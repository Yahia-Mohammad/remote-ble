use clap::ValueEnum;
use parking_lot::Mutex as ParkingMutex;
use std::collections::{HashMap, VecDeque};
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::{Duration, Instant};
use tokio::sync::{Mutex, Notify, mpsc};
use tokio::task::JoinHandle;

use crate::ble::backend::{BleBackend, COORDINATOR_SCAN_STREAM};
use crate::protocol::{
    errors::AgentError,
    events::{AdvertisementDto, AgentEvent},
    op::ScanFilter,
};

pub const REPLAY_CAP: usize = 256;
pub const MAILBOX_CAP: usize = 64;
pub const REPLAY_WINDOW: Duration = Duration::from_secs(30);

#[derive(Debug, Clone, Copy, Default, PartialEq, Eq, ValueEnum)]
pub enum ScanConcurrencyMode {
    #[default]
    Multiplexed,
    Single,
    Uncontrolled,
}

impl ScanConcurrencyMode {
    pub fn capability(self) -> &'static str {
        match self {
            Self::Multiplexed => crate::protocol::frame::capabilities::SCAN_CONCURRENCY_MULTIPLEXED,
            Self::Single => crate::protocol::frame::capabilities::SCAN_CONCURRENCY_SINGLE,
            Self::Uncontrolled => {
                crate::protocol::frame::capabilities::SCAN_CONCURRENCY_UNCONTROLLED
            }
        }
    }

    /// The mode's lowercased name, as `agent.status` reports it and `--scan-concurrency` accepts
    /// it. Matches the Kotlin agent's `ScanConcurrencyMode.name.lowercase()`.
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Multiplexed => "multiplexed",
            Self::Single => "single",
            Self::Uncontrolled => "uncontrolled",
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Hash)]
pub struct LogicalScanKey {
    pub client_key: String,
    pub scan_id: i64,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ScanRegistration {
    pub key: LogicalScanKey,
    pub generation: u64,
    /// Distinguishes same-key replacements issued by one live socket generation.
    pub revision: u64,
}

pub enum ScanAdmission {
    Accepted(ScanRegistration),
    SingleOccupied,
    LimitExceeded,
}

struct LogicalScan {
    registration: ScanRegistration,
    filters: Vec<ScanFilter>,
    delivery: Option<ScanDelivery>,
    grace: Option<JoinHandle<()>>,
}

struct CachedAdvertisement {
    advertisement: AdvertisementDto,
    observed: Instant,
}

#[derive(Default)]
struct State {
    scans: HashMap<LogicalScanKey, LogicalScan>,
    cache: HashMap<String, CachedAdvertisement>,
    plan: Option<Vec<ScanFilter>>,
    unfiltered_plan: bool,
    physical_generation: u64,
    registration_revision: u64,
    collector: Option<JoinHandle<()>>,
}

#[derive(Clone)]
pub struct ScanCoordinator {
    inner: Arc<Inner>,
}

struct Inner {
    backend: Arc<dyn BleBackend>,
    mode: ScanConcurrencyMode,
    transport_grace: Duration,
    max_active_scans: usize,
    state: Mutex<State>,
}

impl ScanCoordinator {
    pub fn new(
        backend: Arc<dyn BleBackend>,
        mode: ScanConcurrencyMode,
        transport_grace: Duration,
        max_active_scans: usize,
    ) -> Self {
        Self {
            inner: Arc::new(Inner {
                backend,
                mode,
                transport_grace,
                max_active_scans,
                state: Mutex::new(State::default()),
            }),
        }
    }

