use async_trait::async_trait;
use btleplug::api::{
    Central, CharPropFlags, Characteristic, Manager as _, Peripheral as _,
    ScanFilter as BtleScanFilter,
};
use btleplug::platform::{Adapter, Manager, Peripheral};
use futures_util::stream::StreamExt;
use parking_lot::Mutex;
use std::collections::{BTreeMap, HashMap, HashSet, VecDeque};
use std::sync::Arc;
use std::time::Duration;
use tokio::sync::mpsc;
use tokio::task::JoinHandle;

use super::backend::{BleBackend, COORDINATOR_SCAN_STREAM, StreamKey};
use crate::protocol::{
    errors::{AgentError, ErrorKind},
    events::{AdvertisementDto, AgentEvent, BleConnState},
    op::{CharRef, DeviceHandle, ScanFilter},
    results::{CharNode, ResultPayload, ServiceNode},
};
use crate::registry::peripheral_lease::PeripheralRegistry;

pub struct BtleplugBackend {
    adapter: Adapter,
    active_scans: Arc<Mutex<HashMap<StreamKey, ScanSubscription>>>,
    /// One notification-pump task per client subscription. The physical BLE subscription is
    /// retained until the final local consumer for the characteristic stops observing.
    active_observations: Arc<Mutex<HashMap<StreamKey, Observation>>>,
    /// Connected devices -> the owning client's event channel plus the live `Peripheral` handle
    /// from the `connect()` that established the link, so the adapter event listener can forward
    /// an unsolicited BLE disconnect to the client and later ops can reuse the same discovered
    /// GATT table instead of re-deriving an independent, undiscovered handle per op (see
    /// [BtleplugBackend::find_connected_peripheral]). Keyed by device handle
    /// (`PeripheralId::to_string()`).
    connected: Arc<Mutex<HashMap<String, ConnectedDevice>>>,
    /// Cross-client ownership. An unsolicited BLE drop notifies it so the lease
    /// is released (after its grace window) rather than leaking.
    registry: PeripheralRegistry,
    /// Legacy direct-scan identity coalescing. Guaranteed-mode scans bypass this state and send
    /// raw advertisements to ScanCoordinator, which owns bounded identity/replay retention.
    scan_identity: Arc<Mutex<ScanIdentityCache>>,
    /// Tracks devices whose write-with-response completions have stopped arriving. Mirrors
    /// `EngineBleBackend.writeDegraded`/`failFastOnDegradedWrites` in the Kotlin agent — see
    /// [DegradedWrites] for the defect this works around.
    write_degraded: DegradedWrites,
}

/// Last-known `(local name, service UUIDs)` for a device within a scan session, used to
/// backfill sparse advertisement packets (see [coalesce_identity]).
type ScanIdentity = (Option<String>, Vec<String>);

const LEGACY_SCAN_IDENTITY_CAP: usize = 256;

#[derive(Default)]
struct ScanIdentityCache {
    entries: HashMap<String, ScanIdentity>,
    order: VecDeque<String>,
}

impl ScanIdentityCache {
    fn clear(&mut self) {
        self.entries.clear();
        self.order.clear();
    }
}

/// Tracks per-device write-with-response degradation and the fail-fast short-circuit.
///
/// Confirmed on hardware (Rig A, 2026-07-28): once btleplug has had one write-with-response
/// answered by a peripheral-side ATT error, it stops delivering write completions for that
/// peripheral for the rest of the connection — later writes reach the peripheral and are
/// accepted, but no completion ever arrives. Reads are unaffected, and a *fresh* connection writes
/// normally, so tearing the connection down is the only observed recovery. Without fail-fast,
/// every subsequent write-with-response still costs a full [GATT_OP_TIMEOUT] before failing; this
/// records the state so [DegradedWrites::rejection] can answer immediately instead.
///
/// Only ever applies to write-**with-response**: `WriteWithoutResponse` has no ATT response to
/// await in the first place (btleplug hands it to the local controller and returns), so it can't
/// be affected by this wedge and must not be short-circuited by it — see [DegradedWrites::rejection].
///
/// Split out from [BtleplugBackend] (which needs a live `Adapter`, so it isn't constructible in
/// tests without hardware) so the gate itself is unit-testable headless.
struct DegradedWrites {
    fail_fast: bool,
    state: Mutex<DegradedState>,
}

#[derive(Default)]
struct DegradedState {
    /// Per-device connection generation, bumped by [DegradedWrites::advance_generation].
    ///
    /// The condition is a property of one *connection*, but a stalled write outlives it: nothing
    /// cancels an in-flight op when the link drops, so a write that hangs can still be waiting out
    /// [GATT_OP_TIMEOUT] long after the client has reconnected. Comparing generations is how
    /// [DegradedWrites::mark_degraded] tells that late timeout apart from a live one.
    generations: HashMap<String, u64>,
    degraded: HashSet<String>,
}

impl DegradedWrites {
    fn new(fail_fast: bool) -> Self {
        Self {
            fail_fast,
            state: Mutex::new(DegradedState::default()),
        }
    }

    /// Records that `device`'s write-with-response completions have stopped arriving. A no-op
    /// (not even a log) if it's already marked, so a burst of stalled writes to the same device
    /// only logs once.
    ///
    /// `generation` is the connection the stalled write belonged to, captured before it ran. If the
    /// device has moved on since, the write is reporting on a connection that no longer exists and
    /// is ignored — otherwise a write that hangs, drops, and expires after the client has already
    /// reconnected would immediately poison the fresh, healthy connection.
    fn mark_degraded(&self, device: &str, generation: u64) {
        let mut state = self.state.lock();
        if state.generations.get(device).copied().unwrap_or(0) != generation {
            drop(state);
            tracing::debug!(
                device,
                "ignoring a stalled write from a previous connection; the current one is unaffected"
            );
            return;
        }
        let newly_degraded = state.degraded.insert(device.to_string());
        drop(state);
        if newly_degraded {
            tracing::warn!(
                device,
                fail_fast = self.fail_fast,
                "write did not complete; treating this connection's writes as degraded until it is re-established"
            );
        }
    }

    /// Clears `device`'s degraded state after a write-with-response actually completed on
    /// `generation` — direct evidence the condition has lifted.
    ///
    /// Only reachable with `fail_fast` off, since fail-fast stops a degraded device's with-response
    /// writes before they reach the radio. That is exactly the configuration where it matters:
    /// without it a device that recovered would stay marked for the rest of the connection, and the
    /// once-only warning above would never fire again for a genuine later stall.
    fn mark_recovered(&self, device: &str, generation: u64) {
        let mut state = self.state.lock();
        if state.generations.get(device).copied().unwrap_or(0) != generation {
            return;
        }
        let recovered = state.degraded.remove(device);
        drop(state);
        if recovered {
            tracing::info!(
                device,
                "write completed again; this connection's writes are no longer degraded"
            );
        }
    }

