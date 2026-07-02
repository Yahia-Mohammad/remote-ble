use async_trait::async_trait;
use btleplug::api::{
    Central, CharPropFlags, Manager as _, Peripheral as _, ScanFilter as BtleScanFilter,
};
use btleplug::platform::{Adapter, Manager, Peripheral};
use futures_util::stream::StreamExt;
use parking_lot::Mutex;
use std::collections::{BTreeMap, HashMap};
use std::sync::Arc;
use std::time::Duration;
use tokio::sync::mpsc;

use super::backend::BleBackend;
use crate::protocol::{
    errors::{AgentError, ErrorKind},
    events::{AdvertisementDto, AgentEvent, BleConnState},
    op::{CharRef, DeviceHandle},
    results::{CharNode, ResultPayload, ServiceNode},
};
use crate::registry::peripheral_lease::PeripheralRegistry;

pub struct BtleplugBackend {
    adapter: Adapter,
    active_scans: Arc<Mutex<HashMap<i64, mpsc::UnboundedSender<AgentEvent>>>>,
    /// Connected devices -> the owning client's event channel, so the adapter
    /// event listener can forward an unsolicited BLE disconnect to the client.
    /// Keyed by device handle (`PeripheralId::to_string()`).
    connected: Arc<Mutex<HashMap<String, mpsc::UnboundedSender<AgentEvent>>>>,
    /// Cross-client ownership. An unsolicited BLE drop notifies it so the lease
    /// is released (after its grace window) rather than leaking.
    registry: PeripheralRegistry,
    /// Per-device last-known name / service UUIDs for advertisement coalescing (see
    /// [coalesce_identity]). Scoped to the *scan session*, not the process: [Self::stop_scan]
    /// clears it once the last scan ends, so it can't grow without bound (rotating private
    /// addresses mint a fresh key on every rotation) and identity never bleeds from one scan
    /// into the next. This matches the KMP agent, whose coalescer is per-scan.
    scan_identity: Arc<Mutex<HashMap<String, (Option<String>, Vec<String>)>>>,
}

/// How long [BtleplugBackend::spawn_liveness_prober]'s probe waits before treating the link
/// as dead.
const LIVENESS_PROBE_TIMEOUT: Duration = Duration::from_secs(5);