    pub async fn start_or_replace(
        &self,
        client_key: String,
        scan_id: i64,
        generation: u64,
        filters: Vec<ScanFilter>,
        delivery: ScanDelivery,
    ) -> Result<ScanAdmission, AgentError> {
        let key = LogicalScanKey {
            client_key,
            scan_id,
        };
        let mut state = self.inner.state.lock().await;
        let current = state.scans.contains_key(&key);
        if !current {
            if self.inner.mode == ScanConcurrencyMode::Single && !state.scans.is_empty() {
                return Ok(ScanAdmission::SingleOccupied);
            }
            if state
                .scans
                .keys()
                .filter(|other| other.client_key == key.client_key)
                .count()
                >= self.inner.max_active_scans
            {
                return Ok(ScanAdmission::LimitExceeded);
            }
        }

        let registration = ScanRegistration {
            key: key.clone(),
            generation,
            revision: state.registration_revision.wrapping_add(1),
        };
        state.registration_revision = registration.revision;
        let previous_unfiltered = state.unfiltered_plan;
        let mut previous = state.scans.get_mut(&key).map(|logical| {
            (
                logical.registration.clone(),
                logical.filters.clone(),
                logical.delivery.clone(),
                logical.grace.take(),
            )
        });
        if let Some(logical) = state.scans.get_mut(&key) {
            logical.registration = registration.clone();
            logical.filters = filters;
            // Keep live fan-out detached until the retained replay has been atomically admitted.
            // The state lock prevents a physical result from overtaking that replay.
            logical.delivery = None;
        } else {
            state.scans.insert(
                key.clone(),
                LogicalScan {
                    registration: registration.clone(),
                    filters,
                    delivery: None,
                    grace: None,
                },
            );
        }

        let plan = physical_plan(&mut state);
        let needs_replacement = state.plan.as_ref() != Some(&plan)
            || state.collector.as_ref().is_none_or(JoinHandle::is_finished);
        let replacement_result = if needs_replacement {
            Some(Self::replace_collector(&self.inner, &mut state, plan).await)
        } else {
            None
        };
        if let Some(Err(error)) = replacement_result {
            state.unfiltered_plan = previous_unfiltered;
            if let Some((registration, filters, delivery, grace)) = previous.take() {
                let logical = state.scans.get_mut(&key).expect("existing key retained");
                logical.registration = registration;
                logical.filters = filters;
                logical.delivery = delivery;
                logical.grace = grace;
            } else {
                state.scans.remove(&key);
            }
            return Err(error);
        }
        evict_expired(&mut state);
        let replay: Vec<_> = state
            .cache
            .values()
            .filter(|entry| matches_filters(&state.scans[&key].filters, &entry.advertisement))
            .map(|entry| entry.advertisement.clone())
            .collect();
        for advertisement in replay {
            if delivery.try_send(advertisement).is_err() {
                // A fresh mailbox has capacity for every retained entry. A failure can therefore
                // only mean its connection is retiring; restore the old logical delivery rather
                // than publishing a partial replacement.
                if let Some((registration, filters, previous_delivery, grace)) = previous.take() {
                    let logical = state.scans.get_mut(&key).expect("existing key retained");
                    logical.registration = registration;
                    logical.filters = filters;
                    logical.delivery = previous_delivery;
                    logical.grace = grace;
                } else {
                    remove_scan(&self.inner, &mut state, &key).await;
                }
                return Err(AgentError::new(
                    crate::protocol::errors::ErrorKind::GattError,
                    Some("scan delivery became unavailable during replay admission".into()),
                ));
            }
        }
        state
            .scans
            .get_mut(&key)
            .expect("accepted key retained")
            .delivery = Some(delivery);
        if let Some((_, _, _, Some(grace))) = previous {
            grace.abort();
        }
        Ok(ScanAdmission::Accepted(registration))
    }

    async fn replace_collector(
        inner: &Arc<Inner>,
        state: &mut State,
        plan: Vec<ScanFilter>,
    ) -> Result<(), AgentError> {
        // Commit the backend replacement before retiring the incumbent collector. A fallible
        // backend must not orphan already-admitted logical scans on a failed reconfiguration.
        // This reserved subscription receives raw advertisements; coordinator matching is the
        // authority and keeps its cache bounded.
        let (tx, mut rx) = mpsc::channel(512);
        inner
            .backend
            .start_scan(COORDINATOR_SCAN_STREAM, plan.clone(), tx)
            .await?;
        if let Some(task) = state.collector.take() {
            task.abort();
        }
        state.plan = Some(plan);
        let generation = state.physical_generation.wrapping_add(1);
        state.physical_generation = generation;
        let weak = Arc::downgrade(inner);
        state.collector = Some(tokio::spawn(async move {
            while let Some(event) = rx.recv().await {
                if let AgentEvent::ScanResult { advertisement, .. } = event
                    && let Some(inner) = weak.upgrade()
                {
                    ScanCoordinator::fan_out_inner(&inner, generation, advertisement).await;
                }
            }
        }));
        Ok(())
    }

    async fn fan_out_inner(inner: &Arc<Inner>, generation: u64, raw: AdvertisementDto) {
        let mut state = inner.state.lock().await;
        if state.physical_generation != generation {
            return;
        }
        let merged = merge_identity(&mut state, raw);
        state.cache.insert(
            merged.device.value.clone(),
            CachedAdvertisement {
                advertisement: merged.clone(),
                observed: Instant::now(),
            },
        );
        evict_expired(&mut state);
        while state.cache.len() > REPLAY_CAP {
            if let Some(oldest) = state
                .cache
                .iter()
                .min_by_key(|(_, entry)| entry.observed)
                .map(|(key, _)| key.clone())
            {
                state.cache.remove(&oldest);
            } else {
                break;
            }
        }
        for logical in state.scans.values() {
            if matches_filters(&logical.filters, &merged)
                && let Some(delivery) = &logical.delivery
            {
                let _ = delivery.try_send(merged.clone());
            }
        }
    }

    pub async fn stop(&self, registration: &ScanRegistration) {
        let mut state = self.inner.state.lock().await;
        if state
            .scans
            .get(&registration.key)
            .is_some_and(|logical| logical.registration == *registration)
        {
            remove_scan(&self.inner, &mut state, &registration.key).await;
        }
    }

    pub async fn detach_generation(&self, generation: u64) {
        let mut state = self.inner.state.lock().await;
        for logical in state
            .scans
            .values_mut()
            .filter(|logical| logical.registration.generation == generation)
        {
            logical.delivery = None;
            if let Some(task) = logical.grace.take() {
                task.abort();
            }
            let registration = logical.registration.clone();
            let this = self.clone();
            logical.grace = Some(tokio::spawn(async move {
                tokio::time::sleep(this.inner.transport_grace).await;
                this.expire(registration).await;
            }));
        }
    }