    /// Starts a new connection generation for `device` and drops the state tied to the previous
    /// one. Called from both `connect` and `disconnect` because both end a connection's life: a
    /// re-established connection is the one thing observed to clear the degraded-write condition,
    /// and a torn-down one cannot be degraded at all.
    fn advance_generation(&self, device: &str) {
        let mut state = self.state.lock();
        let next = state.generations.get(device).copied().unwrap_or(0) + 1;
        state.generations.insert(device.to_string(), next);
        state.degraded.remove(device);
    }

    /// The connection generation `device` is currently on; 0 before it has ever been connected.
    fn generation(&self, device: &str) -> u64 {
        self.state
            .lock()
            .generations
            .get(device)
            .copied()
            .unwrap_or(0)
    }

    fn is_degraded(&self, device: &str) -> bool {
        self.state.lock().degraded.contains(device)
    }

    /// The rejection a write-with-response should raise before touching the radio, or `None` to
    /// proceed normally. Reports [ErrorKind::Timeout] — the same kind and therefore the same
    /// client-visible outcome as letting [gatt_op] expire on this write, since this only changes
    /// how long the failure takes, not what it means.
    ///
    /// `with_response = false` always returns `None`: see this struct's doc for why
    /// WriteWithoutResponse can't be degraded by the defect this guards against.
    fn rejection(&self, device: &str, with_response: bool) -> Option<AgentError> {
        if !with_response || !self.fail_fast || !self.is_degraded(device) {
            return None;
        }
        Some(AgentError::new(
            ErrorKind::Timeout,
            Some(format!(
                "writes on this connection are not completing; reconnect the device \
                 (set REMOTE_BLE_WRITE_FAIL_FAST=false to wait {GATT_OP_TIMEOUT:?} per write instead)"
            )),
        ))
    }
}

struct Observation {
    device: DeviceHandle,
    char_ref: CharRef,
    task: JoinHandle<()>,
}

struct ScanSubscription {
    filters: Vec<ScanFilter>,
    event_tx: mpsc::Sender<AgentEvent>,
}

/// A live, already-`connect()`-ed peripheral handle plus the owning client's event channel.
/// Cloning `peripheral` shares the same underlying btleplug state as the instance stored here —
/// unlike a fresh [find_peripheral_by_id] lookup, which on the BlueZ backend does not inherit
/// another instance's already-discovered GATT table (see
/// [BtleplugBackend::find_connected_peripheral]).
struct ConnectedDevice {
    event_tx: mpsc::Sender<AgentEvent>,
    peripheral: Peripheral,
}

/// How long [BtleplugBackend::spawn_liveness_prober]'s probe waits before treating the link
/// as dead.
const LIVENESS_PROBE_TIMEOUT: Duration = Duration::from_secs(5);

/// How long a single ATT transaction (a characteristic read or write) may run before it is
/// reported as [ErrorKind::Timeout].
///
/// btleplug can fail to *complete* a transaction rather than completing it with an error: on
/// macOS a write-with-response that the peripheral answers with an ATT error never resolves and
/// never yields an `Err`, so an unbounded await parks the command task forever. That is not just
/// slow reporting — the transport chains same-device writes, and a write task that never finishes
/// never drops its completion sender, so every later write to that device blocks behind it. It
/// also holds one of the `MAX_INFLIGHT_OPS` permits for good, so enough hung writes starve every
/// other op too. Nothing unwedges it on its own: the client's timeout is client-side only and the
/// protocol has no cancel op.
///
/// Kept below the client SDK's 15s default op timeout so the client gets a real, explained error
/// from the agent rather than expiring undiagnosed, while leaving far more room than a healthy
/// GATT round-trip needs. Mirrors `EngineBleBackend.GATT_OP_TIMEOUT` in the Kotlin agent.
const GATT_OP_TIMEOUT: Duration = Duration::from_secs(10);

/// Consecutive failed liveness probes before a device is declared dropped.
///
/// A probe is a real GATT round trip, so a single failure does not distinguish a peripheral that is
/// gone from a round trip that merely did not return in time — and the second case is not
/// hypothetical: a probe read of an encrypted characteristic can block on a host pairing dialog
/// until [LIVENESS_PROBE_TIMEOUT], which on Rig A (2026-07-28) tore down a healthy connection on the
/// Kotlin agent. The probe cannot avoid it by choosing a safer characteristic, because encryption is
/// a GATT security *permission* and not visible in the discovered table.
///
/// Costs one extra `liveness_interval` before a genuine silent drop is declared. The adapter's own
/// `DeviceDisconnected` event normally reports a real drop far sooner (measured at 145ms on Rig A),
/// so this loop is the backstop, not the primary detector. Mirrors
/// `ConnectionWatcher.LIVENESS_FAILURES_BEFORE_DROP` in the Kotlin agent.
const LIVENESS_FAILURES_BEFORE_DROP: u32 = 2;

impl BtleplugBackend {
    pub async fn new(
        registry: PeripheralRegistry,
        liveness_interval: Duration,
        fail_fast_on_degraded_writes: bool,
    ) -> Result<Self, AgentError> {
        let manager = Manager::new().await.map_err(|e| {
            AgentError::new(
                ErrorKind::GattError,
                Some(format!("Failed to init BLE manager: {}", e)),
            )
        })?;
        let adapters = manager.adapters().await.map_err(|e| {
            AgentError::new(
                ErrorKind::GattError,
                Some(format!("Failed to list BLE adapters: {}", e)),
            )
        })?;
        let adapter = adapters.into_iter().next().ok_or_else(|| {
            AgentError::new(
                ErrorKind::GattError,
                Some("No Bluetooth adapter found on system".into()),
            )
        })?;

        let active_scans = Arc::new(Mutex::new(HashMap::new()));
        let active_observations = Arc::new(Mutex::new(HashMap::new()));
        let connected = Arc::new(Mutex::new(HashMap::new()));
        let scan_identity = Arc::new(Mutex::new(ScanIdentityCache::default()));
        let backend = Self {
            adapter,
            active_scans,
            active_observations,
            connected,
            registry,
            scan_identity,
            write_degraded: DegradedWrites::new(fail_fast_on_degraded_writes),
        };
        backend.spawn_event_listener();
        backend.spawn_liveness_prober(liveness_interval);
        Ok(backend)
    }

