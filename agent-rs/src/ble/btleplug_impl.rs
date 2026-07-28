use async_trait::async_trait;
use btleplug::api::{
    Central, CharPropFlags, Characteristic, Manager as _, Peripheral as _,
    ScanFilter as BtleScanFilter,
};
use btleplug::platform::{Adapter, Manager, Peripheral};
use futures_util::stream::StreamExt;
use parking_lot::Mutex;
use std::collections::{BTreeMap, HashMap, HashSet};
use std::sync::Arc;
use std::time::Duration;
use tokio::sync::mpsc;
use tokio::task::JoinHandle;

use super::backend::{BleBackend, StreamKey};
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
    /// Connected devices -> the owning client's event channel, so the adapter
    /// event listener can forward an unsolicited BLE disconnect to the client.
    /// Keyed by device handle (`PeripheralId::to_string()`).
    connected: Arc<Mutex<HashMap<String, mpsc::Sender<AgentEvent>>>>,
    /// Cross-client ownership. An unsolicited BLE drop notifies it so the lease
    /// is released (after its grace window) rather than leaking.
    registry: PeripheralRegistry,
    /// Per-device last-known name / service UUIDs for advertisement coalescing (see
    /// [coalesce_identity]). Scoped to the *scan session*, not the process: [Self::stop_scan]
    /// clears it once the last scan ends, so it can't grow without bound (rotating private
    /// addresses mint a fresh key on every rotation) and identity never bleeds from one scan
    /// into the next. This matches the KMP agent, whose coalescer is per-scan.
    scan_identity: Arc<Mutex<HashMap<String, ScanIdentity>>>,
    /// Tracks devices whose write-with-response completions have stopped arriving. Mirrors
    /// `EngineBleBackend.writeDegraded`/`failFastOnDegradedWrites` in the Kotlin agent — see
    /// [DegradedWrites] for the defect this works around.
    write_degraded: DegradedWrites,
}

/// Last-known `(local name, service UUIDs)` for a device within a scan session, used to
/// backfill sparse advertisement packets (see [coalesce_identity]).
type ScanIdentity = (Option<String>, Vec<String>);

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
    degraded: Mutex<HashSet<String>>,
}

impl DegradedWrites {
    fn new(fail_fast: bool) -> Self {
        Self {
            fail_fast,
            degraded: Mutex::new(HashSet::new()),
        }
    }

    /// Records that `device`'s write-with-response completions have stopped arriving. A no-op
    /// (not even a log) if it's already marked, so a burst of stalled writes to the same device
    /// only logs once.
    fn mark_degraded(&self, device: &str) {
        let newly_degraded = self.degraded.lock().insert(device.to_string());
        if newly_degraded {
            tracing::warn!(
                device,
                fail_fast = self.fail_fast,
                "write did not complete; treating this connection's writes as degraded until it is re-established"
            );
        }
    }

    /// A newly (re-)established connection is the one thing observed to clear this.
    fn clear(&self, device: &str) {
        self.degraded.lock().remove(device);
    }