    async fn expire(&self, registration: ScanRegistration) {
        let mut state = self.inner.state.lock().await;
        if state
            .scans
            .get(&registration.key)
            .is_some_and(|logical| logical.registration == registration)
        {
            remove_scan(&self.inner, &mut state, &registration.key).await;
        }
    }
}

async fn remove_scan(inner: &Arc<Inner>, state: &mut State, key: &LogicalScanKey) {
    if let Some(mut logical) = state.scans.remove(key)
        && let Some(task) = logical.grace.take()
    {
        task.abort();
    }
    if state.scans.is_empty() {
        if let Some(task) = state.collector.take() {
            task.abort();
        }
        let _ = inner.backend.stop_scan(COORDINATOR_SCAN_STREAM).await;
        state.plan = None;
        state.unfiltered_plan = false;
        state.cache.clear();
    }
}

fn physical_plan(state: &mut State) -> Vec<ScanFilter> {
    if state.unfiltered_plan {
        return vec![];
    }
    let coverable = state.scans.values().all(|logical| {
        !logical.filters.is_empty()
            && logical
                .filters
                .iter()
                .all(|filter| filter.service.is_some())
    });
    if !coverable {
        state.unfiltered_plan = true;
        return vec![];
    }
    let mut services: Vec<String> = state
        .scans
        .values()
        .flat_map(|logical| {
            logical
                .filters
                .iter()
                .filter_map(|filter| filter.service.clone())
        })
        .map(|service| canonical_uuid(&service))
        .collect();
    if let Some(previous) = &state.plan {
        services.extend(previous.iter().filter_map(|filter| filter.service.clone()));
    }
    services.sort();
    services.dedup();
    services
        .into_iter()
        .map(|service| ScanFilter {
            name: None,
            service: Some(service),
        })
        .collect()
}

fn merge_identity(state: &mut State, raw: AdvertisementDto) -> AdvertisementDto {
    let previous = state
        .cache
        .get(&raw.device.value)
        .map(|entry| &entry.advertisement);
    AdvertisementDto {
        device: raw.device,
        name: raw
            .name
            .or_else(|| previous.and_then(|entry| entry.name.clone())),
        rssi: raw.rssi,
        service_uuids: if raw.service_uuids.is_empty() {
            previous
                .map(|entry| entry.service_uuids.clone())
                .unwrap_or_default()
        } else {
            raw.service_uuids
        },
        manufacturer_data: raw.manufacturer_data,
    }
}

fn evict_expired(state: &mut State) {
    state
        .cache
        .retain(|_, entry| entry.observed.elapsed() <= REPLAY_WINDOW);
}

fn matches_filters(filters: &[ScanFilter], advertisement: &AdvertisementDto) -> bool {
    filters.is_empty()
        || filters.iter().any(|filter| {
            filter
                .name
                .as_ref()
                .is_none_or(|name| advertisement.name.as_ref() == Some(name))
                && filter.service.as_ref().is_none_or(|service| {
                    advertisement
                        .service_uuids
                        .iter()
                        .any(|uuid| canonical_uuid(uuid) == canonical_uuid(service))
                })
        })
}

fn canonical_uuid(value: &str) -> String {
    let raw = value.to_ascii_lowercase();
    if raw.len() == 4 && raw.chars().all(|c| c.is_ascii_hexdigit()) {
        format!("0000{raw}-0000-1000-8000-00805f9b34fb")
    } else if raw.len() == 8 && raw.chars().all(|c| c.is_ascii_hexdigit()) {
        format!("{raw}-0000-1000-8000-00805f9b34fb")
    } else {
        raw
    }
}

#[derive(Clone)]
pub struct ScanDelivery {
    tx: mpsc::Sender<AdvertisementDto>,
    wake: Arc<Notify>,
}

impl ScanDelivery {
    fn try_send(
        &self,
        advertisement: AdvertisementDto,
    ) -> Result<(), mpsc::error::TrySendError<AdvertisementDto>> {
        let result = self.tx.try_send(advertisement);
        if result.is_ok() {
            self.wake.notify_one();
        }
        result
    }
}

struct ArbiterMailbox {
    scan_id: i64,
    rx: mpsc::Receiver<AdvertisementDto>,
    /// Held back until `scan.start`'s reply has been written; see [`ScanArbiterHandle::release`].
    ///
    /// Admission fills this mailbox with the replay cache synchronously, so without the gate the
    /// worker can put a `ScanResult` on the wire before the reply that accepts the scan.
    parked: bool,
}

#[derive(Default)]
struct ArbiterState {
    mailboxes: HashMap<u64, ArbiterMailbox>,
    order: VecDeque<u64>,
    next: u64,
}

#[derive(Clone)]
pub struct ScanArbiter {
    state: Arc<ParkingMutex<ArbiterState>>,
    wake: Arc<Notify>,
    closed: Arc<AtomicBool>,
}

pub struct ScanArbiterHandle {
    token: u64,
    state: Arc<ParkingMutex<ArbiterState>>,
    wake: Arc<Notify>,
}

impl ScanArbiter {
    pub fn new(event_tx: mpsc::Sender<AgentEvent>) -> Self {
        let state = Arc::new(ParkingMutex::new(ArbiterState {
            next: 1,
            ..ArbiterState::default()
        }));
        let wake = Arc::new(Notify::new());
        let closed = Arc::new(AtomicBool::new(false));
        let worker_state = state.clone();
        let worker_wake = wake.clone();
        let worker_closed = closed.clone();
        tokio::spawn(async move {
            loop {
                worker_wake.notified().await;
                if worker_closed.load(Ordering::Acquire) {
                    break;
                }
                loop {
                    let round = {
                        let mut state = worker_state.lock();
                        let mut events = Vec::new();
                        let rounds = state.order.len();
                        for _ in 0..rounds {
                            let Some(token) = state.order.pop_front() else {
                                break;
                            };
                            let Some(mailbox) = state.mailboxes.get_mut(&token) else {
                                continue;
                            };
                            // Not yet cleared for delivery: its `scan.start` reply has not been
                            // written. Keep its turn so ordering is preserved once released.
                            if mailbox.parked {
                                state.order.push_back(token);
                                continue;
                            }
                            match mailbox.rx.try_recv() {
                                Ok(advertisement) => {
                                    events.push(AgentEvent::ScanResult {
                                        scan_id: mailbox.scan_id,
                                        advertisement,
                                    });
                                    state.order.push_back(token);
                                }
                                Err(mpsc::error::TryRecvError::Empty) => {
                                    state.order.push_back(token)
                                }
                                Err(mpsc::error::TryRecvError::Disconnected) => {
                                    state.mailboxes.remove(&token);
                                }
                            }
                        }
                        events
                    };
                    if round.is_empty() {
                        break;
                    }
                    for event in round {
                        let _ = event_tx.try_send(event);
                    }
                    if worker_closed.load(Ordering::Acquire) {
                        break;
                    }
                }
            }
        });
        Self {
            state,
            wake,
            closed,
        }
    }