impl BtleplugBackend {
    pub async fn new(
        registry: PeripheralRegistry,
        liveness_interval: Duration,
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
        let connected = Arc::new(Mutex::new(HashMap::new()));
        let scan_identity = Arc::new(Mutex::new(HashMap::new()));
        let backend = Self {
            adapter,
            active_scans,
            connected,
            registry,
            scan_identity,
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
                            let senders: Vec<(i64, mpsc::UnboundedSender<AgentEvent>)> = {
                                active_scans
                                    .lock()
                                    .iter()
                                    .map(|(k, v)| (*k, v.clone()))
                                    .collect()
                            };

                            if !senders.is_empty()
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
                                for (scan_id, tx) in senders {
                                    let _ = tx.send(AgentEvent::ScanResult {
                                        scan_id,
                                        advertisement: dto.clone(),
                                    });
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

/// Reports an unsolicited drop to the owning client and the registry, and stops tracking the
/// device — shared by the adapter event listener and the active liveness prober, the two
/// independent ways a drop can be noticed. A no-op if the device isn't tracked (e.g. an
/// explicit `Disconnect` op already removed it).
fn report_unsolicited_disconnect(
    connected: &Mutex<HashMap<String, mpsc::UnboundedSender<AgentEvent>>>,
    registry: &PeripheralRegistry,
    handle: &str,
) {
    let sender = connected.lock().remove(handle);
    if let Some(tx) = sender {
        tracing::info!("BLE device disconnected (unsolicited): {}", handle);
        let _ = tx.send(AgentEvent::ConnectionState {
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
        scan_id: i64,
        event_tx: mpsc::UnboundedSender<AgentEvent>,
    ) -> Result<(), AgentError> {
        tracing::info!("Received start_scan request (scan_id: {})", scan_id);
        let is_first = {
            let mut scans = self.active_scans.lock();
            scans.insert(scan_id, event_tx);
            scans.len() == 1
        };

        if is_first {
            tracing::info!("Initiating btleplug adapter.start_scan()...");
            if let Err(e) = self.adapter.start_scan(BtleScanFilter::default()).await {
                // Don't leak the registration we just made if the radio never started scanning,
                // or a later stop_scan would never bring the map back to empty.
                self.active_scans.lock().remove(&scan_id);
                return Err(AgentError::new(
                    ErrorKind::GattError,
                    Some(format!("Failed to start scan: {}", e)),
                ));
            }
        }
        Ok(())
    }

    async fn stop_scan(&self, scan_id: i64) -> Result<(), AgentError> {
        tracing::info!("Received stop_scan request (scan_id: {})", scan_id);
        let should_stop = {
            let mut scans = self.active_scans.lock();
            scans.remove(&scan_id);
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

    async fn connect(
        &self,
        device: &DeviceHandle,
        event_tx: mpsc::UnboundedSender<AgentEvent>,
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

        // Track the owning client's channel so an unsolicited drop can be reported.
        self.connected
            .lock()
            .insert(device.value.clone(), event_tx.clone());

        let _ = event_tx.send(AgentEvent::ConnectionState {
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
            let _ = tx.send(AgentEvent::ConnectionState {
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

        let mut service_nodes = Vec::new();
        for service in peripheral.services() {
            let mut char_nodes = Vec::new();
            for c in service.characteristics {
                let mut props_mask = 0i32;
                if c.properties.contains(CharPropFlags::READ) {
                    props_mask |= 0x02;
                }
                if c.properties.contains(CharPropFlags::WRITE_WITHOUT_RESPONSE) {
                    props_mask |= 0x04;
                }
                if c.properties.contains(CharPropFlags::WRITE) {
                    props_mask |= 0x08;
                }
                if c.properties.contains(CharPropFlags::NOTIFY) {
                    props_mask |= 0x10;
                }
                if c.properties.contains(CharPropFlags::INDICATE) {
                    props_mask |= 0x20;
                }

                char_nodes.push(CharNode {
                    uuid: c.uuid.to_string(),
                    properties: props_mask,
                    descriptors: vec![],
                });
            }

            service_nodes.push(ServiceNode {
                uuid: service.uuid.to_string(),
                characteristics: char_nodes,
            });
        }

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
        for service in peripheral.services() {
            if service.uuid.to_string().to_lowercase() == char_ref.service.to_lowercase() {
                for c in service.characteristics {
                    if c.uuid.to_string().to_lowercase() == char_ref.characteristic.to_lowercase() {
                        let bytes = peripheral.read(&c).await.map_err(|e| {
                            AgentError::new(ErrorKind::ReadFailed, Some(e.to_string()))
                        })?;
                        return Ok(ResultPayload::Bytes { value: bytes });
                    }
                }
            }
        }

        Err(AgentError::new(
            ErrorKind::CharacteristicNotFound,
            Some(format!(
                "Char {} not found in service {}",
                char_ref.characteristic, char_ref.service
            )),
        ))
    }

    async fn write(
        &self,
        device: &DeviceHandle,
        char_ref: &CharRef,
        value: &[u8],
        with_response: bool,
    ) -> Result<(), AgentError> {
        let peripheral = self.find_peripheral(device).await?;
        for service in peripheral.services() {
            if service.uuid.to_string().to_lowercase() == char_ref.service.to_lowercase() {
                for c in service.characteristics {
                    if c.uuid.to_string().to_lowercase() == char_ref.characteristic.to_lowercase() {
                        let write_type = if with_response {
                            btleplug::api::WriteType::WithResponse
                        } else {
                            btleplug::api::WriteType::WithoutResponse
                        };
                        peripheral.write(&c, value, write_type).await.map_err(|e| {
                            AgentError::new(ErrorKind::WriteFailed, Some(e.to_string()))
                        })?;
                        return Ok(());
                    }
                }
            }
        }

        Err(AgentError::new(
            ErrorKind::CharacteristicNotFound,
            Some(format!(
                "Char {} not found in service {}",
                char_ref.characteristic, char_ref.service
            )),
        ))
    }

    async fn request_mtu(
        &self,
        _device: &DeviceHandle,
        mtu: i32,
    ) -> Result<ResultPayload, AgentError> {
        Ok(ResultPayload::Mtu { mtu })
    }

    async fn start_observe(
        &self,
        sub_id: i64,
        device: &DeviceHandle,
        char_ref: &CharRef,
        event_tx: mpsc::UnboundedSender<AgentEvent>,
    ) -> Result<(), AgentError> {
        let peripheral = self.find_peripheral(device).await?;
        for service in peripheral.services() {
            if service.uuid.to_string().to_lowercase() == char_ref.service.to_lowercase() {
                for c in service.characteristics {
                    if c.uuid.to_string().to_lowercase() == char_ref.characteristic.to_lowercase() {
                        peripheral.subscribe(&c).await.map_err(|e| {
                            AgentError::new(ErrorKind::GattError, Some(e.to_string()))
                        })?;

                        let mut notifications = peripheral.notifications().await.map_err(|e| {
                            AgentError::new(ErrorKind::GattError, Some(e.to_string()))
                        })?;

                        let target_uuid = c.uuid;
                        tokio::spawn(async move {
                            while let Some(notification) = notifications.next().await {
                                if notification.uuid == target_uuid
                                    && event_tx
                                        .send(AgentEvent::Notification {
                                            sub_id,
                                            value: notification.value,
                                        })
                                        .is_err()
                                {
                                    break;
                                }
                            }
                        });

                        return Ok(());
                    }
                }
            }
        }

        Err(AgentError::new(
            ErrorKind::CharacteristicNotFound,
            Some(format!(
                "Char {} not found in service {}",
                char_ref.characteristic, char_ref.service
            )),
        ))
    }

    async fn stop_observe(&self, _sub_id: i64) -> Result<(), AgentError> {
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
    last_seen: &mut HashMap<String, (Option<String>, Vec<String>)>,
    handle: &str,
    name: Option<String>,
    service_uuids: Vec<String>,
) -> (Option<String>, Vec<String>) {
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

#[cfg(test)]
mod tests {
    use super::coalesce_identity;
    use std::collections::HashMap;

    #[test]
    fn retains_last_known_name_and_uuids_when_a_later_packet_omits_them() {
        let mut seen = HashMap::new();

        let first = coalesce_identity(
            &mut seen,
            "dev",
            Some("Heart Monitor".to_string()),
            vec!["180d".to_string()],
        );
        assert_eq!(first, (Some("Heart Monitor".to_string()), vec!["180d".to_string()]));

        // A bare refresh: no name, no service UUIDs -> identity is backfilled.
        let second = coalesce_identity(&mut seen, "dev", None, Vec::new());
        assert_eq!(second, (Some("Heart Monitor".to_string()), vec!["180d".to_string()]));
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
}