    /// Runs [probe_liveness] against every tracked connection every [interval]. btleplug's
    /// `DeviceDisconnected` event (handled in [Self::spawn_event_listener]) only fires once the
    /// native stack (CoreBluetooth/BlueZ/WinRT) actually reports a disconnect — a peripheral that
    /// vanished without a clean BLE-level teardown (crashed, force-stopped, walked out of range)
    /// can leave that cached state at "connected" until an LL supervision timeout that's tens of
    /// seconds or effectively unbounded. This runs far less often than the event stream reacts
    /// (it does real I/O) but catches exactly that case, feeding the same disconnect path either
    /// way.
    fn spawn_liveness_prober(&self, interval: Duration) {
        let adapter = self.adapter.clone();
        let connected = self.connected.clone();
        let registry = self.registry.clone();

        tokio::spawn(async move {
            let mut ticker = tokio::time::interval(interval);
            ticker.tick().await; // first tick fires immediately; nothing to probe yet
            // Consecutive failed probes per handle; see [LIVENESS_FAILURES_BEFORE_DROP].
            let mut failures: HashMap<String, u32> = HashMap::new();
            loop {
                ticker.tick().await;
                let handles: Vec<String> = connected.lock().keys().cloned().collect();
                if handles.is_empty() {
                    continue;
                }
                // Resolve the adapter's peripheral list *once per tick*, not once per handle:
                // `adapter.peripherals()` is a full enumeration, so a per-handle lookup would be
                // O(connected × total). Index it by id, then probe each tracked handle against it.
                let by_id: HashMap<String, Peripheral> = match adapter.peripherals().await {
                    Ok(ps) => ps.into_iter().map(|p| (p.id().to_string(), p)).collect(),
                    Err(e) => {
                        tracing::warn!("Liveness prober could not list peripherals: {}", e);
                        continue;
                    }
                };
                // Forget handles that are no longer tracked, so this cannot grow with uptime.
                failures.retain(|handle, _| handles.contains(handle));

                for handle in handles {
                    // Not listed by the adapter anymore -> treat as gone, same as a failed probe.
                    let alive = match by_id.get(&handle) {
                        Some(peripheral) => probe_liveness(peripheral).await,
                        None => false,
                    };
                    if alive {
                        failures.remove(&handle);
                        continue;
                    }
                    // A probe is a real GATT round trip, so one failure does not distinguish "the
                    // peripheral is gone" from "this round trip did not return in time". Confirmed
                    // on hardware (Rig A case 3, 2026-07-28) on the Kotlin agent, whose probe read
                    // of an encrypted characteristic blocked on a macOS pairing dialog, timed out,
                    // and tore down a healthy connection. Mirrors
                    // `ConnectionWatcher.LIVENESS_FAILURES_BEFORE_DROP`.
                    let consecutive = failures.entry(handle.clone()).or_insert(0);
                    *consecutive += 1;
                    if *consecutive >= LIVENESS_FAILURES_BEFORE_DROP {
                        tracing::warn!(
                            handle,
                            consecutive = *consecutive,
                            "Active liveness probe found device unresponsive; declaring a drop"
                        );
                        failures.remove(&handle);
                        report_unsolicited_disconnect(&connected, &registry, &handle);
                    } else {
                        tracing::info!(
                            handle,
                            consecutive = *consecutive,
                            threshold = LIVENESS_FAILURES_BEFORE_DROP,
                            "Liveness probe failed; not declaring a drop yet"
                        );
                    }
                }
            }
        });
    }

    fn spawn_event_listener(&self) {
        let adapter = self.adapter.clone();
        let active_scans = self.active_scans.clone();
        let connected = self.connected.clone();
        let registry = self.registry.clone();
        let scan_identity = self.scan_identity.clone();

        tokio::spawn(async move {
            tracing::info!("Starting btleplug event listener task...");
            // Supervising loop: an adapter reset, a Bluetooth toggle, or USB re-enumeration can
            // make `events()` error or end the stream. If we returned then, the agent would go
            // permanently deaf to the radio (no scan results, no disconnect events) while still
            // looking alive. Instead, re-subscribe with capped backoff. Backoff resets after a
            // successful subscribe, so a clean stream-end recovers fast but a dead adapter doesn't
            // spin.
            let mut backoff = Duration::from_millis(0);
            const MAX_BACKOFF: Duration = Duration::from_secs(30);
            loop {
                let mut events = match adapter.events().await {
                    Ok(events) => {
                        tracing::info!("Subscribed to btleplug adapter events stream");
                        backoff = Duration::from_millis(0);
                        events
                    }
                    Err(e) => {
                        backoff = (backoff * 2).clamp(Duration::from_millis(500), MAX_BACKOFF);
                        tracing::error!(
                            "Failed to subscribe to adapter events ({}); retrying in {:?}",
                            e,
                            backoff
                        );
                        tokio::time::sleep(backoff).await;
                        continue;
                    }
                };
                while let Some(event) = events.next().await {
                    tracing::debug!("btleplug event received: {:?}", event);
                    match event {
                        btleplug::api::CentralEvent::DeviceDiscovered(id)
                        | btleplug::api::CentralEvent::DeviceUpdated(id) => {
                            let subscribers: Vec<(StreamKey, ScanSubscription)> = {
                                active_scans
                                    .lock()
                                    .iter()
                                    .map(|(key, subscription)| {
                                        (
                                            *key,
                                            ScanSubscription {
                                                filters: subscription.filters.clone(),
                                                event_tx: subscription.event_tx.clone(),
                                            },
                                        )
                                    })
                                    .collect()
                            };

                            if !subscribers.is_empty()
                                && let Ok(peripheral) = adapter.peripheral(&id).await
                                && let Ok(Some(props)) = peripheral.properties().await
                            {
                                let device_handle = DeviceHandle {
                                    value: id.to_string(),
                                };
                                let mut mfg_data = BTreeMap::new();
                                for (k, v) in props.manufacturer_data {
                                    mfg_data.insert(k as i32, v);
                                }

                                let raw = AdvertisementDto {
                                    device: device_handle,
                                    name: props.local_name.clone(),
                                    rssi: props.rssi.unwrap_or(0) as i32,
                                    service_uuids: props
                                        .services
                                        .iter()
                                        .map(|u| u.to_string())
                                        .collect(),
                                    manufacturer_data: mfg_data,
                                };
                                let mut legacy_coalesced = None;

                                tracing::debug!(
                                    "Discovered BLE device: {:?} ({:?})",
                                    props.local_name,
                                    id
                                );
                                for (stream, subscription) in subscribers {
                                    // The coordinator deliberately receives raw advertisements so
                                    // it alone performs bounded identity merge and logical matching.
                                    // Direct/uncontrolled subscribers retain the legacy backend path.
                                    let dto = if stream == COORDINATOR_SCAN_STREAM {
                                        raw.clone()
                                    } else {
                                        legacy_coalesced
                                            .get_or_insert_with(|| {
                                                let (name, service_uuids) = coalesce_identity(
                                                    &mut scan_identity.lock(),
                                                    &raw.device.value,
                                                    raw.name.clone(),
                                                    raw.service_uuids.clone(),
                                                );
                                                AdvertisementDto {
                                                    device: raw.device.clone(),
                                                    name,
                                                    rssi: raw.rssi,
                                                    service_uuids,
                                                    manufacturer_data: raw
                                                        .manufacturer_data
                                                        .clone(),
                                                }
                                            })
                                            .clone()
                                    };
                                    if stream == COORDINATOR_SCAN_STREAM
                                        || scan_matches(&subscription.filters, &dto)
                                    {
                                        // Advertisements are explicitly lossy under pressure: the
                                        // next packet is a fresher snapshot, and try_send keeps a
                                        // slow client from blocking the adapter event loop.
                                        let _ = subscription.event_tx.try_send(
                                            AgentEvent::ScanResult {
                                                scan_id: stream.local_id,
                                                advertisement: dto,
                                            },
                                        );
                                    }
                                }
                            }
                        }
                        btleplug::api::CentralEvent::DeviceDisconnected(id) => {
                            // Only forward if we still consider this device connected; an
                            // explicit Disconnect op removes it first and reports its own
                            // DISCONNECTED, so this fires only on unsolicited drops.
                            report_unsolicited_disconnect(&connected, &registry, &id.to_string());
                        }
                        _ => {}
                    }
                }
                // Stream ended (adapter went away/reset). Back off, then re-subscribe.
                backoff = (backoff * 2).clamp(Duration::from_millis(500), MAX_BACKOFF);
                tracing::warn!(
                    "btleplug event stream ended; re-subscribing in {:?}",
                    backoff
                );
                tokio::time::sleep(backoff).await;
            }
        });
    }

