use clap::ValueEnum;
use std::collections::{HashMap, VecDeque};
use std::sync::Arc;
use std::time::{Duration, Instant};
use tokio::sync::{Mutex, mpsc};
use tokio::task::JoinHandle;

use crate::ble::backend::{BleBackend, StreamKey};
use crate::protocol::{
    errors::AgentError,
    events::{AdvertisementDto, AgentEvent},
    op::ScanFilter,
};

pub const REPLAY_CAP: usize = 256;
pub const MAILBOX_CAP: usize = 64;
pub const REPLAY_WINDOW: Duration = Duration::from_secs(30);
const PHYSICAL_STREAM: StreamKey = StreamKey {
    connection: 0,
    local_id: i64::MIN,
};

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
}

pub enum ScanAdmission {
    Accepted(ScanRegistration),
    SingleOccupied,
    LimitExceeded,
}

struct LogicalScan {
    registration: ScanRegistration,
    filters: Vec<ScanFilter>,
    delivery: Option<mpsc::Sender<AdvertisementDto>>,
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
        delivery: mpsc::Sender<AdvertisementDto>,
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
        };
        let previous_unfiltered = state.unfiltered_plan;
        let previous = state.scans.get_mut(&key).map(|logical| {
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
            logical.delivery = Some(delivery.clone());
        } else {
            state.scans.insert(
                key.clone(),
                LogicalScan {
                    registration: registration.clone(),
                    filters,
                    delivery: Some(delivery.clone()),
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
            if let Some((registration, filters, delivery, grace)) = previous {
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
        if let Some((_, _, _, Some(grace))) = previous {
            grace.abort();
        }
        evict_expired(&mut state);
        let replay: Vec<_> = state
            .cache
            .values()
            .filter(|entry| matches_filters(&state.scans[&key].filters, &entry.advertisement))
            .map(|entry| entry.advertisement.clone())
            .collect();
        drop(state);
        for advertisement in replay {
            let _ = delivery.try_send(advertisement);
        }
        Ok(ScanAdmission::Accepted(registration))
    }

    async fn replace_collector(
        inner: &Arc<Inner>,
        state: &mut State,
        plan: Vec<ScanFilter>,
    ) -> Result<(), AgentError> {
        if let Some(task) = state.collector.take() {
            task.abort();
        }
        // The existing backend already owns exactly one adapter scan and fan-outs subscriptions.
        // This internal subscription is the coordinator's sole physical collector; its filters are
        // only a native prefilter, never the authority for logical matching.
        let (tx, mut rx) = mpsc::channel(512);
        inner
            .backend
            .start_scan(PHYSICAL_STREAM, plan.clone(), tx)
            .await?;
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
        let _ = inner.backend.stop_scan(PHYSICAL_STREAM).await;
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

enum ArbiterInput {
    Event(u64, AgentEvent),
    Remove(u64),
}

#[derive(Clone)]
pub struct ScanArbiter {
    input: mpsc::Sender<ArbiterInput>,
    next: Arc<std::sync::atomic::AtomicU64>,
}
pub struct ScanArbiterHandle {
    token: u64,
    input: mpsc::Sender<ArbiterInput>,
}

impl ScanArbiter {
    pub fn new(event_tx: mpsc::Sender<AgentEvent>) -> Self {
        let (input, mut rx) = mpsc::channel::<ArbiterInput>(512);
        tokio::spawn(async move {
            let mut queues: HashMap<u64, VecDeque<AgentEvent>> = HashMap::new();
            let mut order: Vec<u64> = Vec::new();
            let mut cursor = 0usize;
            while let Some(input) = rx.recv().await {
                match input {
                    ArbiterInput::Event(token, event) => {
                        if let std::collections::hash_map::Entry::Vacant(entry) =
                            queues.entry(token)
                        {
                            entry.insert(VecDeque::new());
                            order.push(token);
                        }
                        queues.get_mut(&token).expect("inserted").push_back(event);
                    }
                    ArbiterInput::Remove(token) => {
                        queues.remove(&token);
                        order.retain(|item| *item != token);
                        if cursor >= order.len() {
                            cursor = 0;
                        }
                    }
                }
                let rounds = order.len();
                for _ in 0..rounds {
                    if order.is_empty() {
                        break;
                    }
                    if cursor >= order.len() {
                        cursor = 0;
                    }
                    let token = order[cursor];
                    cursor = (cursor + 1) % order.len();
                    if let Some(event) = queues.get_mut(&token).and_then(VecDeque::pop_front) {
                        let _ = event_tx.try_send(event);
                    }
                }
            }
        });
        Self {
            input,
            next: Arc::new(std::sync::atomic::AtomicU64::new(1)),
        }
    }

    pub fn register(&self, scan_id: i64) -> (mpsc::Sender<AdvertisementDto>, ScanArbiterHandle) {
        let token = self.next.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
        let (tx, mut rx) = mpsc::channel(MAILBOX_CAP);
        let input = self.input.clone();
        tokio::spawn(async move {
            while let Some(advertisement) = rx.recv().await {
                if input
                    .send(ArbiterInput::Event(
                        token,
                        AgentEvent::ScanResult {
                            scan_id,
                            advertisement,
                        },
                    ))
                    .await
                    .is_err()
                {
                    break;
                }
            }
        });
        (
            tx,
            ScanArbiterHandle {
                token,
                input: self.input.clone(),
            },
        )
    }
}

impl ScanArbiterHandle {
    pub async fn close(self) {
        let _ = self.input.send(ArbiterInput::Remove(self.token)).await;
    }
}

#[cfg(test)]
mod tests {
    use super::*;
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
}