    /// Registers a mailbox that delivers as soon as the worker sees it.
    ///
    /// Test-only: the `scan.start` path always parks, because the wire contract requires the reply
    /// to precede delivery. Kept for the arbiter's own tests, which exercise fairness and
    /// backpressure and have no reply to order against.
    #[cfg(test)]
    pub fn register(&self, scan_id: i64) -> (ScanDelivery, ScanArbiterHandle) {
        self.register_with(scan_id, false)
    }

    /// Registers a mailbox held back until [`ScanArbiterHandle::release`].
    ///
    /// Used by the `scan.start` path so admission can fill the mailbox with the replay cache while
    /// nothing reaches the wire until the reply is queued. Opt-in rather than the default: the
    /// arbiter on its own has no notion of a reply, and a caller that never releases would simply
    /// never deliver.
    pub fn register_parked(&self, scan_id: i64) -> (ScanDelivery, ScanArbiterHandle) {
        self.register_with(scan_id, true)
    }

    fn register_with(&self, scan_id: i64, parked: bool) -> (ScanDelivery, ScanArbiterHandle) {
        // Sized for a full replay burst (up to REPLAY_CAP entries, sent synchronously at
        // admission before this mailbox is necessarily being drained) plus steady-state
        // headroom. Bounded either way; this only widens the constant so a late joiner's
        // retained-entry replay isn't silently truncated by try_send at admission time.
        let (tx, rx) = mpsc::channel(MAILBOX_CAP + REPLAY_CAP);
        let token = {
            let mut state = self.state.lock();
            let token = state.next;
            state.next = state.next.wrapping_add(1);
            state.mailboxes.insert(
                token,
                ArbiterMailbox {
                    scan_id,
                    rx,
                    parked,
                },
            );
            state.order.push_back(token);
            token
        };
        self.wake.notify_one();
        (
            ScanDelivery {
                tx,
                wake: self.wake.clone(),
            },
            ScanArbiterHandle {
                token,
                state: self.state.clone(),
                wake: self.wake.clone(),
            },
        )
    }

    pub fn close(&self) {
        self.closed.store(true, Ordering::Release);
        // One worker: notify_one retains a permit if it is between drain rounds.
        self.wake.notify_one();
    }
}

impl ScanArbiterHandle {
    /// Clears this scan for delivery, once its `scan.start` reply is on the wire.
    ///
    /// Admission fills the mailbox with the replay cache synchronously, so a mailbox that drained
    /// immediately could put a `ScanResult` ahead of the reply that accepts the scan. A client is
    /// then handed results for a stream it has not been told exists, and one that reads its reply
    /// before switching to event handling loses the first result outright. Parity with the Kotlin
    /// agent's `BleAgent.replyThenDeliver`.
    pub fn release(&self) {
        {
            let mut state = self.state.lock();
            if let Some(mailbox) = state.mailboxes.get_mut(&self.token) {
                mailbox.parked = false;
            }
        }
        self.wake.notify_one();
    }