    /// Best-effort teardown of every tracked link, for graceful shutdown. The OS drops BLE
    /// connections when the process exits anyway, but disconnecting explicitly leaves
    /// peripherals in a clean state for a fast restart.
    pub async fn disconnect_all(&self) {
        let handles: Vec<String> = self.connected.lock().keys().cloned().collect();
        for handle in handles {
            let _ = self.disconnect(&DeviceHandle { value: handle }).await;
        }
    }

    /// Resolves a handle by walking `Adapter::peripherals()`.
    ///
    /// **Known divergence from the Kotlin agent** (Rig A case 3, 2026-07-28): on macOS btleplug
    /// drops a peripheral from that list once it disconnects with no scan running, so a client that
    /// connects, disconnects, then reconnects gets `UNKNOWN_DEVICE` and must rescan first. Kable
    /// builds a `Peripheral` straight from the identifier and has no such dependency.
    ///
    /// Retaining connected peripherals in a cache and falling back to it was tried and **reverted**:
    /// the handle then resolved, but `connect()` on the retained handle never completed (no
    /// `DeviceConnected`, no error, ~45 s to the client's own timeout). That trades a fast,
    /// actionable error for an opaque hang, which is worse. Until there is a way to re-establish
    /// from a bare identifier, `UNKNOWN_DEVICE` — which tells the client exactly what to do — is the
    /// better answer.
    async fn find_peripheral(&self, device: &DeviceHandle) -> Result<Peripheral, AgentError> {
        find_peripheral_by_id(&self.adapter, &device.value).await
    }

    /// Resolves a handle to the live `Peripheral` that [`Self::connect`] established, rather than a
    /// fresh, independently-enumerated one from [`Self::find_peripheral`].
    ///
    /// This is the Rust parity of the Kotlin agent's `EngineBleBackend.peripherals`/`resolve`,
    /// which has always kept one long-lived `Peripheral` per connected device and reused it for
    /// every op. `agent-rs` was the outlier: `discover`/`read`/`write`/`start_observe` each called
    /// [`Self::find_peripheral`], so every op ran against its own instance. On the BlueZ backend
    /// only the instance that ran `discover_services()` reports a populated `services()`, so
    /// `find_characteristic` failed for every read/write/observe *after* a successful discover —
    /// reproduced deterministically on real Linux hardware (Rig D, 2026-08-03). Reusing the
    /// connection-scoped handle also drops one full `Adapter::peripherals()` enumeration per op.
    ///
    /// Unlike the cache described on [`Self::find_peripheral`] (tried and reverted), this never
    /// resolves a handle across a disconnect: entries are removed on both explicit and unsolicited
    /// disconnect and `connect()`'s own resolution is untouched, so a stale handle can never be
    /// reused to attempt a reconnect. A missing entry fails fast with [`ErrorKind::NotConnected`],
    /// matching the Kotlin agent's `requireConnected` — [`ErrorKind::UnknownDevice`] stays reserved
    /// for a handle that identifies no device at all, as it is on [`find_peripheral_by_id`].
    fn find_connected_peripheral(&self, device: &DeviceHandle) -> Result<Peripheral, AgentError> {
        self.connected
            .lock()
            .get(&device.value)
            .map(|c| c.peripheral.clone())
            .ok_or_else(|| {
                AgentError::new(
                    ErrorKind::NotConnected,
                    Some(format!("Device handle {} is not connected", device.value)),
                )
            })
    }
}

async fn find_peripheral_by_id(adapter: &Adapter, id: &str) -> Result<Peripheral, AgentError> {
    let peripherals = adapter
        .peripherals()
        .await
        .map_err(|e| AgentError::new(ErrorKind::GattError, Some(e.to_string())))?;

    for p in peripherals {
        if p.id().to_string() == id {
            return Ok(p);
        }
    }

    Err(AgentError::new(
        ErrorKind::UnknownDevice,
        Some(format!("Device handle {} not found", id)),
    ))
}

/// Resolves a [CharRef] to the matching characteristic in the peripheral's discovered GATT
/// table, or [ErrorKind::CharacteristicNotFound] if absent. UUID comparison is
/// case-insensitive: btleplug stringifies UUIDs lowercase, but a client may send either case.
fn find_characteristic(
    peripheral: &Peripheral,
    char_ref: &CharRef,
) -> Result<Characteristic, AgentError> {
    peripheral
        .services()
        .into_iter()
        .filter(|s| s.uuid.to_string().eq_ignore_ascii_case(&char_ref.service))
        .flat_map(|s| s.characteristics)
        .find(|c| {
            c.uuid
                .to_string()
                .eq_ignore_ascii_case(&char_ref.characteristic)
        })
        .ok_or_else(|| {
            AgentError::new(
                ErrorKind::CharacteristicNotFound,
                Some(format!(
                    "Char {} not found in service {}",
                    char_ref.characteristic, char_ref.service
                )),
            )
        })
}

/// Maps btleplug's characteristic property flags to the protocol's property bitmask (the same
/// bit values Kotlin's `CharacteristicProperties` uses, so both agents report identically).
fn char_prop_mask(flags: CharPropFlags) -> i32 {
    let mut mask = 0i32;
    if flags.contains(CharPropFlags::READ) {
        mask |= 0x02;
    }
    if flags.contains(CharPropFlags::WRITE_WITHOUT_RESPONSE) {
        mask |= 0x04;
    }
    if flags.contains(CharPropFlags::WRITE) {
        mask |= 0x08;
    }
    if flags.contains(CharPropFlags::NOTIFY) {
        mask |= 0x10;
    }
    if flags.contains(CharPropFlags::INDICATE) {
        mask |= 0x20;
    }
    mask
}

