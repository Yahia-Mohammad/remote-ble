use crate::protocol::errors::{AgentError, ErrorKind};
use crate::registry::lease_disclosure;
use parking_lot::Mutex;
use std::collections::HashMap;
use std::future::Future;
use std::pin::Pin;
use std::sync::{Arc, OnceLock};
use std::time::Duration;
use tokio::task::JoinHandle;

#[derive(Debug, Clone)]
pub struct LeaseConfig {
    pub default_exclusive: bool,
    pub lease_grace: Duration,
    pub transport_grace: Duration,
    pub max_slots: usize,
}

impl Default for LeaseConfig {
    fn default() -> Self {
        Self {
            default_exclusive: true,
            lease_grace: Duration::from_secs(10),
            // See the `--transport-grace-ms` flag: long enough that a process-per-command client
            // resumes its warm link on the next command.
            transport_grace: Duration::from_secs(120),
            max_slots: 8,
        }
    }
}

/// Tears the warm radio link down when a lease's grace window expires. Wired by the
/// composition root to `BleBackend::disconnect`; left unset in tests that only assert
/// ownership bookkeeping.
type TeardownFn = Box<dyn Fn(String) -> Pin<Box<dyn Future<Output = ()> + Send>> + Send + Sync>;

struct PeripheralState {
    owner_client_id: String,
    connected: bool,
    /// Pending release timer, if the owner is (temporarily) gone. `None` when the
    /// owner is present.
    grace_task: Option<JoinHandle<()>>,
    /// Bumped whenever the owner returns (re-acquire/connect) or the lease is dropped.
    /// A fired grace timer only releases if the epoch still matches the one it captured,
    /// so a resume that raced the timer wins without needing the abort to land first.
    epoch: u64,
}

impl PeripheralState {
    /// The owner is back (or the lease is going away): cancel any pending release and
    /// invalidate an already-elapsed timer that is merely waiting on the lock.
    fn cancel_grace(&mut self) {
        if let Some(task) = self.grace_task.take() {
            task.abort();
        }
        self.epoch = self.epoch.wrapping_add(1);
    }
}

struct Inner {
    config: LeaseConfig,
    state: Mutex<HashMap<String, PeripheralState>>,
    teardown: OnceLock<TeardownFn>,
}

/// Authoritative, cross-client ownership of peripherals, shared (cheap `Arc` clone) by every
/// connection. Mirrors the Kotlin agent's `PeripheralRegistry`: anything that means "the owner
/// is (temporarily) gone" schedules a per-lease release timer; anything that means "the owner is
/// back" cancels it. On expiry the lease is freed and [Inner::teardown] disconnects the warm link.
#[derive(Clone)]
pub struct PeripheralRegistry {
    inner: Arc<Inner>,
}

impl PeripheralRegistry {
    pub fn new(config: LeaseConfig) -> Self {
        assert!(
            config.default_exclusive,
            "shared peripheral mode is unavailable in RemoteBLE 0.9.0"
        );
        Self {
            inner: Arc::new(Inner {
                config,
                state: Mutex::new(HashMap::new()),
                teardown: OnceLock::new(),
            }),
        }
    }

    /// Install the warm-link teardown invoked when a grace window expires. Set once, after the
    /// backend exists (the backend and registry reference each other). A no-op if already set.
    pub fn set_teardown<F, Fut>(&self, f: F)
    where
        F: Fn(String) -> Fut + Send + Sync + 'static,
        Fut: Future<Output = ()> + Send + 'static,
    {
        let boxed: TeardownFn = Box::new(move |handle| Box::pin(f(handle)));
        let _ = self.inner.teardown.set(boxed);
    }

    pub fn acquire_lease(&self, device_handle: &str, client_id: &str) -> Result<(), AgentError> {
        let mut map = self.inner.state.lock();

        if let Some(state) = map.get_mut(device_handle) {
            // Re-acquire by the owner resumes the lease (this is how a reconnecting client
            // keeps its peripheral): cancel any pending release and proceed.
            if state.owner_client_id == client_id {
                state.cancel_grace();
                return Ok(());
            }
            // A different client. The 0.9.0 surface is exclusive-only; a participant-based
            // shared model is deferred rather than granting an untracked guest. The holder is
            // named through the disclosure policy, never by interpolating the raw session key —
            // half of that key is text the holder chose, and it belongs to another tenant.
            return Err(AgentError::new(
                ErrorKind::PeripheralBusy,
                Some(lease_disclosure::busy_message(
                    &state.owner_client_id,
                    client_id,
                )),
            ));
        }

        if map.len() >= self.inner.config.max_slots {
            return Err(AgentError::new(
                ErrorKind::NoConnectionSlot,
                Some("Agent connection slot limit reached".into()),
            ));
        }

        map.insert(
            device_handle.to_string(),
            PeripheralState {
                owner_client_id: client_id.to_string(),
                connected: false,
                grace_task: None,
                epoch: 0,
            },
        );

        Ok(())
    }