    pub fn close(self) {
        let mut state = self.state.lock();
        state.mailboxes.remove(&self.token);
        state.order.retain(|token| *token != self.token);
        drop(state);
        self.wake.notify_one();
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::protocol::{
        op::{CharRef, DeviceHandle},
        results::ResultPayload,
    };
    use std::sync::atomic::{AtomicUsize, Ordering};

    #[derive(Default)]
    struct TestBackend {
        starts: AtomicUsize,
        fail_on_start: Option<usize>,
        collectors: ParkingMutex<Vec<mpsc::Sender<AgentEvent>>>,
    }

    #[async_trait::async_trait]
    impl BleBackend for TestBackend {
        fn capabilities(&self) -> Vec<String> {
            vec![]
        }

        async fn start_scan(
            &self,
            _stream: crate::ble::backend::StreamKey,
            _filters: Vec<ScanFilter>,
            tx: mpsc::Sender<AgentEvent>,
        ) -> Result<(), AgentError> {
            let start = self.starts.fetch_add(1, Ordering::Relaxed) + 1;
            if self.fail_on_start == Some(start) {
                return Err(AgentError::new(
                    crate::protocol::errors::ErrorKind::GattError,
                    None,
                ));
            }
            self.collectors.lock().push(tx);
            Ok(())
        }

        async fn stop_scan(
            &self,
            _stream: crate::ble::backend::StreamKey,
        ) -> Result<(), AgentError> {
            Ok(())
        }

        async fn stop_connection_streams(&self, _connection: u64) -> Result<(), AgentError> {
            Ok(())
        }

        async fn connect(
            &self,
            _device: &DeviceHandle,
            _tx: mpsc::Sender<AgentEvent>,
        ) -> Result<(), AgentError> {
            Ok(())
        }

        async fn disconnect(&self, _device: &DeviceHandle) -> Result<(), AgentError> {
            Ok(())
        }

        async fn discover(&self, _device: &DeviceHandle) -> Result<ResultPayload, AgentError> {
            Ok(ResultPayload::Services { services: vec![] })
        }

        async fn read(
            &self,
            _device: &DeviceHandle,
            _char_ref: &CharRef,
        ) -> Result<ResultPayload, AgentError> {
            Ok(ResultPayload::Bytes { value: vec![] })
        }

        async fn write(
            &self,
            _device: &DeviceHandle,
            _char_ref: &CharRef,
            _value: &[u8],
            _with_response: bool,
        ) -> Result<(), AgentError> {
            Ok(())
        }

        async fn request_mtu(
            &self,
            _device: &DeviceHandle,
            _mtu: i32,
        ) -> Result<ResultPayload, AgentError> {
            Ok(ResultPayload::Mtu { mtu: 23 })
        }

        async fn read_descriptor(
            &self,
            _device: &DeviceHandle,
            _desc_ref: &crate::protocol::op::DescRef,
        ) -> Result<ResultPayload, AgentError> {
            Ok(ResultPayload::Bytes { value: vec![] })
        }

        async fn write_descriptor(
            &self,
            _device: &DeviceHandle,
            _desc_ref: &crate::protocol::op::DescRef,
            _value: &[u8],
        ) -> Result<(), AgentError> {
            Ok(())
        }

        async fn start_observe(
            &self,
            _stream: crate::ble::backend::StreamKey,
            _device: &DeviceHandle,
            _char_ref: &CharRef,
            _tx: mpsc::Sender<AgentEvent>,
        ) -> Result<(), AgentError> {
            Ok(())
        }

        async fn stop_observe(
            &self,
            _stream: crate::ble::backend::StreamKey,
        ) -> Result<(), AgentError> {
            Ok(())
        }
    }

    fn advertisement(value: &str) -> AdvertisementDto {
        AdvertisementDto {
            device: DeviceHandle {
                value: value.into(),
            },
            name: None,
            rssi: -50,
            service_uuids: vec![],
            manufacturer_data: Default::default(),
        }
    }
    #[test]
    fn matcher_is_or_across_entries_and_and_inside_entry() {
        let ad = AdvertisementDto {
            device: crate::protocol::op::DeviceHandle { value: "a".into() },
            name: Some("HRM".into()),
            rssi: -55,
            service_uuids: vec!["180d".into()],
            manufacturer_data: Default::default(),
        };
        assert!(matches_filters(
            &[
                ScanFilter {
                    name: Some("no".into()),
                    service: Some("180f".into())
                },
                ScanFilter {
                    name: Some("HRM".into()),
                    service: Some("180d".into())
                }
            ],
            &ad
        ));
        assert!(!matches_filters(
            &[ScanFilter {
                name: Some("no".into()),
                service: Some("180d".into())
            }],
            &ad
        ));
    }

    #[tokio::test]
    async fn same_generation_replacement_has_a_unique_fence() {
        let backend: Arc<dyn BleBackend> = Arc::new(TestBackend::default());
        let coordinator = ScanCoordinator::new(
            backend,
            ScanConcurrencyMode::Single,
            Duration::from_secs(10),
            4,
        );
        let (events, _) = mpsc::channel(8);
        let arbiter = ScanArbiter::new(events);
        let (first_delivery, first_handle) = arbiter.register(1);
        let first = match coordinator
            .start_or_replace("a".into(), 1, 7, vec![], first_delivery)
            .await
            .unwrap()
        {
            ScanAdmission::Accepted(registration) => registration,
            _ => panic!("first scan must be admitted"),
        };
        let (replacement_delivery, replacement_handle) = arbiter.register(1);
        let replacement = match coordinator
            .start_or_replace("a".into(), 1, 7, vec![], replacement_delivery)
            .await
            .unwrap()
        {
            ScanAdmission::Accepted(registration) => registration,
            _ => panic!("same key must replace"),
        };
        assert_ne!(first, replacement);
        coordinator.stop(&first).await;
        let (other_delivery, other_handle) = arbiter.register(1);
        assert!(matches!(
            coordinator
                .start_or_replace("b".into(), 1, 8, vec![], other_delivery)
                .await
                .unwrap(),
            ScanAdmission::SingleOccupied
        ));
        coordinator.stop(&replacement).await;
        first_handle.close();
        replacement_handle.close();
        other_handle.close();
    }

    #[tokio::test]
    async fn multiplexed_scans_share_one_physical_collector() {
        let backend = Arc::new(TestBackend::default());
        let coordinator = ScanCoordinator::new(
            backend.clone(),
            ScanConcurrencyMode::Multiplexed,
            Duration::from_secs(10),
            4,
        );
        let (events, _) = mpsc::channel(8);
        let arbiter = ScanArbiter::new(events);
        let (first, first_handle) = arbiter.register(1);
        coordinator
            .start_or_replace("a".into(), 1, 1, vec![], first)
            .await
            .unwrap();
        let (second, second_handle) = arbiter.register(2);
        coordinator
            .start_or_replace("b".into(), 2, 2, vec![], second)
            .await
            .unwrap();
        assert_eq!(backend.starts.load(Ordering::Relaxed), 1);
        first_handle.close();
        second_handle.close();
    }

    #[tokio::test]
    async fn a_parked_mailbox_delivers_nothing_until_it_is_released() {
        // The wire guarantee's Rust half: `scan.start` fills this mailbox with the replay cache at
        // admission, and nothing may reach the transport until the reply is queued. Parity with the
        // Kotlin agent's `BleAgent.replyThenDeliver`.
        let (events, mut event_rx) = mpsc::channel(8);
        let arbiter = ScanArbiter::new(events);
        let (delivery, handle) = arbiter.register_parked(1);

        delivery.try_send(advertisement("replayed")).unwrap();
        // Give the worker every chance to drain it; a parked mailbox must decline its turn.
        tokio::task::yield_now().await;
        tokio::time::sleep(Duration::from_millis(50)).await;
        assert!(
            event_rx.try_recv().is_err(),
            "a parked mailbox must not deliver before its scan.start reply is written",
        );

        handle.release();
        let delivered = tokio::time::timeout(Duration::from_secs(2), event_rx.recv())
            .await
            .expect("release must let the retained entry through")
            .expect("event channel closed");
        match delivered {
            AgentEvent::ScanResult { advertisement, .. } => {
                assert_eq!(advertisement.device.value, "replayed");
            }
            other => panic!("expected the replayed scan result, got {other:?}"),
        }
        handle.close();
    }

    #[tokio::test]
    async fn failed_reconfiguration_keeps_the_incumbent_collector() {
        let backend = Arc::new(TestBackend {
            fail_on_start: Some(2),
            ..TestBackend::default()
        });
        let coordinator = ScanCoordinator::new(
            backend,
            ScanConcurrencyMode::Multiplexed,
            Duration::from_secs(10),
            4,
        );
        let (events, _) = mpsc::channel(8);
        let arbiter = ScanArbiter::new(events);
        let (first_delivery, first_handle) = arbiter.register(1);
        coordinator
            .start_or_replace(
                "a".into(),
                1,
                1,
                vec![ScanFilter {
                    name: None,
                    service: Some("180d".into()),
                }],
                first_delivery,
            )
            .await
            .unwrap();
        let (second_delivery, second_handle) = arbiter.register(2);
        assert!(
            coordinator
                .start_or_replace(
                    "b".into(),
                    2,
                    2,
                    vec![ScanFilter {
                        name: None,
                        service: Some("180f".into()),
                    }],
                    second_delivery,
                )
                .await
                .is_err()
        );
        let state = coordinator.inner.state.lock().await;
        assert_eq!(state.scans.len(), 1);
        assert!(
            state
                .collector
                .as_ref()
                .is_some_and(|collector| !collector.is_finished())
        );
        drop(state);
        first_handle.close();
        second_handle.close();
    }

    #[tokio::test]
    async fn arbiter_is_fair_and_removed_tokens_cannot_reappear() {
        let (event_tx, mut event_rx) = mpsc::channel(16);
        let arbiter = ScanArbiter::new(event_tx);
        let (first, first_handle) = arbiter.register(1);
        let (second, second_handle) = arbiter.register(2);
        for value in ["a", "b"] {
            first.try_send(advertisement(value)).unwrap();
        }
        for value in ["c", "d"] {
            second.try_send(advertisement(value)).unwrap();
        }
        let mut ids = Vec::new();
        for _ in 0..4 {
            let AgentEvent::ScanResult { scan_id, .. } = event_rx.recv().await.unwrap() else {
                panic!("arbiter must emit scan results");
            };
            ids.push(scan_id);
        }
        assert_eq!(ids, vec![1, 2, 1, 2]);

        first.try_send(advertisement("stale")).unwrap();
        first_handle.close();
        assert!(
            tokio::time::timeout(Duration::from_millis(20), event_rx.recv())
                .await
                .is_err()
        );
        second_handle.close();
    }

    #[tokio::test]
    async fn late_join_after_replay_window_expiry_does_not_receive_stale_cache_entries() {
        let backend: Arc<dyn BleBackend> = Arc::new(TestBackend::default());
        let coordinator = ScanCoordinator::new(
            backend,
            ScanConcurrencyMode::Multiplexed,
            Duration::from_secs(10),
            4,
        );
        {
            let mut state = coordinator.inner.state.lock().await;
            let stale = advertisement("stale");
            state.cache.insert(
                stale.device.value.clone(),
                CachedAdvertisement {
                    advertisement: stale,
                    observed: Instant::now() - REPLAY_WINDOW - Duration::from_secs(1),
                },
            );
        }
        let (events, mut event_rx) = mpsc::channel(8);
        let arbiter = ScanArbiter::new(events);
        let (delivery, handle) = arbiter.register(1);
        assert!(matches!(
            coordinator
                .start_or_replace("late".into(), 1, 1, vec![], delivery)
                .await
                .unwrap(),
            ScanAdmission::Accepted(_)
        ));
        assert!(
            tokio::time::timeout(Duration::from_millis(50), event_rx.recv())
                .await
                .is_err()
        );
        handle.close();
        arbiter.close();
    }

    #[tokio::test]
    async fn replay_cache_evicts_the_oldest_device_at_capacity() {
        let backend: Arc<dyn BleBackend> = Arc::new(TestBackend::default());
        let coordinator = ScanCoordinator::new(
            backend,
            ScanConcurrencyMode::Multiplexed,
            Duration::from_secs(10),
            4,
        );
        let (events, _) = mpsc::channel(8);
        let arbiter = ScanArbiter::new(events);
        let (delivery, handle) = arbiter.register(1);
        let registration = match coordinator
            .start_or_replace("incumbent".into(), 1, 1, vec![], delivery)
            .await
            .unwrap()
        {
            ScanAdmission::Accepted(registration) => registration,
            _ => panic!("incumbent scan must be admitted"),
        };
        let generation = coordinator.inner.state.lock().await.physical_generation;
        for index in 0..=REPLAY_CAP {
            ScanCoordinator::fan_out_inner(
                &coordinator.inner,
                generation,
                advertisement(&format!("device-{index}")),
            )
            .await;
        }
        let state = coordinator.inner.state.lock().await;
        assert_eq!(state.cache.len(), REPLAY_CAP);
        assert!(!state.cache.contains_key("device-0"));
        assert!(state.cache.contains_key(&format!("device-{REPLAY_CAP}")));
        drop(state);
        coordinator.stop(&registration).await;
        handle.close();
        arbiter.close();
    }

    #[tokio::test]
    async fn late_join_receives_every_retained_entry_through_the_bounded_channel() {
        // Delivery, not just cache state: a late joiner's replay goes through the same bounded
        // per-scan channel as steady-state traffic (ScanArbiter::register's MAILBOX_CAP), so this
        // exercises the real channel capacity rather than asserting coordinator-internal state.
        let backend: Arc<dyn BleBackend> = Arc::new(TestBackend::default());
        let coordinator = ScanCoordinator::new(
            backend,
            ScanConcurrencyMode::Multiplexed,
            Duration::from_secs(10),
            4,
        );
        let (events, _) = mpsc::channel(8);
        let arbiter = ScanArbiter::new(events);
        let (incumbent_delivery, incumbent_handle) = arbiter.register(1);
        let incumbent = match coordinator
            .start_or_replace("incumbent".into(), 1, 1, vec![], incumbent_delivery)
            .await
            .unwrap()
        {
            ScanAdmission::Accepted(registration) => registration,
            _ => panic!("incumbent scan must be admitted"),
        };
        let generation = coordinator.inner.state.lock().await.physical_generation;
        for index in 0..REPLAY_CAP {
            ScanCoordinator::fan_out_inner(
                &coordinator.inner,
                generation,
                advertisement(&format!("device-{index}")),
            )
            .await;
        }

        let (late_events, mut late_rx) = mpsc::channel(REPLAY_CAP + 16);
        let late_arbiter = ScanArbiter::new(late_events);
        let (late_delivery, late_handle) = late_arbiter.register(2);
        assert!(matches!(
            coordinator
                .start_or_replace("late".into(), 1, 2, vec![], late_delivery)
                .await
                .unwrap(),
            ScanAdmission::Accepted(_)
        ));
        let mut replayed = 0usize;
        while let Ok(Some(_)) =
            tokio::time::timeout(Duration::from_millis(200), late_rx.recv()).await
        {
            replayed += 1;
        }
        assert_eq!(
            replayed, REPLAY_CAP,
            "late joiner must receive every retained matching entry, not just as many as fit \
             ahead of steady-state headroom"
        );

        coordinator.stop(&incumbent).await;
        incumbent_handle.close();
        late_handle.close();
        arbiter.close();
        late_arbiter.close();
    }

    #[tokio::test]
    async fn replay_is_admitted_before_a_newer_live_observation() {
        let backend: Arc<dyn BleBackend> = Arc::new(TestBackend::default());
        let coordinator = ScanCoordinator::new(
            backend,
            ScanConcurrencyMode::Multiplexed,
            Duration::from_secs(10),
            4,
        );
        let (events, _) = mpsc::channel(8);
        let arbiter = ScanArbiter::new(events);
        let (incumbent_delivery, incumbent_handle) = arbiter.register(1);
        let incumbent = match coordinator
            .start_or_replace("incumbent".into(), 1, 1, vec![], incumbent_delivery)
            .await
            .unwrap()
        {
            ScanAdmission::Accepted(registration) => registration,
            _ => panic!("incumbent scan must be admitted"),
        };
        let generation = coordinator.inner.state.lock().await.physical_generation;
        let mut retained = advertisement("device");
        retained.rssi = -60;
        ScanCoordinator::fan_out_inner(&coordinator.inner, generation, retained).await;

        let (late_events, mut late_rx) = mpsc::channel(8);
        let late_arbiter = ScanArbiter::new(late_events);
        let (late_delivery, late_handle) = late_arbiter.register(2);
        assert!(matches!(
            coordinator
                .start_or_replace("late".into(), 1, 2, vec![], late_delivery)
                .await
                .unwrap(),
            ScanAdmission::Accepted(_)
        ));
        let mut live = advertisement("device");
        live.rssi = -40;
        ScanCoordinator::fan_out_inner(&coordinator.inner, generation, live).await;

        let first = late_rx.recv().await.expect("retained replay must arrive");
        let second = late_rx
            .recv()
            .await
            .expect("live observation must follow replay");
        let AgentEvent::ScanResult {
            advertisement: first,
            ..
        } = first
        else {
            panic!("expected scan result")
        };
        let AgentEvent::ScanResult {
            advertisement: second,
            ..
        } = second
        else {
            panic!("expected scan result")
        };
        assert_eq!(first.rssi, -60);
        assert_eq!(second.rssi, -40);

        coordinator.stop(&incumbent).await;
        incumbent_handle.close();
        late_handle.close();
        arbiter.close();
        late_arbiter.close();
    }

    #[tokio::test]
    async fn stable_client_scan_cap_survives_transport_grace_and_allows_only_rebind() {
        let backend: Arc<dyn BleBackend> = Arc::new(TestBackend::default());
        let coordinator = ScanCoordinator::new(
            backend,
            ScanConcurrencyMode::Multiplexed,
            Duration::from_secs(10),
            1,
        );
        let (events, _) = mpsc::channel(8);
        let arbiter = ScanArbiter::new(events);
        let (first_delivery, first_handle) = arbiter.register(1);
        let first = match coordinator
            .start_or_replace("stable".into(), 1, 1, vec![], first_delivery)
            .await
            .unwrap()
        {
            ScanAdmission::Accepted(registration) => registration,
            _ => panic!("first scan must be admitted"),
        };
        coordinator.detach_generation(1).await;

        let (rebind_delivery, rebind_handle) = arbiter.register(1);
        assert!(matches!(
            coordinator
                .start_or_replace("stable".into(), 1, 2, vec![], rebind_delivery)
                .await
                .unwrap(),
            ScanAdmission::Accepted(_)
        ));
        let (new_key_delivery, new_key_handle) = arbiter.register(2);
        assert!(matches!(
            coordinator
                .start_or_replace("stable".into(), 2, 2, vec![], new_key_delivery)
                .await
                .unwrap(),
            ScanAdmission::LimitExceeded
        ));
        coordinator.stop(&first).await; // stale registration must not remove the rebind
        first_handle.close();
        rebind_handle.close();
        new_key_handle.close();
        arbiter.close();
    }

    #[tokio::test]
    async fn single_admission_is_linearizable_for_concurrent_different_keys() {
        let backend: Arc<dyn BleBackend> = Arc::new(TestBackend::default());
        let coordinator = ScanCoordinator::new(
            backend,
            ScanConcurrencyMode::Single,
            Duration::from_secs(10),
            4,
        );
        let (events, _) = mpsc::channel(8);
        let arbiter = ScanArbiter::new(events);
        let (first_delivery, first_handle) = arbiter.register(1);
        let (second_delivery, second_handle) = arbiter.register(2);
        let (first, second) = tokio::join!(
            coordinator.start_or_replace("a".into(), 1, 1, vec![], first_delivery),
            coordinator.start_or_replace("b".into(), 1, 2, vec![], second_delivery),
        );
        let outcomes = [first.unwrap(), second.unwrap()];
        assert_eq!(
            outcomes
                .iter()
                .filter(|outcome| matches!(outcome, ScanAdmission::Accepted(_)))
                .count(),
            1
        );
        assert_eq!(
            outcomes
                .iter()
                .filter(|outcome| matches!(outcome, ScanAdmission::SingleOccupied))
                .count(),
            1
        );
        first_handle.close();
        second_handle.close();
        arbiter.close();
    }
}