/// Forces a real GATT round-trip so a dead link is caught even while btleplug/the native
/// stack still reports it connected (see [BtleplugBackend::spawn_liveness_prober]).
/// Re-running service discovery is the probe: it's safe to repeat (idempotent, no destructive
/// side effects — unlike picking an arbitrary characteristic that might not exist or be
/// readable) and requires an actual over-the-air exchange to succeed.
///
/// Trade-off: `discover_services()` refreshes btleplug's cached service/characteristic table for
/// this peripheral, so a client op racing a probe on the same device sees a momentary
/// re-discovery. That's acceptable here — the probe runs far less often than client ops and
/// completes quickly on a healthy link, and the alternative (a silently dead link the agent keeps
/// reporting as connected) is worse.
async fn probe_liveness(peripheral: &Peripheral) -> bool {
    if !peripheral.is_connected().await.unwrap_or(false) {
        return false;
    }
    tokio::time::timeout(LIVENESS_PROBE_TIMEOUT, peripheral.discover_services())
        .await
        .is_ok_and(|result| result.is_ok())
}

/// Bounds one ATT transaction by [GATT_OP_TIMEOUT], see that constant for why.
///
/// Expiry is reported as [ErrorKind::Timeout] rather than as the op's own failure kind
/// (ReadFailed/WriteFailed): a transaction that never completes has an *unknown* outcome — the
/// peripheral may have received and applied it — so claiming "the radio said no" would assert more
/// than is known. Timeout is the honest "no answer" kind, and it stays retry-safe because writes
/// are not idempotent, so a policy still won't blind-retry a possibly-applied write.
///
/// The inner `Result` is the operation's own, left for the caller to map to its failure kind.
async fn gatt_op<T, E>(
    op: &str,
    future: impl Future<Output = Result<T, E>>,
) -> Result<Result<T, E>, AgentError> {
    match tokio::time::timeout(GATT_OP_TIMEOUT, future).await {
        Ok(result) => Ok(result),
        Err(_) => {
            tracing::warn!(
                op,
                ?GATT_OP_TIMEOUT,
                "ATT transaction did not complete; reporting TIMEOUT"
            );
            Err(AgentError::new(
                ErrorKind::Timeout,
                Some(format!("{op} did not complete within {GATT_OP_TIMEOUT:?}")),
            ))
        }
    }
}

/// Reports an unsolicited drop to the owning client and the registry, and stops tracking the
/// device — shared by the adapter event listener and the active liveness prober, the two
/// independent ways a drop can be noticed. A no-op if the device isn't tracked (e.g. an
/// explicit `Disconnect` op already removed it).
fn report_unsolicited_disconnect(
    connected: &Mutex<HashMap<String, ConnectedDevice>>,
    registry: &PeripheralRegistry,
    handle: &str,
) {
    let removed = connected.lock().remove(handle);
    if let Some(ConnectedDevice { event_tx: tx, .. }) = removed {
        tracing::info!("BLE device disconnected (unsolicited): {}", handle);
        let _ = tx.try_send(AgentEvent::ConnectionState {
            device: DeviceHandle {
                value: handle.to_string(),
            },
            state: BleConnState::Disconnected,
            reason: Some(AgentError::new(
                ErrorKind::Disconnected,
                Some("peer disconnected".into()),
            )),
        });
        // Free the lease (after its grace window) so the slot doesn't leak.
        registry.on_ble_disconnected(handle);
    }
}

#[async_trait]
impl BleBackend for BtleplugBackend {
    fn capabilities(&self) -> Vec<String> {
        // Baseline v1 only. Advertise a capability *here* once it is actually
        // implemented end-to-end (descriptors, pairing, conn.priority, scan.batch,
        // slots) so a client never negotiates something we'd answer UNSUPPORTED.
        vec![]
    }

    async fn start_scan(
        &self,
        stream: StreamKey,
        filters: Vec<ScanFilter>,
        event_tx: mpsc::Sender<AgentEvent>,
    ) -> Result<(), AgentError> {
        tracing::info!("Received start_scan request (scan_id: {})", stream.local_id);
        // Only the empty -> non-empty transition needs the radio. This backend deliberately scans
        // unfiltered and matches agent-side, so `filters` never reaches the adapter and one running
        // physical scan already serves every subscriber, new or reconfigured. Re-issuing
        // `adapter.start_scan()` for a key that is merely rebinding would be a redundant call
        // against an already-discovering adapter, whose per-platform behaviour is not uniform.
        //
        // Emptiness is decided and the registration published under ONE lock acquisition. Reading
        // it, dropping the lock and registering after the await would let two concurrent starts
        // both see an empty map and both drive the adapter, and would drop advertisements arriving
        // in between. Note `is_first` is taken *before* the insert: a reconfigure of the sole
        // active scan leaves the map non-empty, so it is not first and must not touch the radio.
        let is_first = {
            let mut scans = self.active_scans.lock();
            let is_first = scans.is_empty();
            scans.insert(stream, ScanSubscription { filters, event_tx });
            is_first
        };

        if is_first {
            tracing::info!("Initiating btleplug adapter.start_scan()...");
            if let Err(e) = self.adapter.start_scan(BtleScanFilter::default()).await {
                // Reachable only when this call created the first registration, so rolling it back
                // cannot clobber an incumbent subscription that surviving logical scans depend on.
                // Without the rollback a later stop_scan would never bring the map back to empty.
                self.active_scans.lock().remove(&stream);
                return Err(AgentError::new(
                    ErrorKind::GattError,
                    Some(format!("Failed to start scan: {}", e)),
                ));
            }
        }
        Ok(())
    }

    async fn stop_scan(&self, stream: StreamKey) -> Result<(), AgentError> {
        tracing::info!("Received stop_scan request (scan_id: {})", stream.local_id);
        let should_stop = {
            let mut scans = self.active_scans.lock();
            scans.remove(&stream);
            scans.is_empty()
        };

        if should_stop {
            let _ = self.adapter.stop_scan().await;
            // Last scan ended: drop the coalescing memory so identity can't bleed into a future
            // scan and the map can't grow unbounded across the process lifetime (see the field).
            self.scan_identity.lock().clear();
        }
        Ok(())
    }

    async fn stop_connection_streams(&self, connection: u64) -> Result<(), AgentError> {
        let should_stop_scan = {
            let mut scans = self.active_scans.lock();
            scans.retain(|stream, _| stream.connection != connection);
            scans.is_empty()
        };
        if should_stop_scan {
            let _ = self.adapter.stop_scan().await;
            self.scan_identity.lock().clear();
        }

        let observation_keys: Vec<StreamKey> = self
            .active_observations
            .lock()
            .keys()
            .filter(|stream| stream.connection == connection)
            .copied()
            .collect();
        for stream in observation_keys {
            self.stop_observe(stream).await?;
        }
        Ok(())
    }