    /// Authorize a device-bearing operation that requires an active BLE connection.
    ///
    /// Handles are observable routing values, not credentials. A different client must not be
    /// able to operate a peripheral merely because it learned its scanned handle. This also
    /// rejects the legacy shared-mode guest path until a real participant model exists.
    pub fn authorize_connected(
        &self,
        device_handle: &str,
        client_id: &str,
    ) -> Result<(), AgentError> {
        let map = self.inner.state.lock();
        match map.get(device_handle) {
            None => Err(AgentError::new(
                ErrorKind::NotConnected,
                Some("Peripheral is not connected".into()),
            )),
            Some(state) if state.owner_client_id != client_id => Err(AgentError::new(
                ErrorKind::PeripheralBusy,
                Some(lease_disclosure::busy_message(
                    &state.owner_client_id,
                    client_id,
                )),
            )),
            Some(state) if !state.connected => Err(AgentError::new(
                ErrorKind::NotConnected,
                Some("Peripheral is not connected".into()),
            )),
            Some(_) => Ok(()),
        }
    }

    /// Marks the peripheral physically connected under [client_id] and cancels any pending
    /// release (the owner is present).
    pub fn on_connected(&self, device_handle: &str, client_id: &str) {
        let mut map = self.inner.state.lock();
        if let Some(state) = map.get_mut(device_handle)
            && state.owner_client_id == client_id
        {
            state.connected = true;
            state.cancel_grace();
        }
    }

    pub fn release_lease(&self, device_handle: &str, client_id: &str) -> bool {
        let mut map = self.inner.state.lock();
        if let Some(state) = map.get_mut(device_handle)
            && state.owner_client_id == client_id
        {
            state.cancel_grace();
            map.remove(device_handle);
            return true;
        }
        false
    }

    /// The real handles of every lease `client_id` currently holds — live or in a grace window.
    /// A reconnecting client's fresh connection uses this to re-seed its handle translations
    /// (see `transport::negotiation::Negotiation::on_hello`): the warm leases are exactly the
    /// handles whose translated forms the client may replay on reconcile.
    pub fn held_by(&self, client_id: &str) -> Vec<String> {
        self.inner
            .state
            .lock()
            .iter()
            .filter(|(_, s)| s.owner_client_id == client_id)
            .map(|(h, _)| h.clone())
            .collect()
    }

    /// The client's transport (WebSocket) dropped. Keep its peripherals' radio links **warm** and
    /// schedule each for release after [LeaseConfig::transport_grace]; a reconnect within the
    /// window resumes (re-acquire cancels the timer), otherwise the link is torn down.
    pub fn on_transport_drop(&self, client_id: &str) {
        let mut map = self.inner.state.lock();
        let handles: Vec<String> = map
            .iter()
            .filter(|(_, s)| s.owner_client_id == client_id)
            .map(|(h, _)| h.clone())
            .collect();
        let grace = self.inner.config.transport_grace;
        for handle in handles {
            self.schedule_release(&mut map, &handle, grace);
        }
    }

    /// The radio link dropped (explicit or unsolicited). Schedule release after
    /// [LeaseConfig::lease_grace]; a quick reconnect by the owner keeps the lease.
    pub fn on_ble_disconnected(&self, device_handle: &str) {
        let mut map = self.inner.state.lock();
        if let Some(state) = map.get_mut(device_handle) {
            state.connected = false;
        }
        let grace = self.inner.config.lease_grace;
        self.schedule_release(&mut map, device_handle, grace);
    }

    /// Caller holds the state lock. Schedules a one-shot release for [device_handle] after
    /// [after] unless one is already pending. On fire, the lease is freed (if not resumed
    /// meanwhile) and the teardown callback disconnects the warm link.
    fn schedule_release(
        &self,
        map: &mut HashMap<String, PeripheralState>,
        device_handle: &str,
        after: Duration,
    ) {
        let state = match map.get_mut(device_handle) {
            Some(s) => s,
            None => return,
        };
        if state.grace_task.as_ref().is_some_and(|t| !t.is_finished()) {
            return; // a release is already pending
        }
        let epoch = state.epoch;
        let reg = self.clone();
        let handle_owned = device_handle.to_string();
        let task = tokio::spawn(async move {
            tokio::time::sleep(after).await;
            let released = {
                let mut map = reg.inner.state.lock();
                match map.get(&handle_owned) {
                    // Only release if the owner never returned (epoch unchanged).
                    Some(s) if s.epoch == epoch => {
                        map.remove(&handle_owned);
                        true
                    }
                    _ => false,
                }
            };
            if released && let Some(teardown) = reg.inner.teardown.get() {
                teardown(handle_owned).await; // best-effort warm-link teardown
            }
        });
        state.grace_task = Some(task);
    }

    /// Free/total connection slots. Intended to feed `AgentEvent::SlotState`
    /// once the `slots` capability is advertised; exercised by the unit tests.
    #[allow(dead_code)]
    pub fn free_slots(&self) -> usize {
        let map = self.inner.state.lock();
        self.inner.config.max_slots.saturating_sub(map.len())
    }