    fn is_degraded(&self, device: &str) -> bool {
        self.degraded.lock().contains(device)
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
        let scan_identity = Arc::new(Mutex::new(HashMap::new()));
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
                for handle in handles {
                    // Not listed by the adapter anymore -> treat as gone, same as a failed probe.
                    let alive = match by_id.get(&handle) {
                        Some(peripheral) => probe_liveness(peripheral).await,
                        None => false,
                    };
                    if !alive {
                        tracing::warn!("Active liveness probe found {} unresponsive", handle);
                        report_unsolicited_disconnect(&connected, &registry, &handle);
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

                                // Backfill missing name/UUIDs from earlier packets in this scan
                                // session (the map is cleared when scanning stops, see stop_scan).
                                let (name, service_uuids) = coalesce_identity(
                                    &mut scan_identity.lock(),
                                    &device_handle.value,
                                    props.local_name.clone(),
                                    props.services.iter().map(|u| u.to_string()).collect(),
                                );
                                let dto = AdvertisementDto {
                                    device: device_handle,
                                    name,
                                    rssi: props.rssi.unwrap_or(0) as i32,
                                    service_uuids,
                                    manufacturer_data: mfg_data,
                                };

                                tracing::debug!(
                                    "Discovered BLE device: {:?} ({:?})",
                                    props.local_name,
                                    id
                                );
                                for (stream, subscription) in subscribers {
                                    if scan_matches(&subscription.filters, &dto) {
                                        // Advertisements are explicitly lossy under pressure: the
                                        // next packet is a fresher snapshot, and try_send keeps a
                                        // slow client from blocking the adapter event loop.
                                        let _ = subscription.event_tx.try_send(
                                            AgentEvent::ScanResult {
                                                scan_id: stream.local_id,
                                                advertisement: dto.clone(),
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

    async fn find_peripheral(&self, device: &DeviceHandle) -> Result<Peripheral, AgentError> {
        find_peripheral_by_id(&self.adapter, &device.value).await
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
    connected: &Mutex<HashMap<String, mpsc::Sender<AgentEvent>>>,
    registry: &PeripheralRegistry,
    handle: &str,
) {
    let sender = connected.lock().remove(handle);
    if let Some(tx) = sender {
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
        let is_first = {
            let mut scans = self.active_scans.lock();
            scans.insert(stream, ScanSubscription { filters, event_tx });
            scans.len() == 1
        };

        if is_first {
            tracing::info!("Initiating btleplug adapter.start_scan()...");
            if let Err(e) = self.adapter.start_scan(BtleScanFilter::default()).await {
                // Don't leak the registration we just made if the radio never started scanning,
                // or a later stop_scan would never bring the map back to empty.
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

        // A newly (re-)established connection is the one thing observed to clear degraded
        // writes (DegradedWrites' doc) — the prior wedge doesn't carry over.
        self.write_degraded.clear(&device.value);

        // Track the owning client's channel so an unsolicited drop can be reported.
        self.connected
            .lock()
            .insert(device.value.clone(), event_tx.clone());

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
        let sender = self.connected.lock().remove(&device.value);

        if let Ok(peripheral) = self.find_peripheral(device).await {
            let _ = peripheral.disconnect().await;
        }

        if let Some(tx) = sender {
            let _ = tx.try_send(AgentEvent::ConnectionState {
                device: device.clone(),
                state: BleConnState::Disconnected,
                reason: None,
            });
        }
        Ok(())
    }

    async fn discover(&self, device: &DeviceHandle) -> Result<ResultPayload, AgentError> {
        let peripheral = self.find_peripheral(device).await?;
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
        let peripheral = self.find_peripheral(device).await?;
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

        let peripheral = self.find_peripheral(device).await?;
        let characteristic = find_characteristic(&peripheral, char_ref)?;
        let write_type = if with_response {
            btleplug::api::WriteType::WithResponse
        } else {
            btleplug::api::WriteType::WithoutResponse
        };
        let result = gatt_op(
            "write",
            peripheral.write(&characteristic, value, write_type),
        )
        .await;
        if with_response && matches!(result, Err(ref e) if e.kind == ErrorKind::Timeout) {
            self.write_degraded.mark_degraded(&device.value);
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
        let peripheral = self.find_peripheral(device).await?;
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
        if !has_other_observer {
            let peripheral = self.find_peripheral(&observation.device).await?;
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
    last_seen: &mut HashMap<String, ScanIdentity>,
    handle: &str,
    name: Option<String>,
    service_uuids: Vec<String>,
) -> ScanIdentity {
    let entry = last_seen
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
    use super::{DegradedWrites, coalesce_identity, scan_matches};
    use crate::protocol::{
        errors::ErrorKind,
        events::AdvertisementDto,
        op::{DeviceHandle, ScanFilter},
    };
    use std::collections::{BTreeMap, HashMap};

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
        degraded.mark_degraded(DEVICE);

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
        degraded.mark_degraded(DEVICE);

        assert!(degraded.is_degraded(DEVICE));
        assert!(
            degraded.rejection(DEVICE, false).is_none(),
            "WriteWithoutResponse never awaits the ATT response that degrades, so it must not be short-circuited"
        );
    }

    #[test]
    fn degradation_is_per_device_not_agent_wide() {
        let degraded = DegradedWrites::new(true);
        degraded.mark_degraded(DEVICE);

        assert!(
            degraded.rejection("other-device", true).is_none(),
            "one peripheral's stalled writes must not short-circuit a different peripheral"
        );
    }

    #[test]
    fn disabling_fail_fast_keeps_the_unmodified_behaviour_even_once_degraded() {
        let degraded = DegradedWrites::new(false);
        degraded.mark_degraded(DEVICE);

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
        degraded.mark_degraded(DEVICE);
        assert!(degraded.is_degraded(DEVICE));

        degraded.clear(DEVICE);

        assert!(!degraded.is_degraded(DEVICE));
        assert!(degraded.rejection(DEVICE, true).is_none());
    }

    #[test]
    fn retains_last_known_name_and_uuids_when_a_later_packet_omits_them() {
        let mut seen = HashMap::new();

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
        let mut seen = HashMap::new();

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
        let mut seen = HashMap::new();
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