    async fn connect(
        &self,
        device: &DeviceHandle,
        event_tx: mpsc::Sender<AgentEvent>,
    ) -> Result<(), AgentError> {
        let peripheral = self.find_peripheral(device).await?;
        if !peripheral.is_connected().await.unwrap_or(false) {
            peripheral.connect().await.map_err(|e| {
                AgentError::new(
                    ErrorKind::ConnectionFailed,
                    Some(format!("Connect failed: {}", e)),
                )
            })?;
        }

        // A newly (re-)established connection is the one thing observed to clear degraded writes
        // (DegradedWrites' doc), and it also retires any write still running against the previous
        // connection.
        self.write_degraded.advance_generation(&device.value);

        // Track the owning client's channel (so an unsolicited drop can be reported) and this
        // now-connected handle (so later ops reuse its discovered GATT table — see
        // find_connected_peripheral). connect()'s own lookup above is untouched: it still always
        // does a fresh find_peripheral() scan, never falls back to a cached handle.
        self.connected.lock().insert(
            device.value.clone(),
            ConnectedDevice {
                event_tx: event_tx.clone(),
                peripheral,
            },
        );

        let _ = event_tx.try_send(AgentEvent::ConnectionState {
            device: device.clone(),
            state: BleConnState::Connected,
            reason: None,
        });
        Ok(())
    }

    async fn disconnect(&self, device: &DeviceHandle) -> Result<(), AgentError> {
        // Drop the tracking entry *before* tearing down the link so the adapter
        // event listener treats the resulting DeviceDisconnected as solicited and
        // stays quiet — we report the DISCONNECTED state ourselves below.
        let removed = self.connected.lock().remove(&device.value);

        // Retire the generation too: a connection that no longer exists can't be degraded, and a
        // write still running against it must not degrade its successor.
        self.write_degraded.advance_generation(&device.value);

        // Prefer the tracked handle (avoids a redundant adapter.peripherals() scan); fall back to
        // a fresh lookup if the device wasn't tracked (e.g. this races an unsolicited drop that
        // already removed it).
        let peripheral = match &removed {
            Some(ConnectedDevice { peripheral, .. }) => Ok(peripheral.clone()),
            None => self.find_peripheral(device).await,
        };
        if let Ok(peripheral) = peripheral {
            let _ = peripheral.disconnect().await;
        }

        if let Some(ConnectedDevice { event_tx: tx, .. }) = removed {
            let _ = tx.try_send(AgentEvent::ConnectionState {
                device: device.clone(),
                state: BleConnState::Disconnected,
                reason: None,
            });
        }
        Ok(())
    }

    async fn discover(&self, device: &DeviceHandle) -> Result<ResultPayload, AgentError> {
        let peripheral = self.find_connected_peripheral(device)?;
        peripheral.discover_services().await.map_err(|e| {
            AgentError::new(
                ErrorKind::GattError,
                Some(format!("Discovery failed: {}", e)),
            )
        })?;

        let service_nodes = peripheral
            .services()
            .into_iter()
            .map(|service| ServiceNode {
                uuid: service.uuid.to_string(),
                characteristics: service
                    .characteristics
                    .into_iter()
                    .map(|c| CharNode {
                        uuid: c.uuid.to_string(),
                        properties: char_prop_mask(c.properties),
                        descriptors: vec![],
                    })
                    .collect(),
            })
            .collect();

        Ok(ResultPayload::Services {
            services: service_nodes,
        })
    }

    async fn read(
        &self,
        device: &DeviceHandle,
        char_ref: &CharRef,
    ) -> Result<ResultPayload, AgentError> {
        let peripheral = self.find_connected_peripheral(device)?;
        let characteristic = find_characteristic(&peripheral, char_ref)?;
        let bytes = gatt_op("read", peripheral.read(&characteristic))
            .await?
            .map_err(|e| AgentError::new(ErrorKind::ReadFailed, Some(e.to_string())))?;
        Ok(ResultPayload::Bytes { value: bytes })
    }

    async fn write(
        &self,
        device: &DeviceHandle,
        char_ref: &CharRef,
        value: &[u8],
        with_response: bool,
    ) -> Result<(), AgentError> {
        if let Some(rejection) = self.write_degraded.rejection(&device.value, with_response) {
            return Err(rejection);
        }

        let peripheral = self.find_connected_peripheral(device)?;
        let characteristic = find_characteristic(&peripheral, char_ref)?;
        let write_type = if with_response {
            btleplug::api::WriteType::WithResponse
        } else {
            btleplug::api::WriteType::WithoutResponse
        };
        // Captured before the write, which may run for the full GATT_OP_TIMEOUT: by the time it
        // resolves the client may have dropped and reconnected, and this write's outcome then
        // describes a connection that is already gone. See DegradedState::generations.
        let generation = self.write_degraded.generation(&device.value);
        let result = gatt_op(
            "write",
            peripheral.write(&characteristic, value, write_type),
        )
        .await;
        if with_response {
            match &result {
                Err(e) if e.kind == ErrorKind::Timeout => {
                    self.write_degraded.mark_degraded(&device.value, generation)
                }
                Ok(Ok(())) => self
                    .write_degraded
                    .mark_recovered(&device.value, generation),
                _ => {}
            }
        }
        result?.map_err(|e| AgentError::new(ErrorKind::WriteFailed, Some(e.to_string())))
    }

    async fn request_mtu(
        &self,
        _device: &DeviceHandle,
        _mtu: i32,
    ) -> Result<ResultPayload, AgentError> {
        // btleplug does not expose negotiated ATT MTU on the supported desktop backends. Echoing
        // the requested value would falsely claim a negotiation succeeded.
        Err(AgentError::new(
            ErrorKind::Unsupported,
            Some("btleplug backend cannot negotiate or report ATT MTU".into()),
        ))
    }

    async fn start_observe(
        &self,
        stream: StreamKey,
        device: &DeviceHandle,
        char_ref: &CharRef,
        event_tx: mpsc::Sender<AgentEvent>,
    ) -> Result<(), AgentError> {
        // Replacing the same connection-local subscription must retire its old pump first.
        self.stop_observe(stream).await?;
        let peripheral = self.find_connected_peripheral(device)?;
        let characteristic = find_characteristic(&peripheral, char_ref)?;
        let needs_subscribe =
            !self.active_observations.lock().values().any(|observation| {
                observation.device == *device && observation.char_ref == *char_ref
            });
        if needs_subscribe {
            peripheral
                .subscribe(&characteristic)
                .await
                .map_err(|e| AgentError::new(ErrorKind::GattError, Some(e.to_string())))?;
        }

        let mut notifications = peripheral
            .notifications()
            .await
            .map_err(|e| AgentError::new(ErrorKind::GattError, Some(e.to_string())))?;

        let target_uuid = characteristic.uuid;
        let task = tokio::spawn(async move {
            while let Some(notification) = notifications.next().await {
                if notification.uuid == target_uuid {
                    match event_tx.try_send(AgentEvent::Notification {
                        sub_id: stream.local_id,
                        value: notification.value,
                    }) {
                        Ok(()) => {}
                        Err(mpsc::error::TrySendError::Full(_)) => {
                            // A notification stream is not safely coalescible. End this pump on
                            // overflow rather than retaining data or silently losing an arbitrary
                            // suffix; callers can explicitly subscribe again after recovery.
                            tracing::warn!(
                                "notification stream {} terminated: event buffer full",
                                stream.local_id
                            );
                            break;
                        }
                        Err(mpsc::error::TrySendError::Closed(_)) => break,
                    }
                }
            }
        });
        self.active_observations.lock().insert(
            stream,
            Observation {
                device: device.clone(),
                char_ref: char_ref.clone(),
                task,
            },
        );
        Ok(())
    }