    #[allow(dead_code)]
    pub fn total_slots(&self) -> usize {
        self.inner.config.max_slots
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::{AtomicUsize, Ordering};

    #[test]
    fn held_by_lists_only_that_clients_leases() {
        let reg = PeripheralRegistry::new(LeaseConfig::default());
        assert!(reg.acquire_lease("dev1", "clientA").is_ok());
        reg.on_connected("dev1", "clientA");
        assert!(reg.acquire_lease("dev2", "clientB").is_ok());

        let mut held = reg.held_by("clientA");
        held.sort();
        assert_eq!(held, vec!["dev1".to_string()]);
        assert!(reg.held_by("nobody").is_empty());
    }

    #[test]
    fn test_acquire_and_exclusive_conflict() {
        let reg = PeripheralRegistry::new(LeaseConfig::default());
        assert!(reg.acquire_lease("dev1", "clientA").is_ok());

        // Same client re-acquiring is idempotent ok
        assert!(reg.acquire_lease("dev1", "clientA").is_ok());

        // Different client rejected with PeripheralBusy
        let err = reg.acquire_lease("dev1", "clientB").unwrap_err();
        assert_eq!(err.kind, ErrorKind::PeripheralBusy);
    }

    #[test]
    fn authorization_requires_the_owning_client_and_a_live_connection() {
        let reg = PeripheralRegistry::new(LeaseConfig::default());

        assert_eq!(
            reg.authorize_connected("dev1", "clientA").unwrap_err().kind,
            ErrorKind::NotConnected
        );
        reg.acquire_lease("dev1", "clientA").unwrap();
        assert_eq!(
            reg.authorize_connected("dev1", "clientA").unwrap_err().kind,
            ErrorKind::NotConnected
        );

        reg.on_connected("dev1", "clientA");
        assert!(reg.authorize_connected("dev1", "clientA").is_ok());
        assert_eq!(
            reg.authorize_connected("dev1", "clientB").unwrap_err().kind,
            ErrorKind::PeripheralBusy
        );
    }

    #[test]
    fn test_slots_accounting() {
        let reg = PeripheralRegistry::new(LeaseConfig {
            max_slots: 2,
            ..Default::default()
        });
        assert_eq!(reg.free_slots(), 2);

        assert!(reg.acquire_lease("dev1", "clientA").is_ok());
        assert_eq!(reg.free_slots(), 1);

        assert!(reg.acquire_lease("dev2", "clientB").is_ok());
        assert_eq!(reg.free_slots(), 0);

        let err = reg.acquire_lease("dev3", "clientC").unwrap_err();
        assert_eq!(err.kind, ErrorKind::NoConnectionSlot);
    }

    #[tokio::test(start_paused = true)]
    async fn transport_drop_releases_and_tears_down_after_grace() {
        let reg = PeripheralRegistry::new(LeaseConfig {
            transport_grace: Duration::from_secs(5),
            ..Default::default()
        });
        let torn_down = Arc::new(AtomicUsize::new(0));
        let counter = torn_down.clone();
        reg.set_teardown(move |_handle| {
            let counter = counter.clone();
            async move {
                counter.fetch_add(1, Ordering::SeqCst);
            }
        });

        reg.acquire_lease("dev1", "clientA").unwrap();
        assert_eq!(reg.free_slots(), 7);

        reg.on_transport_drop("clientA");
        tokio::task::yield_now().await; // let the grace task register its sleep against now

        // Still leased (and slot held) within the grace window.
        tokio::time::advance(Duration::from_secs(4)).await;
        tokio::task::yield_now().await;
        assert_eq!(reg.free_slots(), 7);
        assert_eq!(torn_down.load(Ordering::SeqCst), 0);

        // Past the window: lease freed and warm link torn down exactly once.
        tokio::time::advance(Duration::from_secs(2)).await;
        tokio::task::yield_now().await;
        assert_eq!(reg.free_slots(), 8);
        assert_eq!(torn_down.load(Ordering::SeqCst), 1);
    }

    #[tokio::test(start_paused = true)]
    async fn reconnect_within_grace_keeps_lease_and_skips_teardown() {
        let reg = PeripheralRegistry::new(LeaseConfig {
            transport_grace: Duration::from_secs(5),
            ..Default::default()
        });
        let torn_down = Arc::new(AtomicUsize::new(0));
        let counter = torn_down.clone();
        reg.set_teardown(move |_handle| {
            let counter = counter.clone();
            async move {
                counter.fetch_add(1, Ordering::SeqCst);
            }
        });

        reg.acquire_lease("dev1", "clientA").unwrap();
        reg.on_transport_drop("clientA");
        tokio::task::yield_now().await; // let the grace task register its sleep against now
        tokio::time::advance(Duration::from_secs(2)).await;

        // Owner reconnects within the window: re-acquire cancels the pending release.
        reg.acquire_lease("dev1", "clientA").unwrap();

        tokio::time::advance(Duration::from_secs(10)).await;
        tokio::task::yield_now().await;
        assert_eq!(reg.free_slots(), 7, "lease must survive the resume");
        assert_eq!(
            torn_down.load(Ordering::SeqCst),
            0,
            "teardown must not fire"
        );
    }
}