    async fn stop_observe(&self, stream: StreamKey) -> Result<(), AgentError> {
        let observation = self.active_observations.lock().remove(&stream);
        let Some(observation) = observation else {
            return Ok(());
        };

        observation.task.abort();
        let _ = observation.task.await;
        let has_other_observer = self.active_observations.lock().values().any(|other| {
            other.device == observation.device && other.char_ref == observation.char_ref
        });
        // A device that's already disconnected has nothing to unsubscribe from — the physical
        // subscription ended with the link, and erroring here would abort a batch cleanup loop
        // (see the connection-teardown caller) partway through the other observations it still
        // needs to stop.
        if !has_other_observer
            && let Ok(peripheral) = self.find_connected_peripheral(&observation.device)
        {
            let characteristic = find_characteristic(&peripheral, &observation.char_ref)?;
            peripheral
                .unsubscribe(&characteristic)
                .await
                .map_err(|e| AgentError::new(ErrorKind::GattError, Some(e.to_string())))?;
        }
        Ok(())
    }
}

/// Fills a missing local name / service-UUID list from the last advertisement that carried them.
///
/// BLE advertisements for one device arrive repeatedly and individual packets routinely omit the
/// local name or service UUIDs (a bare RSSI refresh is normal); forwarding such a sparse packet
/// verbatim makes the device flicker to "unnamed" on the client. This records the last-known name
/// and service UUIDs per device and backfills them when a later packet lacks them. RSSI is not
/// retained — it legitimately varies per packet and is forwarded as received.
fn coalesce_identity(
    last_seen: &mut ScanIdentityCache,
    handle: &str,
    name: Option<String>,
    service_uuids: Vec<String>,
) -> ScanIdentity {
    let entry = last_seen
        .entries
        .entry(handle.to_string())
        .or_insert_with(|| (None, Vec::new()));
    if name.is_some() {
        entry.0 = name.clone();
    }
    let resolved_uuids = if service_uuids.is_empty() {
        entry.1.clone()
    } else {
        entry.1 = service_uuids.clone();
        service_uuids
    };
    let resolved_name = name.or_else(|| entry.0.clone());
    last_seen.order.retain(|key| key != handle);
    last_seen.order.push_back(handle.to_string());
    while last_seen.entries.len() > LEGACY_SCAN_IDENTITY_CAP {
        if let Some(oldest) = last_seen.order.pop_front() {
            last_seen.entries.remove(&oldest);
        } else {
            break;
        }
    }
    (resolved_name, resolved_uuids)
}

/// Applies the protocol's per-subscriber scan semantics after adapter fan-out. Filters are ORed;
/// populated fields within an individual filter are ANDed. An empty filter list (or empty filter)
/// matches every advertisement.
fn scan_matches(filters: &[ScanFilter], advertisement: &AdvertisementDto) -> bool {
    filters.is_empty()
        || filters.iter().any(|filter| {
            let name_matches = filter
                .name
                .as_ref()
                .is_none_or(|name| advertisement.name.as_deref() == Some(name.as_str()));
            let service_matches = filter.service.as_ref().is_none_or(|service| {
                advertisement
                    .service_uuids
                    .iter()
                    .any(|uuid| uuid.eq_ignore_ascii_case(service))
            });
            name_matches && service_matches
        })
}

#[cfg(test)]
mod tests {
    use super::{
        DegradedWrites, LEGACY_SCAN_IDENTITY_CAP, ScanIdentityCache, coalesce_identity,
        scan_matches,
    };
    use crate::protocol::{
        errors::ErrorKind,
        events::AdvertisementDto,
        op::{DeviceHandle, ScanFilter},
    };
    use std::collections::BTreeMap;

    // --- degraded-write fail-fast ---------------------------------------------------------
    // The condition itself (btleplug dropping write completions after an ATT error) reproduces
    // only on hardware; what is testable here is the gate — that the state is tracked, that the
    // switch actually switches, and that the short-circuit reports the same kind as waiting would.

    const DEVICE: &str = "11111111-2222-3333-4444-555555555555";

    #[test]
    fn writes_are_not_degraded_until_one_fails_to_complete() {
        let degraded = DegradedWrites::new(true);
        assert!(
            degraded.rejection(DEVICE, true).is_none(),
            "a device with no stalled write must not be short-circuited"
        );
    }

    #[test]
    fn a_stalled_write_marks_the_connection_and_short_circuits_later_with_response_writes() {
        let degraded = DegradedWrites::new(true);
        degraded.mark_degraded(DEVICE, degraded.generation(DEVICE));

        assert!(degraded.is_degraded(DEVICE));
        let rejection = degraded
            .rejection(DEVICE, true)
            .expect("a degraded device must short-circuit a with-response write");
        // Same kind the caller would have got by waiting out GATT_OP_TIMEOUT — the point of the
        // short-circuit is latency, not a different outcome.
        assert_eq!(rejection.kind, ErrorKind::Timeout);
        assert!(
            rejection
                .message
                .as_deref()
                .is_some_and(|m| m.contains("reconnect")),
            "the message should tell the operator what recovers it, got: {:?}",
            rejection.message
        );
    }

    /// Regression test (Rig A, 2026-07-28): a degraded connection must still let
    /// WriteWithoutResponse through, since it never awaits the ATT response that actually
    /// wedges (see [DegradedWrites]'s doc).
    #[test]
    fn a_degraded_device_still_lets_write_without_response_through() {
        let degraded = DegradedWrites::new(true);
        degraded.mark_degraded(DEVICE, degraded.generation(DEVICE));

        assert!(degraded.is_degraded(DEVICE));
        assert!(
            degraded.rejection(DEVICE, false).is_none(),
            "WriteWithoutResponse never awaits the ATT response that degrades, so it must not be short-circuited"
        );
    }

    #[test]
    fn degradation_is_per_device_not_agent_wide() {
        let degraded = DegradedWrites::new(true);
        degraded.mark_degraded(DEVICE, degraded.generation(DEVICE));

        assert!(
            degraded.rejection("other-device", true).is_none(),
            "one peripheral's stalled writes must not short-circuit a different peripheral"
        );
    }

    #[test]
    fn disabling_fail_fast_keeps_the_unmodified_behaviour_even_once_degraded() {
        let degraded = DegradedWrites::new(false);
        degraded.mark_degraded(DEVICE, degraded.generation(DEVICE));

        assert!(
            degraded.is_degraded(DEVICE),
            "the state is still tracked for logging"
        );
        assert!(
            degraded.rejection(DEVICE, true).is_none(),
            "with fail-fast off, a degraded device must still attempt the write"
        );
    }

    #[test]
    fn a_fresh_connection_clears_the_degraded_state() {
        let degraded = DegradedWrites::new(true);
        degraded.mark_degraded(DEVICE, degraded.generation(DEVICE));
        assert!(degraded.is_degraded(DEVICE));

        degraded.advance_generation(DEVICE);

        assert!(!degraded.is_degraded(DEVICE));
        assert!(degraded.rejection(DEVICE, true).is_none());
    }

    /// Nothing cancels an in-flight op when a link drops, so a write that hangs can still be
    /// waiting out [GATT_OP_TIMEOUT] long after the client has reconnected. Marking on the bare
    /// handle would let that late timeout degrade the *fresh* connection, which — with fail-fast on
    /// by default — fails every subsequent with-response write on a healthy connection.
    #[test]
    fn a_stalled_write_from_a_previous_connection_does_not_degrade_the_current_one() {
        let degraded = DegradedWrites::new(true);
        let when_the_write_started = degraded.generation(DEVICE);
        // The client dropped and reconnected while that write was still hanging.
        degraded.advance_generation(DEVICE);

        degraded.mark_degraded(DEVICE, when_the_write_started);

        assert!(
            !degraded.is_degraded(DEVICE),
            "a previous connection's stall must not carry over"
        );
        assert!(degraded.rejection(DEVICE, true).is_none());
    }

    #[test]
    fn a_stalled_write_on_the_current_connection_still_degrades_it() {
        // The guard above must not over-reach: a stall reported against the live generation is the
        // real case the workaround exists for.
        let degraded = DegradedWrites::new(true);
        degraded.advance_generation(DEVICE);

        degraded.mark_degraded(DEVICE, degraded.generation(DEVICE));

        assert!(degraded.is_degraded(DEVICE));
    }

    #[test]
    fn a_completed_write_clears_the_degraded_state() {
        // Reachable with fail-fast off, where a degraded device's writes still reach the radio: one
        // that completes is direct evidence the condition has lifted.
        let degraded = DegradedWrites::new(false);
        degraded.mark_degraded(DEVICE, degraded.generation(DEVICE));
        assert!(degraded.is_degraded(DEVICE));

        degraded.mark_recovered(DEVICE, degraded.generation(DEVICE));

        assert!(!degraded.is_degraded(DEVICE));
    }

    #[test]
    fn a_completed_write_from_a_previous_connection_does_not_clear_the_current_one() {
        let degraded = DegradedWrites::new(false);
        let when_the_write_started = degraded.generation(DEVICE);
        degraded.advance_generation(DEVICE);
        degraded.mark_degraded(DEVICE, degraded.generation(DEVICE));

        degraded.mark_recovered(DEVICE, when_the_write_started);

        assert!(
            degraded.is_degraded(DEVICE),
            "a previous connection's success says nothing about the current one"
        );
    }

    #[test]
    fn retains_last_known_name_and_uuids_when_a_later_packet_omits_them() {
        let mut seen = ScanIdentityCache::default();

        let first = coalesce_identity(
            &mut seen,
            "dev",
            Some("Heart Monitor".to_string()),
            vec!["180d".to_string()],
        );
        assert_eq!(
            first,
            (Some("Heart Monitor".to_string()), vec!["180d".to_string()])
        );

        // A bare refresh: no name, no service UUIDs -> identity is backfilled.
        let second = coalesce_identity(&mut seen, "dev", None, Vec::new());
        assert_eq!(
            second,
            (Some("Heart Monitor".to_string()), vec!["180d".to_string()])
        );
    }

    #[test]
    fn adopts_a_name_once_one_arrives_and_keeps_devices_separate() {
        let mut seen = ScanIdentityCache::default();

        let a1 = coalesce_identity(&mut seen, "a", None, Vec::new());
        assert_eq!(a1, (None, Vec::<String>::new()));

        let a2 = coalesce_identity(&mut seen, "a", Some("A".to_string()), Vec::new());
        assert_eq!(a2.0, Some("A".to_string()));

        // A different device is tracked independently.
        let b1 = coalesce_identity(&mut seen, "b", None, Vec::new());
        assert_eq!(b1.0, None);
    }

    #[test]
    fn clearing_between_scans_drops_stale_identity() {
        let mut seen = ScanIdentityCache::default();
        coalesce_identity(
            &mut seen,
            "dev",
            Some("Old Name".to_string()),
            vec!["180d".to_string()],
        );

        // stop_scan clears the shared map when the last scan ends.
        seen.clear();

        // A fresh scan session: a nameless packet must NOT resurrect the previous scan's identity.
        let after = coalesce_identity(&mut seen, "dev", None, Vec::new());
        assert_eq!(after, (None, Vec::<String>::new()));
    }

    #[test]
    fn legacy_identity_cache_evicts_the_oldest_device_at_capacity() {
        let mut seen = ScanIdentityCache::default();
        for index in 0..=LEGACY_SCAN_IDENTITY_CAP {
            coalesce_identity(
                &mut seen,
                &format!("device-{index}"),
                Some(format!("name-{index}")),
                Vec::new(),
            );
        }
        assert_eq!(seen.entries.len(), LEGACY_SCAN_IDENTITY_CAP);
        assert!(!seen.entries.contains_key("device-0"));
        assert!(
            seen.entries
                .contains_key(&format!("device-{LEGACY_SCAN_IDENTITY_CAP}"))
        );
    }

    #[test]
    fn applies_name_and_service_filters_per_subscriber() {
        let advertisement = AdvertisementDto {
            device: DeviceHandle {
                value: "dev".into(),
            },
            name: Some("Heart Monitor".into()),
            rssi: -55,
            service_uuids: vec!["180D".into()],
            manufacturer_data: BTreeMap::new(),
        };

        assert!(scan_matches(&[], &advertisement));
        assert!(scan_matches(
            &[ScanFilter {
                service: Some("180d".into()),
                name: None
            }],
            &advertisement
        ));
        assert!(scan_matches(
            &[ScanFilter {
                service: Some("180d".into()),
                name: Some("Heart Monitor".into())
            }],
            &advertisement
        ));
        assert!(!scan_matches(
            &[ScanFilter {
                service: Some("180f".into()),
                name: None
            }],
            &advertisement
        ));
        assert!(!scan_matches(
            &[ScanFilter {
                service: None,
                name: Some("Other".into())
            }],
            &advertisement
        ));
    }
}
