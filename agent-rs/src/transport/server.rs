use futures_util::{SinkExt, StreamExt};
use std::collections::{HashMap, HashSet, VecDeque};
use std::net::{IpAddr, SocketAddr};
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::time::{Duration, Instant};
use tokio::net::TcpListener;
use tokio::sync::{mpsc, oneshot};
use tokio::task::JoinSet;
use tokio_tungstenite::tungstenite::handshake::server::{Request, Response};
use tokio_tungstenite::tungstenite::protocol::{
    CloseFrame, Message, WebSocketConfig,
    frame::{Utf8Bytes, coding::CloseCode},
};
use tracing::Instrument;

use crate::ble::backend::{BleBackend, StreamKey};
use crate::protocol::{
    codec::{decode_cbor, encode_cbor},
    errors::{AgentError, ErrorKind},
    events::{AdvertisementDto, AgentEvent, BleConnState},
    frame::{Frame, PROTOCOL_VERSION, capabilities},
    op::Op,
    results::{OpResult, ResultPayload},
    status::{AgentStatusDto, LeaseStatusDto, StatusSettingsDto, StatusSlotsDto},
};
use crate::registry::lease_disclosure;
use crate::registry::peripheral_lease::{LeaseAcquisition, PeripheralRegistry};
use crate::registry::write_policy::{self, WritePolicy};
use crate::translate::{HandleTranslator, agent_identifier_format};
use crate::transport::negotiation::{
    HelloRequest, Negotiation, ProtocolVersionSelection, select_protocol_version,
};
use crate::transport::scan_coordinator::{ScanAdmission, ScanArbiter, ScanCoordinator};

pub use crate::transport::scan_coordinator::ScanConcurrencyMode;

/// Outbound frame buffer per connection. Bounds memory for a slow/stalled client.
const FRAME_CHANNEL_CAP: usize = 512;
/// Caps radio-to-transport event handoff. Advertisements may be shed; a notification producer
/// terminates its stream on overflow so it can never accumulate unbounded events.
const EVENT_CHANNEL_CAP: usize = 128;
/// Max commands executing concurrently per connection. Caps spawned tasks so a command
/// flood can't exhaust memory; the read loop backpressures (stops accepting) once hit.
const MAX_INFLIGHT_OPS: usize = 64;
const MAX_SCAN_FILTERS: usize = 64;
const MAX_ACTIVE_SCANS: usize = 16;
const MAX_ACTIVE_OBSERVATIONS: usize = 128;
const MAX_AUTH_FAILURES_PER_PEER: u32 = 5;
const MAX_AUTH_FAILURES_GLOBAL: u32 = 64;
const MAX_AUTH_TRACKED_PEERS: usize = 256;
const AUTH_FAILURE_WINDOW: Duration = Duration::from_secs(60);
const MAX_WRITE_BYTES: usize = 512;
const MIN_MTU: i32 = 23;
const MAX_MTU: i32 = 517;
/// Longest an advertisement is held waiting for company under capability `scan.batch`, so a
/// result is never delayed by more than this. Matches `BleAgent.DEFAULT_SCAN_BATCH_WINDOW`.
const SCAN_BATCH_WINDOW: Duration = Duration::from_millis(100);
/// Flush a batch early once a burst reaches this size, so a flood cannot grow the buffer
/// unbounded between ticks. Matches `BleAgent.DEFAULT_SCAN_BATCH_MAX_SIZE`.
const SCAN_BATCH_MAX_SIZE: usize = 16;
/// How often the agent pings an otherwise-idle client to probe liveness.
const PING_PERIOD: Duration = Duration::from_secs(15);
/// Close a connection if nothing is heard from the peer for this long (covers a missed
/// pong plus jitter — comfortably more than [PING_PERIOD]).
const LIVENESS_TIMEOUT: Duration = Duration::from_secs(40);
const INCOMPATIBLE_PROTOCOL_CLOSE_REASON: &str = "REMOTE_BLE_INCOMPATIBLE_PROTOCOL";
const MAX_FRAME_BYTES: usize = 1_048_576;
const FRAME_TOO_LARGE_CLOSE_REASON: &str = "REMOTE_BLE_FRAME_TOO_LARGE";
const DUPLICATE_SESSION_CLOSE_REASON: &str = "REMOTE_BLE_DUPLICATE_SESSION";
/// Protocol scan/subscription IDs are scoped to a client connection. This monotonically assigned
/// generation makes their backend keys unique even when clients reuse the same local IDs.
static NEXT_CONNECTION_GENERATION: AtomicU64 = AtomicU64::new(1);
static NEXT_LOG_CONNECTION_ID: AtomicU64 = AtomicU64::new(1);
static NEXT_SESSION_GENERATION: AtomicU64 = AtomicU64::new(1);

fn websocket_config() -> WebSocketConfig {
    WebSocketConfig::default()
        .max_message_size(Some(MAX_FRAME_BYTES))
        .max_frame_size(Some(MAX_FRAME_BYTES))
}
static NEXT_WRITE_SEQUENCE: AtomicU64 = AtomicU64::new(1);
static NEXT_SCAN_SEQUENCE: AtomicU64 = AtomicU64::new(1);

/// A write retains the completion sender for its position in one device's receive-order chain.
/// Dropping it (including on cancellation) wakes the successor, so a failed task cannot wedge
/// later writes indefinitely.
struct WriteReservation {
    device: String,
    sequence: u64,
    predecessor: Option<oneshot::Receiver<()>>,
    _completion: oneshot::Sender<()>,
}

struct WriteTail {
    sequence: u64,
    receiver: oneshot::Receiver<()>,
}

/// A scan lifecycle turn preserves receive order for one connection-local scan id.  Start/stop
/// commands still run concurrently with unrelated operations, but cannot publish stale bindings
/// after a newer same-id command has committed.
struct ScanReservation {
    scan_id: i64,
    sequence: u64,
    predecessor: Option<oneshot::Receiver<()>>,
    _completion: oneshot::Sender<()>,
}

struct ScanTail {
    sequence: u64,
    receiver: oneshot::Receiver<()>,
}

/// The writer owns the WebSocket sink, so normal frames and terminal close controls share one
/// bounded channel. This prevents the reader from racing cleanup ahead of a required close frame.
enum Outbound {
    Frame(Frame),
    IncompatibleProtocolClose,
    FrameTooLargeClose,
}

fn reserve_write(
    tails: &parking_lot::Mutex<HashMap<String, WriteTail>>,
    device: &str,
) -> WriteReservation {
    let sequence = NEXT_WRITE_SEQUENCE.fetch_add(1, Ordering::Relaxed);
    let (completion, receiver) = oneshot::channel();
    let predecessor = tails
        .lock()
        .insert(device.to_string(), WriteTail { sequence, receiver })
        .map(|tail| tail.receiver);
    WriteReservation {
        device: device.to_string(),
        sequence,
        predecessor,
        _completion: completion,
    }
}

fn release_write_tail(
    tails: &parking_lot::Mutex<HashMap<String, WriteTail>>,
    reservation: &WriteReservation,
) {
    let mut tails = tails.lock();
    if tails
        .get(&reservation.device)
        .is_some_and(|tail| tail.sequence == reservation.sequence)
    {
        tails.remove(&reservation.device);
    }
}

fn reserve_scan(
    tails: &parking_lot::Mutex<HashMap<i64, ScanTail>>,
    scan_id: i64,
) -> ScanReservation {
    let sequence = NEXT_SCAN_SEQUENCE.fetch_add(1, Ordering::Relaxed);
    let (completion, receiver) = oneshot::channel();
    let predecessor = tails
        .lock()
        .insert(scan_id, ScanTail { sequence, receiver })
        .map(|tail| tail.receiver);
    ScanReservation {
        scan_id,
        sequence,
        predecessor,
        _completion: completion,
    }
}

fn release_scan_tail(
    tails: &parking_lot::Mutex<HashMap<i64, ScanTail>>,
    reservation: &ScanReservation,
) {
    let mut tails = tails.lock();
    if tails
        .get(&reservation.scan_id)
        .is_some_and(|tail| tail.sequence == reservation.sequence)
    {
        tails.remove(&reservation.scan_id);
    }
}

struct ExecuteContext<'a> {
    client_id: &'a str,
    backend: &'a Arc<dyn BleBackend>,
    registry: &'a PeripheralRegistry,
    translator: &'a Arc<HandleTranslator>,
    event_tx: mpsc::Sender<AgentEvent>,
    connection_live: &'a AtomicBool,
    stream_connection: u64,
    streams: &'a StreamReservations,
    scan_coordinator: &'a ScanCoordinator,
    scan_mode: ScanConcurrencyMode,
    scan_arbiter: &'a ScanArbiter,
    scan_bindings: &'a ScanBindings,
    scan_gates: &'a ScanGates,
    negotiated_capabilities: &'a parking_lot::Mutex<std::collections::BTreeSet<String>>,
    /// Everything `agent.status` reports that no single op otherwise needs.
    status: &'a StatusContext,
}

/// The agent-wide facts an `agent.status` reply is assembled from. One instance per process,
/// shared by every connection.
///
/// Grouped rather than spread across [ExecuteContext] because they are one feature's inputs and
/// exactly one op reads them.
#[derive(Clone)]
struct StatusSource {
    /// When this agent process began serving.
    started_at: Instant,
    strict_identifiers: Arc<AtomicBool>,
    live_sessions: Arc<LiveSessionRegistry>,
    device_names: Arc<DeviceNames>,
    /// Per-principal write allowlist (U7). Lives here rather than a new `ExecuteContext` field
    /// because it's an agent-wide fact `agent.status` already reports (`writePolicyEnforced`) and
    /// exactly the two write-bearing ops need it otherwise — the same shape as the rest of this
    /// struct.
    write_policy: Arc<WritePolicy>,
}

/// [StatusSource] plus the one fact that is per-connection.
struct StatusContext {
    source: Arc<StatusSource>,
    /// Whether this connection presented a valid operator credential on the upgrade. Widens what
    /// `agent.status` discloses and authorizes nothing else.
    operator_scope: bool,
}

/// The human-readable engine/platform label, in `ServerHello` and in `agent.status` alike.
const AGENT_INFO: &str = concat!("RemoteBle-Agent-RS ", env!("CARGO_PKG_VERSION"));

/// Upgrade header carrying the optional operator credential. Mirrors the Kotlin
/// `OPERATOR_HEADER`; the two agents must read the same name or a CLI works against only one.
const OPERATOR_HEADER: &str = "X-RemoteBle-Operator";

/// Per-connection reservations for streaming BLE work. Backend stream keys are scoped to the
/// WebSocket generation, but backend implementations retain their own registrations, so cap and
/// reserve them before starting work rather than relying on command concurrency.
#[derive(Default)]
struct StreamReservations {
    scans: parking_lot::Mutex<HashSet<StreamKey>>,
    observations: parking_lot::Mutex<HashSet<StreamKey>>,
}

struct ScanBinding {
    registration: crate::transport::scan_coordinator::ScanRegistration,
    handle: crate::transport::scan_coordinator::ScanArbiterHandle,
}

#[derive(Default)]
struct ScanBindings {
    scans: tokio::sync::Mutex<HashMap<i64, ScanBinding>>,
}

/// Acknowledge-before-deliver for scans that bypass the arbiter.
///
/// The guaranteed modes park an arbiter mailbox, but `uncontrolled` streams straight from the
/// backend into the connection's event channel, so it needs its own gate to honour the same wire
/// rule: `scan.start`'s reply is written before any result it produces. A forwarding task holds the
/// backend's events until [`ScanGates::release`], which the command loop calls once the reply is
/// queued.
#[derive(Default)]
struct ScanGates {
    gates: tokio::sync::Mutex<HashMap<i64, tokio::sync::oneshot::Sender<()>>>,
}

impl ScanGates {
    /// Returns a sender to hand the backend, whose events are withheld until release.
    async fn gated(
        &self,
        scan_id: i64,
        event_tx: mpsc::Sender<AgentEvent>,
    ) -> mpsc::Sender<AgentEvent> {
        let (gated_tx, mut gated_rx) = mpsc::channel(EVENT_CHANNEL_CAP);
        let (release_tx, release_rx) = tokio::sync::oneshot::channel::<()>();
        tokio::spawn(async move {
            // Dropped rather than released — the connection went away before the reply was
            // written. Deliver nothing and unwind.
            if release_rx.await.is_err() {
                return;
            }
            while let Some(event) = gated_rx.recv().await {
                if event_tx.send(event).await.is_err() {
                    break;
                }
            }
        });
        // A same-id restart drops the previous gate, retiring the collector it was feeding.
        self.gates.lock().await.insert(scan_id, release_tx);
        gated_tx
    }

    /// Lets a gated scan deliver. A no-op for any scan that has no gate, so the command loop can
    /// call it unconditionally alongside the arbiter's release.
    async fn release(&self, scan_id: i64) {
        if let Some(release) = self.gates.lock().await.remove(&scan_id) {
            let _ = release.send(());
        }
    }

    async fn discard(&self, scan_id: i64) {
        self.gates.lock().await.remove(&scan_id);
    }
}

impl ScanBindings {
    async fn replace(&self, scan_id: i64, binding: ScanBinding) {
        if let Some(previous) = self.scans.lock().await.insert(scan_id, binding) {
            previous.handle.close();
        }
    }
    async fn remove(&self, scan_id: i64) -> Option<ScanBinding> {
        self.scans.lock().await.remove(&scan_id)
    }
    /// Clears a just-admitted scan for delivery, once its reply is queued on the outbound channel.
    ///
    /// A no-op for a scan that was refused, so the caller can release unconditionally after the
    /// reply without inspecting the result.
    async fn release(&self, scan_id: i64) {
        if let Some(binding) = self.scans.lock().await.get(&scan_id) {
            binding.handle.release();
        }
    }
    async fn clear(&self) -> Vec<ScanBinding> {
        std::mem::take(&mut *self.scans.lock().await)
            .into_values()
            .collect()
    }
}

impl StreamReservations {
    fn reserve_scan(&self, stream: StreamKey) -> Result<bool, AgentError> {
        Self::reserve(&self.scans, stream, MAX_ACTIVE_SCANS, "scans")
    }

    fn reserve_observation(&self, stream: StreamKey) -> Result<bool, AgentError> {
        Self::reserve(
            &self.observations,
            stream,
            MAX_ACTIVE_OBSERVATIONS,
            "observations",
        )
    }

    fn reserve(
        set: &parking_lot::Mutex<HashSet<StreamKey>>,
        stream: StreamKey,
        limit: usize,
        label: &str,
    ) -> Result<bool, AgentError> {
        let mut set = set.lock();
        if set.contains(&stream) {
            return Ok(false); // same ID replaces its existing backend stream without consuming a slot
        }
        if set.len() >= limit {
            return Err(AgentError::new(
                ErrorKind::InvalidRequest,
                Some(format!("at most {limit} active {label} are allowed")),
            ));
        }
        set.insert(stream);
        Ok(true)
    }

    fn release_scan(&self, stream: StreamKey) {
        self.scans.lock().remove(&stream);
    }

    fn release_observation(&self, stream: StreamKey) {
        self.observations.lock().remove(&stream);
    }

    fn clear(&self) {
        self.scans.lock().clear();
        self.observations.lock().clear();
    }
}

pub struct ServerConfig {
    pub addr: SocketAddr,
    /// Principal names mapped to bearer secrets. Names remain server-side and scope ownership.
    pub credentials: Arc<HashMap<String, String>>,
    /// Agent-wide identifier strict-mode switch (capability `identifier.translate`). Shared across
    /// connections; when set, handles pass through untranslated.
    pub strict_identifiers: Arc<AtomicBool>,
    pub scan_concurrency: ScanConcurrencyMode,
    pub transport_grace: Duration,
    /// Optional **operator** credential, distinct from every client credential. Presenting it on
    /// the upgrade (`X-RemoteBle-Operator: Bearer …`) widens what `agent.status` discloses to that
    /// session — every lease and its holder — and grants nothing else. `None` means no session can
    /// ever obtain operator scope from this agent.
    pub operator_token: Option<String>,
    /// Per-principal write allowlist (U7). Permissive by default: no existing consumer breaks.
    pub write_policy: WritePolicy,
}

fn constant_time_eq(expected: &str, candidate: &str) -> bool {
    let expected = expected.as_bytes();
    let candidate = candidate.as_bytes();
    let mut difference = expected.len() ^ candidate.len();
    for index in 0..expected.len().max(candidate.len()) {
        let left = expected.get(index).copied().unwrap_or_default();
        let right = candidate.get(index).copied().unwrap_or_default();
        difference |= usize::from(left ^ right);
    }
    difference == 0
}

/// AUTH-REVOKE-01: every connection attempt — a fresh handshake or a resume reconnect within a
/// lease's transport grace ([crate::registry::peripheral_lease::PeripheralRegistry::on_transport_drop])
/// — re-authenticates from scratch here, so a principal revoked mid-grace cannot resume: its next
/// reconnect fails this check before the registry is ever consulted.
fn authenticate(
    credentials: &HashMap<String, String>,
    revoked: &parking_lot::Mutex<HashSet<String>>,
    authorization: Option<&str>,
) -> Option<String> {
    if credentials.is_empty() {
        return Some("anonymous".to_string());
    }
    let bearer = authorization?.strip_prefix("Bearer ")?;
    let principal = credentials
        .iter()
        .find(|(_, secret)| constant_time_eq(secret, bearer))
        .map(|(principal, _)| principal.clone())?;
    if revoked.lock().contains(&principal) {
        return None;
    }
    Some(principal)
}

fn session_key(principal: &str, client_id: &str) -> String {
    format!("{principal}\0{client_id}")
}

/// What this host's **radio** can do. Agent-level capabilities are not asked of the backend and
/// are not added here — [crate::transport::negotiation::Negotiation::on_hello] applies
/// [crate::protocol::frame::capabilities::AGENT_CAPABILITIES] unconditionally, so that nothing a
/// backend reports can withhold one.
fn supported_capabilities(
    mut backend_capabilities: Vec<String>,
    scan_mode: ScanConcurrencyMode,
) -> Vec<String> {
    backend_capabilities.retain(|capability| {
        !matches!(
            capability.as_str(),
            crate::protocol::frame::capabilities::SCAN_CONCURRENCY_MULTIPLEXED
                | crate::protocol::frame::capabilities::SCAN_CONCURRENCY_SINGLE
                | crate::protocol::frame::capabilities::SCAN_CONCURRENCY_UNCONTROLLED
        )
    });
    backend_capabilities.push(scan_mode.capability().to_string());
    backend_capabilities
}

/// Allows exactly one live WebSocket generation for each authenticated stable client identity.
/// The generation token makes release safe even if a future policy replaces an active socket.
#[derive(Default)]
struct LiveSessionRegistry {
    generations: parking_lot::Mutex<HashMap<String, u64>>,
}

impl LiveSessionRegistry {
    fn try_acquire(&self, client_id: &str, generation: u64) -> bool {
        let mut generations = self.generations.lock();
        if generations.contains_key(client_id) {
            return false;
        }
        generations.insert(client_id.to_string(), generation);
        true
    }

    fn release(&self, client_id: &str, generation: u64) {
        let mut generations = self.generations.lock();
        if generations.get(client_id) == Some(&generation) {
            generations.remove(client_id);
        }
    }

    /// How many client sessions are live right now, across every principal — what `agent.status`
    /// reports. One entry per admitted identity, which is exactly one live socket each.
    fn len(&self) -> usize {
        self.generations.lock().len()
    }
}

/// Last-seen advertised names, keyed by real radio handle, for `agent.status` lease rows.
///
/// btleplug hands the name to the scan path and nothing else keeps it, so without this the Rust
/// agent would report a permanently null name where the Kotlin agent reports a real one — a
/// divergence in what one status command answers depending on which agent it reached. Bounded, and
/// evicted oldest-first: a long scan in a busy room must not grow this without limit.
#[derive(Default)]
struct DeviceNames {
    names: parking_lot::Mutex<(HashMap<String, String>, VecDeque<String>)>,
}

impl DeviceNames {
    const MAX_ENTRIES: usize = 256;

    fn observe(&self, handle: &str, name: Option<&str>) {
        let Some(name) = name.filter(|n| !n.is_empty()) else {
            return;
        };
        let mut guard = self.names.lock();
        let (map, order) = &mut *guard;
        if map.insert(handle.to_string(), name.to_string()).is_none() {
            order.push_back(handle.to_string());
            while order.len() > Self::MAX_ENTRIES {
                if let Some(evicted) = order.pop_front() {
                    map.remove(&evicted);
                }
            }
        }
    }

    fn get(&self, handle: &str) -> Option<String> {
        self.names.lock().0.get(handle).cloned()
    }
}

/// Bounded failed-auth accounting for upgrade attempts. The least-recently-seen peer is evicted
/// at capacity, so spoofed source addresses cannot create an unbounded denial-of-service map.
struct AuthFailureLimiter {
    state: parking_lot::Mutex<AuthFailureState>,
}

struct AuthFailureState {
    window_started: Instant,
    global_failures: u32,
    peers: HashMap<IpAddr, AuthFailurePeer>,
}

struct AuthFailurePeer {
    failures: u32,
    last_seen: Instant,
    last_limited_log: Option<Instant>,
}

struct AuthFailureDecision {
    allowed: bool,
    should_log: bool,
}

impl Default for AuthFailureLimiter {
    fn default() -> Self {
        Self {
            state: parking_lot::Mutex::new(AuthFailureState {
                window_started: Instant::now(),
                global_failures: 0,
                peers: HashMap::new(),
            }),
        }
    }
}

impl AuthFailureLimiter {
    fn record_failure(&self, peer: IpAddr) -> AuthFailureDecision {
        let now = Instant::now();
        let mut state = self.state.lock();
        if now.duration_since(state.window_started) >= AUTH_FAILURE_WINDOW {
            state.window_started = now;
            state.global_failures = 0;
            state
                .peers
                .retain(|_, entry| now.duration_since(entry.last_seen) < AUTH_FAILURE_WINDOW);
        }
        if !state.peers.contains_key(&peer) {
            if state.peers.len() >= MAX_AUTH_TRACKED_PEERS
                && let Some(evicted) = state
                    .peers
                    .iter()
                    .min_by_key(|(_, entry)| entry.last_seen)
                    .map(|(peer, _)| *peer)
            {
                state.peers.remove(&evicted);
            }
            state.peers.insert(
                peer,
                AuthFailurePeer {
                    failures: 0,
                    last_seen: now,
                    last_limited_log: None,
                },
            );
        }
        let global_limited = state.global_failures >= MAX_AUTH_FAILURES_GLOBAL;
        let entry = state.peers.get_mut(&peer).expect("peer inserted above");
        entry.last_seen = now;
        if global_limited || entry.failures >= MAX_AUTH_FAILURES_PER_PEER {
            let should_log = entry
                .last_limited_log
                .is_none_or(|last| now.duration_since(last) >= AUTH_FAILURE_WINDOW);
            if should_log {
                entry.last_limited_log = Some(now);
            }
            return AuthFailureDecision {
                allowed: false,
                should_log,
            };
        }
        entry.failures += 1;
        state.global_failures += 1;
        AuthFailureDecision {
            allowed: true,
            should_log: true,
        }
    }
}

pub struct AgentServer {
    config: ServerConfig,
    backend: Arc<dyn BleBackend>,
    registry: PeripheralRegistry,
    live_sessions: Arc<LiveSessionRegistry>,
    failed_auth_limiter: Arc<AuthFailureLimiter>,
    revoked_principals: Arc<parking_lot::Mutex<HashSet<String>>>,
    scan_coordinator: ScanCoordinator,
    status_source: Arc<StatusSource>,
}

impl AgentServer {
    pub fn new(
        config: ServerConfig,
        backend: Arc<dyn BleBackend>,
        registry: PeripheralRegistry,
    ) -> Self {
        let scan_coordinator = ScanCoordinator::new(
            backend.clone(),
            config.scan_concurrency,
            config.transport_grace,
            MAX_ACTIVE_SCANS,
        );
        let live_sessions = Arc::new(LiveSessionRegistry::default());
        let status_source = Arc::new(StatusSource {
            // Taken here rather than at first connection: uptime means "how long has this agent
            // been serving", and a value that only starts once someone connects would answer a
            // different question.
            started_at: Instant::now(),
            strict_identifiers: config.strict_identifiers.clone(),
            live_sessions: live_sessions.clone(),
            device_names: Arc::new(DeviceNames::default()),
            // Cloned rather than moved: `config` as a whole is still stored on `Self` below.
            write_policy: Arc::new(config.write_policy.clone()),
        });
        Self {
            config,
            backend,
            registry,
            live_sessions,
            failed_auth_limiter: Arc::new(AuthFailureLimiter::default()),
            revoked_principals: Arc::new(parking_lot::Mutex::new(HashSet::new())),
            scan_coordinator,
            status_source,
        }
    }

    /// Revokes `principal` at runtime: every future connection attempt for it fails
    /// authentication (see [authenticate]) until [Self::unrevoke_principal]. Errs if `principal`
    /// is not one of this server's configured credential names.
    ///
    /// No operator trigger (signal, admin endpoint) calls this yet in `main.rs` — `agent-rs` has
    /// no equivalent of the Kotlin agent's dashboard mutation routes today — so it's exercised by
    /// the AUTH-REVOKE-01 conformance tests only. Wiring a real trigger is a separate follow-up.
    #[allow(dead_code)]
    pub fn revoke_principal(&self, principal: &str) -> Result<(), String> {
        if !self.config.credentials.contains_key(principal) {
            return Err(format!("unknown credential principal: {principal}"));
        }
        self.revoked_principals.lock().insert(principal.to_string());
        Ok(())
    }

    /// Restores a previously [Self::revoke_principal]d principal.
    #[allow(dead_code)]
    pub fn unrevoke_principal(&self, principal: &str) {
        self.revoked_principals.lock().remove(principal);
    }

    #[allow(dead_code)]
    pub fn is_principal_revoked(&self, principal: &str) -> bool {
        self.revoked_principals.lock().contains(principal)
    }

    pub async fn run(&self) -> Result<(), Box<dyn std::error::Error>> {
        let listener = TcpListener::bind(self.config.addr).await?;
        tracing::info!(
            "Agent WebSocket server listening on ws://{}",
            self.config.addr
        );

        // A transient accept() error (fd exhaustion, ECONNABORTED, a peer that
        // reset between SYN and accept) must never take the whole agent down — it
        // serves every other client. Log it, back off briefly so an EMFILE storm
        // can't spin the CPU, and keep listening. `accept_backoff` resets to zero
        // after any success.
        let mut accept_backoff = Duration::from_millis(0);
        const MAX_ACCEPT_BACKOFF: Duration = Duration::from_secs(1);
        loop {
            let (stream, peer_addr) = match listener.accept().await {
                Ok(accepted) => {
                    accept_backoff = Duration::from_millis(0);
                    accepted
                }
                Err(e) => {
                    accept_backoff =
                        (accept_backoff * 2).clamp(Duration::from_millis(50), MAX_ACCEPT_BACKOFF);
                    tracing::warn!("accept() failed ({}); retrying in {:?}", e, accept_backoff);
                    tokio::time::sleep(accept_backoff).await;
                    continue;
                }
            };
            let backend = self.backend.clone();
            let registry = self.registry.clone();
            let credentials = self.config.credentials.clone();
            let strict = self.config.strict_identifiers.clone();
            let live_sessions = self.live_sessions.clone();
            let failed_auth_limiter = self.failed_auth_limiter.clone();
            let revoked_principals = self.revoked_principals.clone();
            let scan_coordinator = self.scan_coordinator.clone();
            let scan_mode = self.config.scan_concurrency;
            let status_source = self.status_source.clone();
            let operator_token = self.config.operator_token.clone();

            tokio::spawn(Self::accept_connection_with_scan(
                stream,
                peer_addr,
                backend,
                registry,
                credentials,
                strict,
                live_sessions,
                failed_auth_limiter,
                revoked_principals,
                scan_coordinator,
                scan_mode,
                status_source,
                operator_token,
            ));
        }
    }

    /// Runs the pre-upgrade auth/duplicate-session handshake and, on success, the connection's
    /// full lifecycle. Generic over the stream so tests can drive it with an in-memory duplex
    /// pair instead of a real `TcpStream` while exercising the exact handshake the live server
    /// uses (`run()` calls this for every accepted socket).
    // The WebSocket handshake callback must return `http::Response` in its Err arm
    // (tungstenite's API), which clippy flags as a large Err variant. The type is
    // fixed by the upstream signature, so the lint doesn't apply here.
    /// Test-only convenience wrapper: mints a **fresh** [ScanCoordinator] for this one connection.
    ///
    /// That deliberately defeats the agent-wide guarantee — two connections accepted through this
    /// helper do not share a coordinator, so `single` admission, cross-client multiplexing, the
    /// stable-client cap and grace-held rebinds would all appear to work when they do not. It
    /// exists for tests about auth, leases and framing, where scans are not the subject. **Any
    /// test that asserts scan-concurrency behaviour must call [Self::accept_connection_with_scan]
    /// with one shared coordinator instead.**
    #[allow(clippy::too_many_arguments, clippy::result_large_err)]
    #[allow(dead_code)]
    async fn accept_connection<S>(
        stream: S,
        peer_addr: SocketAddr,
        backend: Arc<dyn BleBackend>,
        registry: PeripheralRegistry,
        credentials: Arc<HashMap<String, String>>,
        strict: Arc<AtomicBool>,
        live_sessions: Arc<LiveSessionRegistry>,
        failed_auth_limiter: Arc<AuthFailureLimiter>,
        revoked_principals: Arc<parking_lot::Mutex<HashSet<String>>>,
        operator_token: Option<String>,
    ) where
        S: tokio::io::AsyncRead + tokio::io::AsyncWrite + Unpin + Send + 'static,
    {
        let status_source = Arc::new(StatusSource {
            started_at: Instant::now(),
            strict_identifiers: strict.clone(),
            live_sessions: live_sessions.clone(),
            device_names: Arc::new(DeviceNames::default()),
            write_policy: Arc::new(WritePolicy::permissive()),
        });
        Self::accept_connection_with_scan(
            stream,
            peer_addr,
            backend.clone(),
            registry,
            credentials,
            strict,
            live_sessions,
            failed_auth_limiter,
            revoked_principals,
            ScanCoordinator::new(
                backend.clone(),
                ScanConcurrencyMode::Multiplexed,
                Duration::from_secs(10),
                MAX_ACTIVE_SCANS,
            ),
            ScanConcurrencyMode::Multiplexed,
            status_source,
            operator_token,
        )
        .await
    }

    #[allow(clippy::too_many_arguments, clippy::result_large_err)]
    async fn accept_connection_with_scan<S>(
        stream: S,
        peer_addr: SocketAddr,
        backend: Arc<dyn BleBackend>,
        registry: PeripheralRegistry,
        credentials: Arc<HashMap<String, String>>,
        strict: Arc<AtomicBool>,
        live_sessions: Arc<LiveSessionRegistry>,
        failed_auth_limiter: Arc<AuthFailureLimiter>,
        revoked_principals: Arc<parking_lot::Mutex<HashSet<String>>>,
        scan_coordinator: ScanCoordinator,
        scan_mode: ScanConcurrencyMode,
        status_source: Arc<StatusSource>,
        operator_token: Option<String>,
    ) where
        S: tokio::io::AsyncRead + tokio::io::AsyncWrite + Unpin + Send + 'static,
    {
        let mut client_id = format!("anon-{}", peer_addr);
        let mut principal = None;
        let mut session_generation = None;
        let mut operator_scope = false;

        let callback = |req: &Request,
                        response: Response|
         -> Result<Response, http::Response<Option<String>>> {
            principal = authenticate(
                credentials.as_ref(),
                revoked_principals.as_ref(),
                req.headers()
                    .get("Authorization")
                    .and_then(|header| header.to_str().ok()),
            );

            // Optional second credential, widening only what `agent.status` discloses. A missing
            // or wrong value is deliberately NOT a rejection: the session proceeds at normal scope
            // and says so in its status reply, so a client that asked for operator-only fields
            // without the secret can tell that apart from an unreachable agent. A *wrong* value is
            // still a guess at the operator secret, so it is rate-limited like any other.
            if let Some(expected) = operator_token.as_deref() {
                let offered = req
                    .headers()
                    .get(OPERATOR_HEADER)
                    .and_then(|header| header.to_str().ok())
                    .and_then(|value| value.strip_prefix("Bearer "))
                    .filter(|value| !value.is_empty());
                if let Some(offered) = offered {
                    operator_scope = constant_time_eq(expected, offered);
                    if !operator_scope {
                        let decision = failed_auth_limiter.record_failure(peer_addr.ip());
                        if decision.should_log {
                            tracing::warn!(
                                "operator scope refused for {}: bad credential",
                                peer_addr
                            );
                        }
                    }
                }
            }

            if let Some(cid_hdr) = req.headers().get("X-RemoteBle-Client")
                && let Ok(str_val) = cid_hdr.to_str()
            {
                client_id = str_val.to_string();
            }

            let Some(principal) = principal.as_deref() else {
                let decision = failed_auth_limiter.record_failure(peer_addr.ip());
                if decision.should_log {
                    tracing::warn!(
                        "client rejected from {}: {}",
                        peer_addr,
                        if decision.allowed {
                            "unauthorized"
                        } else {
                            "authentication rate limited"
                        }
                    );
                }
                let rejected = http::Response::builder()
                    .status(if decision.allowed { 401 } else { 429 })
                    .body(Some(
                        if decision.allowed {
                            "Unauthorized"
                        } else {
                            "Too Many Requests"
                        }
                        .to_string(),
                    ))
                    .unwrap();
                return Err(rejected);
            };
            if client_id.trim().is_empty() || client_id.len() > 128 || client_id.contains('\0') {
                let rejected = http::Response::builder()
                    .status(400)
                    .body(Some("Invalid X-RemoteBle-Client".to_string()))
                    .unwrap();
                return Err(rejected);
            }
            client_id = session_key(principal, &client_id);
            let generation = NEXT_SESSION_GENERATION.fetch_add(1, Ordering::Relaxed);
            if !live_sessions.try_acquire(&client_id, generation) {
                tracing::warn!("client rejected from {}: duplicate live session", peer_addr);
                let rejected = http::Response::builder()
                    .status(409)
                    .body(Some(DUPLICATE_SESSION_CLOSE_REASON.to_string()))
                    .unwrap();
                return Err(rejected);
            }
            session_generation = Some(generation);

            Ok(response)
        };

        match tokio_tungstenite::accept_hdr_async_with_config(
            stream,
            callback,
            Some(websocket_config()),
        )
        .await
        {
            Ok(ws_stream) => {
                let connection = NEXT_LOG_CONNECTION_ID.fetch_add(1, Ordering::Relaxed);
                let span = tracing::info_span!("conn", connection, peer = %peer_addr);
                tracing::info!(parent: &span, "Client connected");
                Self::handle_connection_with_scan(
                    ws_stream,
                    client_id,
                    backend,
                    registry,
                    strict,
                    live_sessions,
                    session_generation.expect("accepted session must have a generation"),
                    scan_coordinator,
                    scan_mode,
                    StatusContext {
                        source: status_source,
                        operator_scope,
                    },
                )
                .instrument(span)
                .await;
            }
            Err(e) => {
                if let Some(generation) = session_generation {
                    live_sessions.release(&client_id, generation);
                }
                tracing::warn!("Handshake failed for {}: {}", peer_addr, e);
            }
        }
    }

    #[allow(dead_code)]
    async fn handle_connection<S>(
        ws_stream: tokio_tungstenite::WebSocketStream<S>,
        client_id: String,
        backend: Arc<dyn BleBackend>,
        registry: PeripheralRegistry,
        strict: Arc<AtomicBool>,
        live_sessions: Arc<LiveSessionRegistry>,
        session_generation: u64,
    ) where
        S: tokio::io::AsyncRead + tokio::io::AsyncWrite + Unpin + Send + 'static,
    {
        let status = StatusContext {
            source: Arc::new(StatusSource {
                started_at: Instant::now(),
                strict_identifiers: strict.clone(),
                live_sessions: live_sessions.clone(),
                device_names: Arc::new(DeviceNames::default()),
                write_policy: Arc::new(WritePolicy::permissive()),
            }),
            operator_scope: false,
        };
        Self::handle_connection_with_scan(
            ws_stream,
            client_id,
            backend.clone(),
            registry,
            strict,
            live_sessions,
            session_generation,
            ScanCoordinator::new(
                backend.clone(),
                ScanConcurrencyMode::Multiplexed,
                Duration::from_secs(10),
                MAX_ACTIVE_SCANS,
            ),
            ScanConcurrencyMode::Multiplexed,
            status,
        )
        .await
    }

    #[allow(clippy::too_many_arguments)]
    async fn handle_connection_with_scan<S>(
        ws_stream: tokio_tungstenite::WebSocketStream<S>,
        client_id: String,
        backend: Arc<dyn BleBackend>,
        registry: PeripheralRegistry,
        strict: Arc<AtomicBool>,
        live_sessions: Arc<LiveSessionRegistry>,
        session_generation: u64,
        scan_coordinator: ScanCoordinator,
        scan_mode: ScanConcurrencyMode,
        status: StatusContext,
    ) where
        S: tokio::io::AsyncRead + tokio::io::AsyncWrite + Unpin + Send + 'static,
    {
        let status = Arc::new(status);
        let (mut ws_sender, mut ws_receiver) = ws_stream.split();
        // Per-connection handle translator (capability `identifier.translate`). Identity until a
        // ClientHello configures it; shared with the event pump (forward) and op tasks (reverse).
        let translator = Arc::new(HandleTranslator::new(agent_identifier_format(), strict));
        // Bounded so a stalled/slow client can't make outbound buffers grow without limit.
        // Replies apply backpressure (await space, bounded by the in-flight-op semaphore);
        // events are shed on overflow (a flood of notifications must never block the radio).
        let (frame_tx, mut frame_rx) = mpsc::channel::<Outbound>(FRAME_CHANNEL_CAP);
        let inflight = Arc::new(tokio::sync::Semaphore::new(MAX_INFLIGHT_OPS));
        // Every command task belongs to this WebSocket generation. Retiring the connection aborts
        // and joins the set before lease cleanup, so no detached command can later resurrect an
        // abandoned connection/lease.
        let connection_live = Arc::new(AtomicBool::new(true));
        let mut command_tasks = JoinSet::new();
        let stream_connection = NEXT_CONNECTION_GENERATION.fetch_add(1, Ordering::Relaxed);
        let streams = Arc::new(StreamReservations::default());
        let scan_bindings = Arc::new(ScanBindings::default());
        let scan_gates = Arc::new(ScanGates::default());
        // Reserved in the sequential receive loop, rather than inside tasks, to preserve command
        // receive order for writes to the same physical device while other work stays concurrent.
        let write_tails = Arc::new(parking_lot::Mutex::new(HashMap::new()));
        let scan_tails = Arc::new(parking_lot::Mutex::new(HashMap::new()));

        // Liveness: a client that vanishes without a TCP FIN (Wi-Fi drop, NAT timeout, sleep)
        // would otherwise hold its slot/lease until the OS keepalive fires (minutes). We ping
        // periodically and close the link if we've heard nothing — including the auto-pong
        // tungstenite sends for our ping — within LIVENESS_TIMEOUT. Updated on every inbound frame.
        let last_activity = Arc::new(parking_lot::Mutex::new(Instant::now()));
        let last_activity_send = last_activity.clone();
        // The reader owns cleanup, but it cannot observe a failed writer/ping while blocked in
        // `next()`. The writer therefore signals one terminal reason; the reader then retires the
        // generation through the same path it uses for EOF or an inbound close frame.
        let (terminal_tx, mut terminal_rx) = mpsc::channel::<&'static str>(1);

        let send_task = tokio::spawn(async move {
            let mut ping = tokio::time::interval(PING_PERIOD);
            ping.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);
            let reason = loop {
                tokio::select! {
                    maybe_frame = frame_rx.recv() => {
                        match maybe_frame {
                            Some(outbound) => {
                                match outbound {
                                    Outbound::Frame(frame) => {
                                        if let Ok(bytes) = encode_cbor(&frame)
                                            && ws_sender.send(Message::Binary(bytes.into())).await.is_err()
                                        {
                                            break "writer send failed";
                                        }
                                    }
                                    Outbound::IncompatibleProtocolClose => {
                                        let _ = ws_sender.send(Message::Close(Some(CloseFrame {
                                            code: CloseCode::Protocol,
                                            reason: Utf8Bytes::from_static(INCOMPATIBLE_PROTOCOL_CLOSE_REASON),
                                        }))).await;
                                        break "protocol incompatible";
                                    }
                                    Outbound::FrameTooLargeClose => {
                                        let _ = ws_sender.send(Message::Close(Some(CloseFrame {
                                            code: CloseCode::Size,
                                            reason: Utf8Bytes::from_static(FRAME_TOO_LARGE_CLOSE_REASON),
                                        }))).await;
                                        break "frame too large";
                                    }
                                }
                            }
                            None => break "outbound frame channel closed",
                        }
                    }
                    _ = ping.tick() => {
                        if last_activity_send.lock().elapsed() > LIVENESS_TIMEOUT {
                            tracing::info!("client idle past liveness timeout; closing");
                            let _ = ws_sender.send(Message::Close(None)).await;
                            break "liveness timeout";
                        }
                        if ws_sender.send(Message::Ping(Vec::new().into())).await.is_err() {
                            break "ping failed";
                        }
                    }
                }
            };
            // A full channel already contains a terminal signal; either way the reader will
            // retire this generation exactly once.
            let _ = terminal_tx.try_send(reason);
        });

        let negotiated_capabilities =
            Arc::new(parking_lot::Mutex::new(std::collections::BTreeSet::new()));

        let (event_tx, mut event_rx) = mpsc::channel::<AgentEvent>(EVENT_CHANNEL_CAP);
        let scan_arbiter = ScanArbiter::new(event_tx.clone());
        let frame_tx_event = frame_tx.clone();
        let translator_event = translator.clone();
        let batch_capabilities = negotiated_capabilities.clone();
        let device_names = status.source.device_names.clone();
        let event_task = tokio::spawn(async move {
            // Advertisements held for coalescing under capability `scan.batch`, keyed by scan id.
            // Batching lives here, at the one point both scan paths converge — the coordinator's
            // arbiter and the uncontrolled backend path both feed this channel — rather than being
            // implemented twice. Non-scan events pass straight through, as in the Kotlin agent,
            // where batching is likewise internal to a scan and orders nothing else against it.
            let mut pending: HashMap<i64, Vec<AdvertisementDto>> = HashMap::new();
            let mut flush = tokio::time::interval(SCAN_BATCH_WINDOW);
            flush.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Delay);

            // Forward-translate the real handle the event carries into the client's format, then
            // shed rather than block the radio when the client can't keep up. `false` means the
            // frame channel is gone and this pump is finished.
            let deliver = |event: AgentEvent| -> bool {
                let event = translator_event.to_client_event(event);
                match frame_tx_event.try_send(Outbound::Frame(Frame::Event { event })) {
                    Ok(()) => true,
                    Err(mpsc::error::TrySendError::Full(_)) => {
                        tracing::warn!("outbound frame buffer full; dropping event");
                        true
                    }
                    Err(mpsc::error::TrySendError::Closed(_)) => false,
                }
            };

            'pump: loop {
                tokio::select! {
                    maybe_event = event_rx.recv() => {
                        let Some(event) = maybe_event else { break };
                        // Remember advertised names here, where the handle is still the real one
                        // and before batching can fold the advertisement away. This is the only
                        // place btleplug offers a name, and `agent.status` has no other source for
                        // it — without this the Rust agent would report a permanently null name
                        // where the Kotlin agent reports a real one.
                        if let AgentEvent::ScanResult { advertisement, .. } = &event {
                            device_names.observe(
                                &advertisement.device.value,
                                advertisement.name.as_deref(),
                            );
                        }
                        // Read live rather than latched, so a scan opened before the hello starts
                        // batching once a later hello negotiates it — the same rule §5.3 states
                        // for handle translation on an already-running stream.
                        let batching = batch_capabilities.lock().contains(capabilities::SCAN_BATCH);
                        match event {
                            AgentEvent::ScanResult { scan_id, advertisement } if batching => {
                                let full = {
                                    let buffered = pending.entry(scan_id).or_default();
                                    buffered.push(advertisement);
                                    buffered.len() >= SCAN_BATCH_MAX_SIZE
                                };
                                if full
                                    && let Some(advertisements) = pending.remove(&scan_id)
                                    && !deliver(AgentEvent::ScanResultBatch { scan_id, advertisements })
                                {
                                    break 'pump;
                                }
                            }
                            other => if !deliver(other) { break 'pump },
                        }
                    }
                    _ = flush.tick() => {
                        for (scan_id, advertisements) in pending.drain() {
                            if !deliver(AgentEvent::ScanResultBatch { scan_id, advertisements }) {
                                break 'pump;
                            }
                        }
                    }
                }
            }

            // A scan that ends mid-window must not strand the results already buffered for it.
            for (scan_id, advertisements) in pending.drain() {
                if !deliver(AgentEvent::ScanResultBatch {
                    scan_id,
                    advertisements,
                }) {
                    break;
                }
            }
        });

        // Per-connection handshake state — first hello wins; see [Negotiation].
        let mut negotiation = Negotiation::new();
        // Runs only for a client that negotiated `slots`; aborted with the other pumps on retire.
        let mut slot_feed: Option<tokio::task::JoinHandle<()>> = None;
        let mut closing_for_protocol = false;

        loop {
            let msg_res = tokio::select! {
                maybe_msg = ws_receiver.next() => match maybe_msg {
                    Some(msg) => msg,
                    None => break,
                },
                Some(reason) = terminal_rx.recv() => {
                    tracing::info!("connection writer terminated: {}; retiring connection", reason);
                    break;
                }
            };
            if closing_for_protocol {
                // The close control is queued on the writer. Ignore any racing inbound frames until
                // its terminal signal reaches this reader and triggers the one cleanup path.
                continue;
            }
            // Reap finished commands as the socket remains active. The in-flight semaphore bounds
            // running work, but retaining completed JoinSet entries would otherwise still grow
            // memory on a long-lived busy connection.
            while let Some(result) = command_tasks.try_join_next() {
                if let Err(e) = result {
                    tracing::debug!("command task ended without a result: {}", e);
                }
            }
            let msg = match msg_res {
                Ok(m) => m,
                Err(_) => break,
            };

            // Any inbound traffic (data, the peer's pong, control frames) proves the peer is alive.
            *last_activity.lock() = Instant::now();

            if msg.is_close() {
                break;
            }

            if msg.is_binary() {
                let data = msg.into_data();
                if data.len() > MAX_FRAME_BYTES {
                    tracing::warn!("client rejected: frame exceeds {} bytes", MAX_FRAME_BYTES);
                    let _ = frame_tx.send(Outbound::FrameTooLargeClose).await;
                    continue;
                }
                tracing::trace!("Incoming binary CBOR ({} bytes): {:02x?}", data.len(), data);
                match decode_cbor(&data) {
                    Ok(frame) => {
                        tracing::trace!("Decoded frame: {:?}", frame);
                        match frame {
                            Frame::ClientHello {
                                min_version,
                                max_version,
                                capabilities: wanted,
                                identifier_format,
                            } => {
                                let version =
                                    match select_protocol_version(min_version, max_version) {
                                        ProtocolVersionSelection::Selected(version) => version,
                                        ProtocolVersionSelection::InvalidRange
                                        | ProtocolVersionSelection::NoCompatibleVersion => {
                                            tracing::warn!(
                                                "client rejected: incompatible protocol v{}..{}",
                                                min_version,
                                                max_version
                                            );
                                            closing_for_protocol = true;
                                            let _ = frame_tx
                                                .send(Outbound::IncompatibleProtocolClose)
                                                .await;
                                            continue;
                                        }
                                    };
                                let caps = negotiation.on_hello(
                                    HelloRequest {
                                        min_version,
                                        max_version,
                                        wanted,
                                        identifier_format,
                                    },
                                    &translator,
                                    || supported_capabilities(backend.capabilities(), scan_mode),
                                    || registry.held_by(&client_id),
                                );
                                let reply_frame = Frame::ServerHello {
                                    version,
                                    capabilities: caps.clone(),
                                    agent_info: Some(AGENT_INFO.into()),
                                };
                                *negotiated_capabilities.lock() = caps.clone();
                                let _ = frame_tx.send(Outbound::Frame(reply_frame)).await;

                                // Start the slot feed from the handshake, for the same reason the
                                // Kotlin agent does: the negotiated set is only known once the
                                // hello lands. Guarded on the task rather than on "first hello" so
                                // a repeated hello — answered idempotently by [Negotiation] — never
                                // starts a second feed.
                                if slot_feed.is_none()
                                    && caps.contains(capabilities::CONNECTION_SLOTS)
                                {
                                    slot_feed = Some(Self::spawn_slot_state_feed(
                                        registry.clone(),
                                        event_tx.clone(),
                                    ));
                                }
                            }
                            Frame::Command { cid, op } => {
                                // Execute each command on its own task so a slow op (e.g. a
                                // multi-second connect) can't head-of-line block this client's
                                // other commands. Replies are cid-correlated, so out-of-order
                                // completion is fine.
                                //
                                // Cap concurrent in-flight ops: acquiring a permit here stops the
                                // read loop (TCP backpressure to the client) once the cap is hit,
                                // so a command flood can't spawn unbounded tasks. The permit is
                                // held for the op's lifetime and released when its task ends.
                                let permit = match inflight.clone().acquire_owned().await {
                                    Ok(p) => p,
                                    Err(_) => break, // semaphore closed — connection going away
                                };
                                let backend = backend.clone();
                                let registry = registry.clone();
                                let client_id = client_id.clone();
                                let event_tx = event_tx.clone();
                                let frame_tx = frame_tx.clone();
                                let translator = translator.clone();
                                let connection_live = connection_live.clone();
                                let write_tails = write_tails.clone();
                                let scan_tails = scan_tails.clone();
                                let streams = streams.clone();
                                let scan_coordinator = scan_coordinator.clone();
                                let scan_arbiter = scan_arbiter.clone();
                                let scan_bindings = scan_bindings.clone();
                                let scan_gates = scan_gates.clone();
                                let negotiated_capabilities = negotiated_capabilities.clone();
                                let status = status.clone();
                                let op = translator.to_real_op(op);
                                let write_reservation = match &op {
                                    Op::Write { device, .. } => {
                                        Some(reserve_write(&write_tails, &device.value))
                                    }
                                    _ => None,
                                };
                                let scan_reservation = match &op {
                                    Op::ScanStart { scan_id, .. } | Op::ScanStop { scan_id } => {
                                        Some(reserve_scan(&scan_tails, *scan_id))
                                    }
                                    _ => None,
                                };
                                // Admission parks this scan's mailbox; it is cleared for delivery
                                // only once the reply below is queued, so a replayed advertisement
                                // cannot overtake the reply that accepts the scan.
                                let admitted_scan = match &op {
                                    Op::ScanStart { scan_id, .. } => Some(*scan_id),
                                    _ => None,
                                };
                                command_tasks.spawn(
                                    async move {
                                        let _permit = permit;
                                        let mut write_reservation = write_reservation;
                                        let mut scan_reservation = scan_reservation;
                                        if let Some(reservation) = &mut write_reservation
                                            && let Some(predecessor) =
                                                reservation.predecessor.take()
                                        {
                                            let _ = predecessor.await;
                                        }
                                        if let Some(reservation) = &mut scan_reservation
                                            && let Some(predecessor) =
                                                reservation.predecessor.take()
                                        {
                                            let _ = predecessor.await;
                                        }
                                        let result = Self::execute_op(
                                            op,
                                            ExecuteContext {
                                                client_id: &client_id,
                                                backend: &backend,
                                                registry: &registry,
                                                translator: &translator,
                                                event_tx,
                                                connection_live: &connection_live,
                                                stream_connection,
                                                streams: &streams,
                                                scan_coordinator: &scan_coordinator,
                                                scan_mode,
                                                scan_arbiter: &scan_arbiter,
                                                scan_bindings: &scan_bindings,
                                                scan_gates: &scan_gates,
                                                negotiated_capabilities: &negotiated_capabilities,
                                                status: &status,
                                            },
                                        )
                                        .await;
                                        // Releasing the sender starts the next reserved write
                                        // before a slow client can delay it on reply backpressure.
                                        if let Some(reservation) = &write_reservation {
                                            release_write_tail(&write_tails, reservation);
                                        }
                                        if let Some(reservation) = &scan_reservation {
                                            release_scan_tail(&scan_tails, reservation);
                                        }
                                        drop(write_reservation);
                                        drop(scan_reservation);
                                        let _ = frame_tx
                                            .send(Outbound::Frame(Frame::Reply { cid, result }))
                                            .await;
                                        // Strictly after the reply is queued: events and replies
                                        // share this one FIFO, so anything the mailbox now emits
                                        // lands behind it on the wire.
                                        if let Some(scan_id) = admitted_scan {
                                            // One of these owns the scan depending on mode; the
                                            // other is a no-op.
                                            scan_bindings.release(scan_id).await;
                                            scan_gates.release(scan_id).await;
                                        }
                                    }
                                    .in_current_span(),
                                );
                            }
                            _ => {}
                        }
                    }
                    Err(e) => {
                        tracing::warn!(
                            "Failed to decode frame from client (len {}): {}",
                            data.len(),
                            e
                        );
                    }
                }
            }
        }

        // Retire this generation before touching leases, then stop and join all command work.
        // This ordering prevents a slow Connect from completing after transport cleanup and
        // cancelling the very grace timer meant to release its abandoned lease.
        connection_live.store(false, Ordering::Release);
        command_tasks.abort_all();
        while let Some(result) = command_tasks.join_next().await {
            if let Err(e) = result {
                tracing::debug!(
                    "connection command task cancelled/failed during teardown: {}",
                    e
                );
            }
        }

        scan_coordinator.detach_generation(stream_connection).await;
        for binding in scan_bindings.clear().await {
            binding.handle.close();
        }
        scan_arbiter.close();
        if let Err(e) = backend.stop_connection_streams(stream_connection).await {
            tracing::warn!("failed to stop connection-owned BLE streams: {}", e);
        }
        streams.clear();

        // Stop and join every connection-owned pump before scheduling grace. Otherwise an old
        // writer/event task can outlive cleanup and send or mutate state after a new generation
        // reconnects under the same stable identity.
        //
        // The slot feed goes first: it holds a clone of [event_tx], so the drop below cannot close
        // the event channel while it is still alive. It also outlives its client by nature — the
        // registry it watches is process-wide — which is exactly why it must be aborted here
        // rather than left to notice the socket is gone.
        if let Some(feed) = slot_feed.take() {
            feed.abort();
            let _ = feed.await;
        }
        drop(event_tx);
        drop(frame_tx);
        send_task.abort();
        event_task.abort();
        let _ = send_task.await;
        let _ = event_task.await;

        // Transport gone: keep this client's links warm and let the registry release them on
        // grace-expiry (a reconnect within the window resumes).
        registry.on_transport_drop(&client_id);
        // Release only the generation that acquired this client identity. Keeping the identity
        // claimed through lease cleanup ensures a reconnect cannot race an older socket's drop.
        live_sessions.release(&client_id, session_generation);
        tracing::info!("Client disconnected");
    }

    /// Streams `AgentEvent::SlotState` to a client that negotiated `slots`, starting with the
    /// current value.
    ///
    /// Two things this deliberately is not. It is not per session: the count comes from the
    /// process-wide registry, so it spans every client's leases against the host's capacity, and a
    /// client learns that a peripheral is unavailable because *someone* holds it — including
    /// itself, between two invocations of a process-per-command tool. And it is not driven from
    /// the connect/disconnect paths: a `watch` hands its current value to a new receiver, so a
    /// client that negotiates `slots` and asks nothing else still gets an answer instead of
    /// waiting for a connection count to move.
    ///
    /// Events go through [event_tx] rather than straight to the frame channel so they inherit the
    /// same shed-on-overflow policy as every other event: slot state is refreshable by nature, and
    /// a slow client must never be able to block the radio.
    fn spawn_slot_state_feed(
        registry: PeripheralRegistry,
        event_tx: mpsc::Sender<AgentEvent>,
    ) -> tokio::task::JoinHandle<()> {
        tokio::spawn(async move {
            let mut occupancy = registry.occupancy();
            let total = registry.total_slots();
            loop {
                let occupied = *occupancy.borrow_and_update();
                let event = AgentEvent::SlotState {
                    free: total.saturating_sub(occupied) as i32,
                    total: total as i32,
                };
                if event_tx.send(event).await.is_err() {
                    break; // the connection's event pump is gone
                }
                if occupancy.changed().await.is_err() {
                    break; // the registry outlives every connection, so this is shutdown
                }
            }
        })
    }

    /// Builds this caller's view of the agent for `Op::AgentStatus`.
    ///
    /// Disclosure is decided here rather than at the client, because only the agent knows who is
    /// asking: an ordinary caller sees the leases its own session key holds, and everything else
    /// becomes `other_leases` plus the aggregate slot count — enough to answer "can I connect?"
    /// without naming another tenant. A caller with operator scope sees every lease and its holder.
    ///
    /// Mirrors `BleAgent.agentStatus()` field for field; the two agents answering the same question
    /// differently is the divergence this whole op exists to remove.
    async fn agent_status(
        client_id: &str,
        registry: &PeripheralRegistry,
        translator: &Arc<HandleTranslator>,
        scan_mode: ScanConcurrencyMode,
        status: &StatusContext,
    ) -> AgentStatusDto {
        let settings = registry.settings();
        let leases = registry.snapshot();
        let total = registry.total_slots();
        let occupied = leases.len();
        let visible: Vec<_> = leases
            .iter()
            .filter(|lease| status.operator_scope || lease.owner == client_id)
            .collect();
        AgentStatusDto {
            agent_info: Some(AGENT_INFO.to_string()),
            protocol_version: PROTOCOL_VERSION,
            uptime_ms: status.source.started_at.elapsed().as_millis() as i64,
            settings: StatusSettingsDto {
                lease_grace_ms: settings.lease_grace.as_millis() as i64,
                transport_grace_ms: settings.transport_grace.as_millis() as i64,
                exclusive_by_default: settings.default_exclusive,
                scan_concurrency: scan_mode.as_str().to_string(),
                strict_identifiers: status.source.strict_identifiers.load(Ordering::Relaxed),
                write_policy_enforced: status.source.write_policy.enforced(),
            },
            slots: StatusSlotsDto {
                free: total.saturating_sub(occupied) as i32,
                total: total as i32,
            },
            connected_clients: status.source.live_sessions.len() as i32,
            leases: visible
                .iter()
                .map(|lease| LeaseStatusDto {
                    handle: translator.to_client(&lease.handle),
                    name: status.source.device_names.get(&lease.handle),
                    holder: Some(lease_disclosure::holder_label(
                        &lease.owner,
                        client_id,
                        status.operator_scope,
                    )),
                    mine: lease.owner == client_id,
                    connected: lease.connected,
                    in_grace: lease.in_grace,
                    remaining_grace_ms: lease.remaining_grace_ms,
                })
                .collect(),
            other_leases: (leases.len() - visible.len()) as i32,
            operator_scope: status.operator_scope,
        }
    }

    /// The error for a write the policy refused. Never enumerates the policy — a refused caller
    /// learns that it was refused, not the shape of the allowlist.
    ///
    /// Gated on `write.policy` for the same reason `RADIO_OFF` needs `radio.state`: `ErrorKind`
    /// serializes by name, and an unknown name would fail a v1 client's decode. A client that has
    /// not negotiated this receives `INVALID_REQUEST` instead — the same kind an over-limit write
    /// already returns.
    fn policy_error(negotiated: &std::collections::BTreeSet<String>, message: &str) -> AgentError {
        let kind = if negotiated.contains(capabilities::WRITE_POLICY) {
            ErrorKind::PolicyDenied
        } else {
            ErrorKind::InvalidRequest
        };
        AgentError::new(kind, Some(message.to_string()))
    }

    async fn execute_op(op: Op, context: ExecuteContext<'_>) -> OpResult {
        let ExecuteContext {
            client_id,
            backend,
            registry,
            translator,
            event_tx,
            connection_live,
            stream_connection,
            streams,
            scan_coordinator,
            scan_mode,
            scan_arbiter,
            scan_bindings,
            scan_gates,
            negotiated_capabilities,
            status,
        } = context;
        // What this caller may be told when a lease refuses it. Computed once, because the gate
        // must not vary between the eleven sites below that can return `PERIPHERAL_BUSY`.
        let disclosure = lease_disclosure::DisclosureScope {
            operator: status.operator_scope,
            structured: negotiated_capabilities
                .lock()
                .contains(capabilities::LEASE_HOLDER),
        };
        match op {
            // Names no device, so there is nothing to authorize against a lease: what a caller may
            // see is decided inside, by who it is.
            Op::AgentStatus => OpResult::ok(Some(ResultPayload::Status {
                status: Self::agent_status(client_id, registry, translator, scan_mode, status)
                    .await,
            })),
            Op::ScanStart { filters, .. } if filters.len() > MAX_SCAN_FILTERS => {
                OpResult::err(AgentError::new(
                    ErrorKind::InvalidRequest,
                    Some(format!(
                        "at most {MAX_SCAN_FILTERS} scan filters are allowed"
                    )),
                ))
            }
            Op::Write { value, .. } if value.len() > MAX_WRITE_BYTES => {
                OpResult::err(AgentError::new(
                    ErrorKind::InvalidRequest,
                    Some(format!("write payload exceeds {MAX_WRITE_BYTES} bytes")),
                ))
            }
            Op::WriteDescriptor { value, .. } if value.len() > MAX_WRITE_BYTES => {
                OpResult::err(AgentError::new(
                    ErrorKind::InvalidRequest,
                    Some(format!(
                        "descriptor payload exceeds {MAX_WRITE_BYTES} bytes"
                    )),
                ))
            }
            Op::RequestMtu { mtu, .. } if !(MIN_MTU..=MAX_MTU).contains(&mtu) => {
                OpResult::err(AgentError::new(
                    ErrorKind::InvalidRequest,
                    Some(format!(
                        "requested MTU must be between {MIN_MTU} and {MAX_MTU}"
                    )),
                ))
            }
            Op::ScanStart { scan_id, filters }
                if scan_mode != ScanConcurrencyMode::Uncontrolled =>
            {
                let (delivery, handle) = scan_arbiter.register_parked(scan_id);
                let admission = match scan_coordinator
                    .start_or_replace(
                        client_id.to_string(),
                        scan_id,
                        stream_connection,
                        filters,
                        delivery,
                    )
                    .await
                {
                    Ok(admission) => admission,
                    Err(error) => {
                        handle.close();
                        return OpResult::err(error);
                    }
                };
                match admission {
                    ScanAdmission::Accepted(registration) => {
                        scan_bindings
                            .replace(
                                scan_id,
                                ScanBinding {
                                    registration,
                                    handle,
                                },
                            )
                            .await;
                        OpResult::ok(None)
                    }
                    ScanAdmission::LimitExceeded => {
                        handle.close();
                        OpResult::err(AgentError::new(
                            ErrorKind::InvalidRequest,
                            Some(format!(
                                "at most {MAX_ACTIVE_SCANS} active scans are allowed"
                            )),
                        ))
                    }
                    ScanAdmission::SingleOccupied => {
                        handle.close();
                        let kind = if negotiated_capabilities
                            .lock()
                            .contains(crate::protocol::frame::capabilities::SCAN_CONCURRENCY_SINGLE)
                        {
                            ErrorKind::ScanUnavailable
                        } else {
                            ErrorKind::AgentBusy
                        };
                        OpResult::err(AgentError::new(
                            kind,
                            Some("the agent-wide scan slot is held".into()),
                        ))
                    }
                }
            }
            Op::ScanStart { scan_id, filters } => {
                let stream = StreamKey {
                    connection: stream_connection,
                    local_id: scan_id,
                };
                let inserted = match streams.reserve_scan(stream) {
                    Ok(inserted) => inserted,
                    Err(error) => return OpResult::err(error),
                };
                // The backend streams results as soon as it has them, so hand it a gated sender:
                // nothing reaches the connection's event channel until the command loop releases
                // it, immediately after this op's reply is queued.
                let gated = scan_gates.gated(scan_id, event_tx).await;
                let result = backend.start_scan(stream, filters, gated).await;
                if result.is_err() {
                    // Never released, so the forwarder retires without delivering.
                    scan_gates.discard(scan_id).await;
                    if inserted {
                        streams.release_scan(stream);
                    }
                }
                OpResult::from_unit(result)
            }
            Op::ScanStop { scan_id } if scan_mode != ScanConcurrencyMode::Uncontrolled => {
                if let Some(binding) = scan_bindings.remove(scan_id).await {
                    scan_coordinator.stop(&binding.registration).await;
                    binding.handle.close();
                }
                OpResult::ok(None)
            }
            Op::ScanStop { scan_id } => {
                let stream = StreamKey {
                    connection: stream_connection,
                    local_id: scan_id,
                };
                let result = backend.stop_scan(stream).await;
                if result.is_ok() {
                    streams.release_scan(stream);
                }
                OpResult::from_unit(result)
            }
            Op::Connect { device } => {
                let acquisition = match registry.acquire_lease(&device.value, client_id, disclosure)
                {
                    Ok(acquisition) => acquisition,
                    Err(e) => return OpResult::err(e),
                };
                // Resuming a lease whose radio link never dropped: the peripheral is already
                // connected, so driving the backend again would re-connect a live link. The
                // per-connection state cannot answer this — a resuming client is by definition a
                // new connection — only the registry, which outlives the transport, can.
                if acquisition == LeaseAcquisition::ResumedWarmLink {
                    tracing::info!(
                        "warm lease resumed, skipping connect [dev={}]",
                        device.value
                    );
                    // The backend emits this on a real connect; on the skip path it is ours to
                    // send, so a resuming client sees the same thing either way.
                    let _ = event_tx
                        .send(AgentEvent::ConnectionState {
                            device: device.clone(),
                            state: BleConnState::Connected,
                            reason: None,
                        })
                        .await;
                    return OpResult::ok(None);
                }
                match backend.connect(&device, event_tx).await {
                    Ok(_) => {
                        // `connect` can finish after the socket generation was retired. Do not
                        // commit the lease in that case; unwind the radio link instead.
                        if !connection_live.load(Ordering::Acquire) {
                            let _ = backend.disconnect(&device).await;
                            registry.release_lease(&device.value, client_id);
                            return OpResult::err(AgentError::new(
                                ErrorKind::TransportLost,
                                Some("connection closed before connect completed".into()),
                            ));
                        }
                        // Mark the lease physically connected and cancel any pending release
                        // (e.g. a reconnect resuming within the transport-grace window).
                        registry.on_connected(&device.value, client_id);
                        OpResult::ok(None)
                    }
                    Err(e) => {
                        // Don't leak the lease we just took if the radio never connected.
                        registry.release_lease(&device.value, client_id);
                        OpResult::err(e)
                    }
                }
            }
            Op::Disconnect { device } => {
                if let Err(e) = registry.authorize_connected(&device.value, client_id, disclosure) {
                    return OpResult::err(e);
                }
                registry.release_lease(&device.value, client_id);
                translator.evict(&device.value);
                OpResult::from_unit(backend.disconnect(&device).await)
            }
            Op::Discover { device } => {
                if let Err(e) = registry.authorize_connected(&device.value, client_id, disclosure) {
                    return OpResult::err(e);
                }
                OpResult::from_payload(backend.discover(&device).await)
            }
            Op::Read { device, char } => {
                if let Err(e) = registry.authorize_connected(&device.value, client_id, disclosure) {
                    return OpResult::err(e);
                }
                OpResult::from_payload(backend.read(&device, &char).await)
            }
            Op::Write {
                device,
                char,
                value,
                with_response,
            } => {
                if let Err(e) = registry.authorize_connected(&device.value, client_id, disclosure) {
                    return OpResult::err(e);
                }
                let principal = write_policy::principal_of(client_id);
                if !status.source.write_policy.authorizes_write(
                    principal,
                    &char.service,
                    &char.characteristic,
                    value.len(),
                    with_response,
                ) {
                    return OpResult::err(Self::policy_error(
                        &negotiated_capabilities.lock(),
                        "write not permitted for this principal",
                    ));
                }
                OpResult::from_unit(backend.write(&device, &char, &value, with_response).await)
            }
            Op::ReadDescriptor { device, desc } => {
                if let Err(e) = registry.authorize_connected(&device.value, client_id, disclosure) {
                    return OpResult::err(e);
                }
                OpResult::from_payload(backend.read_descriptor(&device, &desc).await)
            }
            Op::WriteDescriptor {
                device,
                desc,
                value,
            } => {
                if let Err(e) = registry.authorize_connected(&device.value, client_id, disclosure) {
                    return OpResult::err(e);
                }
                let principal = write_policy::principal_of(client_id);
                if !status.source.write_policy.authorizes_descriptor_write(
                    principal,
                    &desc.service,
                    &desc.characteristic,
                    &desc.descriptor,
                    value.len(),
                ) {
                    return OpResult::err(Self::policy_error(
                        &negotiated_capabilities.lock(),
                        "descriptor write not permitted for this principal",
                    ));
                }
                OpResult::from_unit(backend.write_descriptor(&device, &desc, &value).await)
            }
            Op::RequestMtu { device, mtu } => {
                if let Err(e) = registry.authorize_connected(&device.value, client_id, disclosure) {
                    return OpResult::err(e);
                }
                OpResult::from_payload(backend.request_mtu(&device, mtu).await)
            }
            Op::ObserveStart {
                sub_id,
                device,
                char,
            } => {
                if let Err(e) = registry.authorize_connected(&device.value, client_id, disclosure) {
                    return OpResult::err(e);
                }
                let stream = StreamKey {
                    connection: stream_connection,
                    local_id: sub_id,
                };
                let inserted = match streams.reserve_observation(stream) {
                    Ok(inserted) => inserted,
                    Err(error) => return OpResult::err(error),
                };
                let result = backend
                    .start_observe(stream, &device, &char, event_tx)
                    .await;
                if result.is_err() && inserted {
                    streams.release_observation(stream);
                }
                OpResult::from_unit(result)
            }
            Op::ObserveStop { sub_id } => {
                let stream = StreamKey {
                    connection: stream_connection,
                    local_id: sub_id,
                };
                let result = backend.stop_observe(stream).await;
                if result.is_ok() {
                    streams.release_observation(stream);
                }
                OpResult::from_unit(result)
            }
            // Pairing itself is unimplemented on every reference agent (parity: neither JVM, Android,
            // nor Rust advertises `pairing`), so these two answer UNSUPPORTED exactly like the
            // catch-all below — but with an explicit policy check first, ahead of that answer,
            // keeping this agent structurally parallel with the Kotlin one (whose common `BleAgent`
            // dispatches Pair/Unpair for every Kotlin target, including Android/iOS backends that
            // may implement bonding) for whenever pairing lands on either.
            Op::Pair { device } | Op::Unpair { device } => {
                if let Err(e) = registry.authorize_connected(&device.value, client_id, disclosure) {
                    return OpResult::err(e);
                }
                let principal = write_policy::principal_of(client_id);
                if !status.source.write_policy.authorizes_pairing(principal) {
                    return OpResult::err(Self::policy_error(
                        &negotiated_capabilities.lock(),
                        "pairing not permitted for this principal",
                    ));
                }
                OpResult::err(AgentError::new(
                    ErrorKind::Unsupported,
                    Some("Operation not supported on this agent".into()),
                ))
            }
            // Ops this agent does not implement. Authorization still runs first when the op names a
            // device: a client that does not own it must get the same PERIPHERAL_BUSY the supported
            // ops give, not an answer about the agent's capabilities. Answering UNSUPPORTED first
            // was a real divergence from the Kotlin agent, whose `BleAgent` authorizes in every
            // device-bearing branch (found by Rig A case 3, 2026-07-28).
            unsupported => {
                if let Some(device) = unsupported.device_handle()
                    && let Err(e) =
                        registry.authorize_connected(&device.value, client_id, disclosure)
                {
                    return OpResult::err(e);
                }
                OpResult::err(AgentError::new(
                    ErrorKind::Unsupported,
                    Some("Operation not supported on this agent".into()),
                ))
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::protocol::{
        op::{CharRef, ConnProfile, DescRef, DeviceHandle, ScanFilter},
        results::ResultPayload,
    };
    use crate::registry::peripheral_lease::LeaseConfig;
    use async_trait::async_trait;
    use parking_lot::Mutex;
    use std::io;
    use std::pin::Pin;
    use std::sync::atomic::AtomicUsize;
    use std::task::{Context, Poll};
    use tokio::io::{AsyncRead, AsyncWrite, ReadBuf};

    #[test]
    fn credentials_select_named_principals_and_scope_session_keys() {
        let credentials = HashMap::from([
            ("alpha".to_string(), "secret-a".to_string()),
            ("beta".to_string(), "secret-b".to_string()),
        ]);
        let revoked = parking_lot::Mutex::new(HashSet::new());
        assert_eq!(
            authenticate(&credentials, &revoked, Some("Bearer secret-a")),
            Some("alpha".to_string())
        );
        assert_eq!(
            authenticate(&credentials, &revoked, Some("Bearer wrong")),
            None
        );
        assert_ne!(
            session_key("alpha", "client"),
            session_key("beta", "client")
        );
    }

    #[test]
    fn live_session_release_cannot_retire_a_newer_generation() {
        let sessions = LiveSessionRegistry::default();
        let client = session_key("alpha", "same-client");

        assert!(sessions.try_acquire(&client, 1));
        assert!(!sessions.try_acquire(&client, 2));
        sessions.release(&client, 2);
        assert!(!sessions.try_acquire(&client, 3));
        sessions.release(&client, 1);
        assert!(sessions.try_acquire(&client, 3));
    }

    #[test]
    fn stream_reservations_cap_new_ids_and_allow_replacement() {
        let streams = StreamReservations::default();
        for local_id in 0..MAX_ACTIVE_SCANS as i64 {
            assert!(
                streams
                    .reserve_scan(StreamKey {
                        connection: 1,
                        local_id,
                    })
                    .unwrap()
            );
        }
        assert!(
            !streams
                .reserve_scan(StreamKey {
                    connection: 1,
                    local_id: 0,
                })
                .unwrap()
        );
        assert!(
            streams
                .reserve_scan(StreamKey {
                    connection: 1,
                    local_id: MAX_ACTIVE_SCANS as i64,
                })
                .is_err()
        );
        streams.release_scan(StreamKey {
            connection: 1,
            local_id: 0,
        });
        assert!(
            streams
                .reserve_scan(StreamKey {
                    connection: 1,
                    local_id: MAX_ACTIVE_SCANS as i64,
                })
                .unwrap()
        );
    }

    /// LIMIT-SLOW-01: the outbound frame channel — what a stalled client or an event flood fills
    /// up — is bounded (`FRAME_CHANNEL_CAP`) and sheds with `TrySendError::Full` rather than
    /// growing or blocking the sender, exactly as `handle_connection`'s event pump
    /// (`frame_tx_event.try_send(..)`, above) and the notification pump
    /// (`ble::btleplug_impl::start_observe`) both rely on. Draining one slot immediately makes
    /// room again, so the bound is stable rather than a one-shot trip.
    #[test]
    fn outbound_frame_channel_sheds_on_overflow_instead_of_growing_or_blocking() {
        let (tx, mut rx) = mpsc::channel::<Outbound>(FRAME_CHANNEL_CAP);
        let event = || {
            Outbound::Frame(Frame::Event {
                event: AgentEvent::Notification {
                    sub_id: 0,
                    value: Vec::new(),
                },
            })
        };

        for _ in 0..FRAME_CHANNEL_CAP {
            tx.try_send(event()).expect("must accept up to capacity");
        }
        assert!(matches!(
            tx.try_send(event()),
            Err(mpsc::error::TrySendError::Full(_))
        ));

        rx.try_recv().expect("draining one slot frees capacity");
        tx.try_send(event())
            .expect("a freed slot must accept the next frame immediately");
    }

    #[test]
    fn failed_auth_limiter_caps_a_peer_and_rate_limits_its_logs() {
        let limiter = AuthFailureLimiter::default();
        let peer = "127.0.0.1".parse().unwrap();

        for _ in 0..MAX_AUTH_FAILURES_PER_PEER {
            assert!(limiter.record_failure(peer).allowed);
        }
        let limited = limiter.record_failure(peer);
        assert!(!limited.allowed);
        assert!(limited.should_log);
        assert!(!limiter.record_failure(peer).should_log);
    }

    #[tokio::test]
    async fn oversized_websocket_message_is_rejected_by_the_framing_layer() {
        let (server_io, client_io) = tokio::io::duplex(MAX_FRAME_BYTES + 4096);
        let server = tokio::spawn(async move {
            tokio_tungstenite::accept_async_with_config(server_io, Some(websocket_config()))
                .await
                .unwrap()
        });
        let (mut client, _) = tokio_tungstenite::client_async("ws://localhost/agent", client_io)
            .await
            .unwrap();
        let mut server = server.await.unwrap();

        client
            .send(Message::Binary(vec![0; MAX_FRAME_BYTES + 1].into()))
            .await
            .unwrap();
        assert!(
            server
                .next()
                .await
                .expect("peer must produce a frame result")
                .is_err()
        );
    }

    /// LEASE-DUPLICATE-01 (operational): a second live generation for the same
    /// `(principal, stable client id)` is refused pre-upgrade with HTTP 409, and the incumbent's
    /// generation is left untouched. Drives `AgentServer::accept_connection` — the exact
    /// handshake path `run()` uses for every accepted socket — over two in-memory duplex pairs so
    /// no real TCP port is required. See docs/conformance/0.9.1-scenarios.md for the Kotlin
    /// counterpart (`WebSocketEndToEndTest.rejectsASecondLiveSocketForTheSameStableClientIdentity`),
    /// which observes the equivalent refusal as a post-upgrade close(1008) — the two agents signal
    /// the refusal differently but both leave the incumbent generation untouched.
    #[tokio::test]
    async fn duplicate_live_generation_is_rejected_with_409_and_leaves_the_incumbent_untouched() {
        use tokio_tungstenite::tungstenite::client::IntoClientRequest;

        let backend: Arc<dyn BleBackend> = Arc::new(FakeBackend::default());
        let registry = PeripheralRegistry::new(LeaseConfig::default());
        let credentials = Arc::new(HashMap::from([(
            "alpha".to_string(),
            "secret-a".to_string(),
        )]));
        let strict = Arc::new(AtomicBool::new(false));
        let live_sessions = Arc::new(LiveSessionRegistry::default());
        let failed_auth_limiter = Arc::new(AuthFailureLimiter::default());
        let revoked_principals = Arc::new(parking_lot::Mutex::new(HashSet::new()));

        let request = || {
            let mut request = "ws://localhost/agent".into_client_request().unwrap();
            request
                .headers_mut()
                .insert("Authorization", "Bearer secret-a".parse().unwrap());
            request
                .headers_mut()
                .insert("X-RemoteBle-Client", "device-1".parse().unwrap());
            request
        };

        let (first_server_io, first_client_io) = tokio::io::duplex(4096);
        let first_peer: SocketAddr = "127.0.0.1:1".parse().unwrap();
        let first_accept = tokio::spawn(AgentServer::accept_connection(
            first_server_io,
            first_peer,
            backend.clone(),
            registry.clone(),
            credentials.clone(),
            strict.clone(),
            live_sessions.clone(),
            failed_auth_limiter.clone(),
            revoked_principals.clone(),
            None,
        ));
        let (first_client, _) = tokio_tungstenite::client_async(request(), first_client_io)
            .await
            .expect("first connection must be accepted");

        let (second_server_io, second_client_io) = tokio::io::duplex(4096);
        let second_peer: SocketAddr = "127.0.0.1:2".parse().unwrap();
        let second_accept = tokio::spawn(AgentServer::accept_connection(
            second_server_io,
            second_peer,
            backend.clone(),
            registry.clone(),
            credentials.clone(),
            strict.clone(),
            live_sessions.clone(),
            failed_auth_limiter.clone(),
            revoked_principals.clone(),
            None,
        ));
        let second_err = tokio_tungstenite::client_async(request(), second_client_io)
            .await
            .expect_err("duplicate live generation must be refused pre-upgrade");
        match second_err {
            tokio_tungstenite::tungstenite::Error::Http(response) => {
                assert_eq!(response.status(), http::StatusCode::CONFLICT);
            }
            other => panic!("expected an HTTP 409 handshake rejection, got {other:?}"),
        }

        // The incumbent's generation is untouched: the same (principal, client id) key still
        // reports as held by the first connection, so a third acquire attempt is refused too.
        assert!(!live_sessions.try_acquire(&session_key("alpha", "device-1"), u64::MAX));

        drop(first_client);
        first_accept
            .await
            .expect("first connection task must not panic");
        second_accept
            .await
            .expect("second connection task must not panic");
    }

    async fn send_command<S>(ws: &mut tokio_tungstenite::WebSocketStream<S>, cid: i64, op: Op)
    where
        S: tokio::io::AsyncRead + tokio::io::AsyncWrite + Unpin,
    {
        let bytes = encode_cbor(&Frame::Command { cid, op }).expect("command must encode");
        ws.send(Message::Binary(bytes.into()))
            .await
            .expect("command must send");
    }

    async fn recv_frame<S>(ws: &mut tokio_tungstenite::WebSocketStream<S>) -> Frame
    where
        S: tokio::io::AsyncRead + tokio::io::AsyncWrite + Unpin,
    {
        loop {
            let msg = ws
                .next()
                .await
                .expect("stream ended before a protocol frame arrived")
                .expect("websocket read must not error");
            if msg.is_binary() {
                return decode_cbor(&msg.into_data()).expect("protocol frame must decode");
            }
        }
    }

    async fn recv_reply<S>(ws: &mut tokio_tungstenite::WebSocketStream<S>) -> OpResult
    where
        S: tokio::io::AsyncRead + tokio::io::AsyncWrite + Unpin,
    {
        loop {
            if let Frame::Reply { result, .. } = recv_frame(ws).await {
                return result;
            }
        }
    }

    /// The next `SlotState` event as `(free, total)`.
    async fn recv_slot_state<S>(ws: &mut tokio_tungstenite::WebSocketStream<S>) -> (i32, i32)
    where
        S: tokio::io::AsyncRead + tokio::io::AsyncWrite + Unpin,
    {
        loop {
            if let Frame::Event {
                event: AgentEvent::SlotState { free, total },
            } = recv_frame(ws).await
            {
                return (free, total);
            }
        }
    }

    struct ScanWsClient {
        ws: tokio_tungstenite::WebSocketStream<tokio::io::DuplexStream>,
        accept_task: tokio::task::JoinHandle<()>,
    }

    impl ScanWsClient {
        async fn close(self) {
            drop(self.ws);
            let _ = self.accept_task.await;
        }
    }

    struct ScanWsHarness {
        fake: Arc<FakeBackend>,
        backend: Arc<dyn BleBackend>,
        registry: PeripheralRegistry,
        credentials: Arc<HashMap<String, String>>,
        strict: Arc<AtomicBool>,
        live_sessions: Arc<LiveSessionRegistry>,
        failed_auth_limiter: Arc<AuthFailureLimiter>,
        revoked_principals: Arc<parking_lot::Mutex<HashSet<String>>>,
        scan_coordinator: ScanCoordinator,
        scan_mode: ScanConcurrencyMode,
        status_source: Arc<StatusSource>,
        /// Configured operator secret, if this harness's agent has one. Per-client scope is chosen
        /// by whether [ScanWsHarness::operator_client] sends it.
        operator_token: Option<String>,
    }

    impl ScanWsHarness {
        fn new(scan_mode: ScanConcurrencyMode, transport_grace: Duration) -> Self {
            let fake = Arc::new(FakeBackend::default());
            let backend: Arc<dyn BleBackend> = fake.clone();
            let registry = PeripheralRegistry::new(LeaseConfig::default());
            let scan_coordinator = ScanCoordinator::new(
                backend.clone(),
                scan_mode,
                transport_grace,
                MAX_ACTIVE_SCANS,
            );
            let strict = Arc::new(AtomicBool::new(false));
            let live_sessions = Arc::new(LiveSessionRegistry::default());
            Self {
                fake,
                backend,
                registry,
                credentials: Arc::new(HashMap::new()),
                strict: strict.clone(),
                live_sessions: live_sessions.clone(),
                failed_auth_limiter: Arc::new(AuthFailureLimiter::default()),
                revoked_principals: Arc::new(parking_lot::Mutex::new(HashSet::new())),
                scan_coordinator,
                scan_mode,
                status_source: Arc::new(StatusSource {
                    started_at: Instant::now(),
                    strict_identifiers: strict,
                    live_sessions,
                    device_names: Arc::new(DeviceNames::default()),
                    write_policy: Arc::new(WritePolicy::permissive()),
                }),
                operator_token: None,
            }
        }

        /// Replaces the write policy every subsequent [Self::client]/[Self::client_with_operator]
        /// connection sees. Rebuilds [Self::status_source] rather than mutating a field through the
        /// `Arc`, since every other field is unaffected.
        fn set_write_policy(&mut self, policy: WritePolicy) {
            self.status_source = Arc::new(StatusSource {
                write_policy: Arc::new(policy),
                ..(*self.status_source).clone()
            });
        }

        fn set_credentials(&mut self, credentials: HashMap<String, String>) {
            self.credentials = Arc::new(credentials);
        }

        async fn client(
            &self,
            client_id: &str,
            capabilities: &[&str],
            expected_server_capabilities: &[&str],
        ) -> ScanWsClient {
            self.client_with_operator(client_id, capabilities, expected_server_capabilities, None)
                .await
        }

        /// As [Self::client], but presenting `operator` on the upgrade. `Some` with the wrong
        /// secret is a supported case on purpose: it must connect at normal scope, not fail.
        async fn client_with_operator(
            &self,
            client_id: &str,
            capabilities: &[&str],
            expected_server_capabilities: &[&str],
            operator: Option<&str>,
        ) -> ScanWsClient {
            self.client_with_headers(
                client_id,
                capabilities,
                expected_server_capabilities,
                None,
                operator,
            )
            .await
        }

        async fn client_with_bearer(
            &self,
            client_id: &str,
            capabilities: &[&str],
            expected_server_capabilities: &[&str],
            bearer: &str,
        ) -> ScanWsClient {
            self.client_with_headers(
                client_id,
                capabilities,
                expected_server_capabilities,
                Some(bearer),
                None,
            )
            .await
        }

        async fn client_with_headers(
            &self,
            client_id: &str,
            capabilities: &[&str],
            expected_server_capabilities: &[&str],
            bearer: Option<&str>,
            operator: Option<&str>,
        ) -> ScanWsClient {
            use tokio_tungstenite::tungstenite::client::IntoClientRequest;

            let (server_io, client_io) = tokio::io::duplex(65_536);
            let accept_task = tokio::spawn(AgentServer::accept_connection_with_scan(
                server_io,
                "127.0.0.1:1".parse().unwrap(),
                self.backend.clone(),
                self.registry.clone(),
                self.credentials.clone(),
                self.strict.clone(),
                self.live_sessions.clone(),
                self.failed_auth_limiter.clone(),
                self.revoked_principals.clone(),
                self.scan_coordinator.clone(),
                self.scan_mode,
                self.status_source.clone(),
                self.operator_token.clone(),
            ));
            let mut request = "ws://localhost/agent".into_client_request().unwrap();
            request.headers_mut().insert(
                "X-RemoteBle-Client",
                client_id.parse().expect("client id header must be valid"),
            );
            if let Some(bearer) = bearer {
                request.headers_mut().insert(
                    "Authorization",
                    format!("Bearer {bearer}")
                        .parse()
                        .expect("authorization header must be valid"),
                );
            }
            if let Some(operator) = operator {
                request.headers_mut().insert(
                    OPERATOR_HEADER,
                    format!("Bearer {operator}")
                        .parse()
                        .expect("operator header must be valid"),
                );
            }
            let (mut ws, _) = tokio_tungstenite::client_async(request, client_io)
                .await
                .expect("scan client must complete the WebSocket handshake");
            let offered = capabilities.iter().map(|cap| (*cap).to_string()).collect();
            ws.send(Message::Binary(
                encode_cbor(&Frame::ClientHello {
                    min_version: 1,
                    max_version: 1,
                    capabilities: offered,
                    identifier_format: None,
                })
                .expect("hello must encode")
                .into(),
            ))
            .await
            .expect("hello must send");
            match recv_frame(&mut ws).await {
                Frame::ServerHello { capabilities, .. } => {
                    let expected = expected_server_capabilities
                        .iter()
                        .map(|cap| (*cap).to_string())
                        .collect();
                    assert_eq!(capabilities, expected);
                }
                other => panic!("expected server hello, got {other:?}"),
            }
            ScanWsClient { ws, accept_task }
        }

        async fn wait_for_scan_start(&self, count: usize) {
            tokio::time::timeout(Duration::from_secs(1), async {
                loop {
                    if self.fake.scan_filters.lock().len() >= count {
                        break;
                    }
                    tokio::task::yield_now().await;
                }
            })
            .await
            .expect("coordinator must start a physical scan");
        }
    }

    async fn recv_scan_result(
        ws: &mut tokio_tungstenite::WebSocketStream<tokio::io::DuplexStream>,
        scan_id: i64,
    ) -> crate::protocol::events::AdvertisementDto {
        loop {
            if let Frame::Event { event } = recv_frame(ws).await {
                match event {
                    AgentEvent::ScanResult {
                        scan_id: event_scan_id,
                        advertisement,
                    } if event_scan_id == scan_id => return advertisement,
                    AgentEvent::ScanResultBatch {
                        scan_id: event_scan_id,
                        mut advertisements,
                    } if event_scan_id == scan_id => {
                        return advertisements
                            .drain(..)
                            .next()
                            .expect("scan batch must not be empty");
                    }
                    _ => {}
                }
            }
        }
    }

    fn test_advertisement(
        device: &str,
        service: Option<&str>,
        name: Option<&str>,
        rssi: i32,
    ) -> crate::protocol::events::AdvertisementDto {
        crate::protocol::events::AdvertisementDto {
            device: DeviceHandle {
                value: device.to_string(),
            },
            name: name.map(str::to_string),
            rssi,
            service_uuids: service.into_iter().map(str::to_string).collect(),
            manufacturer_data: Default::default(),
        }
    }

    #[tokio::test]
    async fn an_uncontrolled_scan_gate_withholds_results_until_the_reply_is_queued() {
        // Uncontrolled scans bypass the arbiter and stream straight from the backend, so they carry
        // the acknowledge-before-deliver guarantee here instead of in a parked mailbox. Parity with
        // the guaranteed modes and with the Kotlin agent, which defers delivery in every mode.
        let (event_tx, mut event_rx) = mpsc::channel(8);
        let gates = ScanGates::default();
        let gated = gates.gated(7, event_tx).await;

        gated
            .send(AgentEvent::ScanResult {
                scan_id: 7,
                advertisement: test_advertisement("dev", None, None, -50),
            })
            .await
            .unwrap();
        tokio::time::sleep(Duration::from_millis(50)).await;
        assert!(
            event_rx.try_recv().is_err(),
            "a gated scan must not deliver before its scan.start reply is written",
        );

        gates.release(7).await;
        let delivered = tokio::time::timeout(Duration::from_secs(2), event_rx.recv())
            .await
            .expect("release must let the withheld result through")
            .expect("event channel closed");
        match delivered {
            AgentEvent::ScanResult { scan_id, .. } => assert_eq!(scan_id, 7),
            other => panic!("expected the withheld scan result, got {other:?}"),
        }
    }

    #[tokio::test]
    async fn a_discarded_scan_gate_never_delivers() {
        // The failure path: start_scan errored, so the reply is an Err and nothing this scan
        // produced may reach the client.
        let (event_tx, mut event_rx) = mpsc::channel(8);
        let gates = ScanGates::default();
        let gated = gates.gated(7, event_tx).await;
        gated
            .send(AgentEvent::ScanResult {
                scan_id: 7,
                advertisement: test_advertisement("dev", None, None, -50),
            })
            .await
            .unwrap();

        gates.discard(7).await;
        gates.release(7).await; // the command loop releases unconditionally; must stay a no-op
        tokio::time::sleep(Duration::from_millis(50)).await;
        assert!(
            event_rx.try_recv().is_err(),
            "a discarded gate must never deliver",
        );
    }

    #[tokio::test]
    async fn scan_conc_01_different_filtered_scans_receive_only_their_own_matches() {
        let harness = ScanWsHarness::new(ScanConcurrencyMode::Multiplexed, Duration::from_secs(1));
        let mut first = harness
            .client(
                "scan-a",
                &[crate::protocol::frame::capabilities::SCAN_CONCURRENCY_MULTIPLEXED],
                &[crate::protocol::frame::capabilities::SCAN_CONCURRENCY_MULTIPLEXED],
            )
            .await;
        let mut second = harness
            .client(
                "scan-b",
                &[crate::protocol::frame::capabilities::SCAN_CONCURRENCY_MULTIPLEXED],
                &[crate::protocol::frame::capabilities::SCAN_CONCURRENCY_MULTIPLEXED],
            )
            .await;

        send_command(
            &mut first.ws,
            1,
            Op::ScanStart {
                scan_id: 1,
                filters: vec![ScanFilter {
                    name: None,
                    service: Some("180d".into()),
                }],
            },
        )
        .await;
        assert!(matches!(
            recv_reply(&mut first.ws).await,
            OpResult::Ok { .. }
        ));
        send_command(
            &mut second.ws,
            1,
            Op::ScanStart {
                scan_id: 1,
                filters: vec![ScanFilter {
                    name: None,
                    service: Some("180f".into()),
                }],
            },
        )
        .await;
        assert!(matches!(
            recv_reply(&mut second.ws).await,
            OpResult::Ok { .. }
        ));
        harness.wait_for_scan_start(1).await;

        harness
            .fake
            .emit_scan(test_advertisement("hr", Some("180d"), None, -50))
            .await;
        harness
            .fake
            .emit_scan(test_advertisement("battery", Some("180f"), None, -60))
            .await;
        assert_eq!(recv_scan_result(&mut first.ws, 1).await.device.value, "hr");
        assert_eq!(
            recv_scan_result(&mut second.ws, 1).await.device.value,
            "battery"
        );
        assert!(
            tokio::time::timeout(
                Duration::from_millis(100),
                recv_scan_result(&mut first.ws, 1),
            )
            .await
            .is_err()
        );
        assert!(
            tokio::time::timeout(
                Duration::from_millis(100),
                recv_scan_result(&mut second.ws, 1),
            )
            .await
            .is_err()
        );
        first.close().await;
        second.close().await;
    }

    #[tokio::test]
    async fn scan_conc_02_stopping_one_scan_leaves_the_survivor_on_the_same_physical_scan() {
        let harness = ScanWsHarness::new(ScanConcurrencyMode::Multiplexed, Duration::from_secs(1));
        let mut first = harness
            .client(
                "scan-a",
                &[crate::protocol::frame::capabilities::SCAN_CONCURRENCY_MULTIPLEXED],
                &[crate::protocol::frame::capabilities::SCAN_CONCURRENCY_MULTIPLEXED],
            )
            .await;
        let mut second = harness
            .client(
                "scan-b",
                &[crate::protocol::frame::capabilities::SCAN_CONCURRENCY_MULTIPLEXED],
                &[crate::protocol::frame::capabilities::SCAN_CONCURRENCY_MULTIPLEXED],
            )
            .await;
        for (ws, cid, service) in [(&mut first.ws, 1, "180d"), (&mut second.ws, 1, "180f")] {
            send_command(
                ws,
                cid,
                Op::ScanStart {
                    scan_id: 1,
                    filters: vec![ScanFilter {
                        name: None,
                        service: Some(service.into()),
                    }],
                },
            )
            .await;
            assert!(matches!(recv_reply(ws).await, OpResult::Ok { .. }));
        }
        harness.wait_for_scan_start(1).await;
        let starts_before_stop = harness.fake.scan_filters.lock().len();
        send_command(&mut first.ws, 2, Op::ScanStop { scan_id: 1 }).await;
        assert!(matches!(
            recv_reply(&mut first.ws).await,
            OpResult::Ok { .. }
        ));

        harness
            .fake
            .emit_scan(test_advertisement("battery", Some("180f"), None, -60))
            .await;
        assert_eq!(
            recv_scan_result(&mut second.ws, 1).await.device.value,
            "battery"
        );
        assert_eq!(harness.fake.scan_filters.lock().len(), starts_before_stop);
        first.close().await;
        second.close().await;
    }

    #[tokio::test]
    async fn scan_conc_03_late_join_receives_replay_within_the_window() {
        let harness = ScanWsHarness::new(ScanConcurrencyMode::Multiplexed, Duration::from_secs(1));
        let mut first = harness
            .client(
                "scan-a",
                &[crate::protocol::frame::capabilities::SCAN_CONCURRENCY_MULTIPLEXED],
                &[crate::protocol::frame::capabilities::SCAN_CONCURRENCY_MULTIPLEXED],
            )
            .await;
        send_command(
            &mut first.ws,
            1,
            Op::ScanStart {
                scan_id: 1,
                filters: vec![ScanFilter {
                    name: None,
                    service: Some("180d".into()),
                }],
            },
        )
        .await;
        assert!(matches!(
            recv_reply(&mut first.ws).await,
            OpResult::Ok { .. }
        ));
        harness.wait_for_scan_start(1).await;
        harness
            .fake
            .emit_scan(test_advertisement(
                "hr",
                Some("180d"),
                Some("Heart Rate"),
                -50,
            ))
            .await;
        assert_eq!(recv_scan_result(&mut first.ws, 1).await.device.value, "hr");

        let mut late = harness
            .client(
                "scan-b",
                &[crate::protocol::frame::capabilities::SCAN_CONCURRENCY_MULTIPLEXED],
                &[crate::protocol::frame::capabilities::SCAN_CONCURRENCY_MULTIPLEXED],
            )
            .await;
        send_command(
            &mut late.ws,
            1,
            Op::ScanStart {
                scan_id: 1,
                filters: vec![ScanFilter {
                    name: None,
                    service: Some("180d".into()),
                }],
            },
        )
        .await;
        assert!(matches!(
            recv_reply(&mut late.ws).await,
            OpResult::Ok { .. }
        ));
        assert_eq!(recv_scan_result(&mut late.ws, 1).await.device.value, "hr");
        first.close().await;
        late.close().await;
    }

    #[tokio::test]
    async fn scan_conc_04_sparse_identity_is_merged_before_logical_matching() {
        let harness = ScanWsHarness::new(ScanConcurrencyMode::Multiplexed, Duration::from_secs(1));
        let mut client = harness
            .client(
                "scan-a",
                &[crate::protocol::frame::capabilities::SCAN_CONCURRENCY_MULTIPLEXED],
                &[crate::protocol::frame::capabilities::SCAN_CONCURRENCY_MULTIPLEXED],
            )
            .await;
        send_command(
            &mut client.ws,
            1,
            Op::ScanStart {
                scan_id: 1,
                filters: vec![ScanFilter {
                    name: None,
                    service: Some("180d".into()),
                }],
            },
        )
        .await;
        assert!(matches!(
            recv_reply(&mut client.ws).await,
            OpResult::Ok { .. }
        ));
        harness.wait_for_scan_start(1).await;
        harness
            .fake
            .emit_scan(test_advertisement(
                "hr",
                Some("180d"),
                Some("Heart Rate"),
                -50,
            ))
            .await;
        let _ = recv_scan_result(&mut client.ws, 1).await;
        harness
            .fake
            .emit_scan(test_advertisement("hr", None, None, -41))
            .await;
        let sparse = recv_scan_result(&mut client.ws, 1).await;
        assert_eq!(sparse.name.as_deref(), Some("Heart Rate"));
        assert_eq!(sparse.service_uuids, vec!["180d"]);
        assert_eq!(sparse.rssi, -41);
        client.close().await;
    }

    #[tokio::test]
    async fn scan_conc_05_single_mode_refuses_a_different_key_without_disturbing_incumbent() {
        let harness = ScanWsHarness::new(ScanConcurrencyMode::Single, Duration::from_secs(1));
        let mut incumbent = harness
            .client(
                "scan-a",
                &[crate::protocol::frame::capabilities::SCAN_CONCURRENCY_SINGLE],
                &[crate::protocol::frame::capabilities::SCAN_CONCURRENCY_SINGLE],
            )
            .await;
        let mut contender = harness
            .client(
                "scan-b",
                &[crate::protocol::frame::capabilities::SCAN_CONCURRENCY_SINGLE],
                &[crate::protocol::frame::capabilities::SCAN_CONCURRENCY_SINGLE],
            )
            .await;
        send_command(
            &mut incumbent.ws,
            1,
            Op::ScanStart {
                scan_id: 1,
                filters: vec![],
            },
        )
        .await;
        assert!(matches!(
            recv_reply(&mut incumbent.ws).await,
            OpResult::Ok { .. }
        ));
        harness.wait_for_scan_start(1).await;
        send_command(
            &mut contender.ws,
            1,
            Op::ScanStart {
                scan_id: 1,
                filters: vec![],
            },
        )
        .await;
        assert!(matches!(
            recv_reply(&mut contender.ws).await,
            OpResult::Err { error } if error.kind == ErrorKind::ScanUnavailable
        ));
        harness
            .fake
            .emit_scan(test_advertisement("survivor", None, Some("survivor"), -50))
            .await;
        assert_eq!(
            recv_scan_result(&mut incumbent.ws, 1).await.device.value,
            "survivor"
        );
        incumbent.close().await;
        contender.close().await;
    }

    #[tokio::test]
    async fn scan_conc_06_reissuing_the_incumbent_key_replaces_it_atomically() {
        let harness = ScanWsHarness::new(ScanConcurrencyMode::Single, Duration::from_secs(1));
        let mut client = harness
            .client(
                "scan-a",
                &[crate::protocol::frame::capabilities::SCAN_CONCURRENCY_SINGLE],
                &[crate::protocol::frame::capabilities::SCAN_CONCURRENCY_SINGLE],
            )
            .await;
        for (cid, service) in [(1, "180d"), (2, "180f")] {
            send_command(
                &mut client.ws,
                cid,
                Op::ScanStart {
                    scan_id: 1,
                    filters: vec![ScanFilter {
                        name: None,
                        service: Some(service.into()),
                    }],
                },
            )
            .await;
            assert!(matches!(
                recv_reply(&mut client.ws).await,
                OpResult::Ok { .. }
            ));
        }
        harness.wait_for_scan_start(2).await;
        harness
            .fake
            .emit_scan(test_advertisement("battery", Some("180f"), None, -60))
            .await;
        assert_eq!(
            recv_scan_result(&mut client.ws, 1).await.device.value,
            "battery"
        );
        assert!(
            tokio::time::timeout(
                Duration::from_millis(100),
                recv_scan_result(&mut client.ws, 1),
            )
            .await
            .is_err()
        );
        client.close().await;
    }

    #[tokio::test]
    async fn pipelined_same_id_scan_lifecycle_commands_follow_receive_order() {
        let harness = ScanWsHarness::new(ScanConcurrencyMode::Multiplexed, Duration::from_secs(1));
        let mut client = harness
            .client(
                "scan-a",
                &[crate::protocol::frame::capabilities::SCAN_CONCURRENCY_MULTIPLEXED],
                &[crate::protocol::frame::capabilities::SCAN_CONCURRENCY_MULTIPLEXED],
            )
            .await;
        send_command(
            &mut client.ws,
            1,
            Op::ScanStart {
                scan_id: 1,
                filters: vec![ScanFilter {
                    name: None,
                    service: Some("180d".into()),
                }],
            },
        )
        .await;
        send_command(
            &mut client.ws,
            2,
            Op::ScanStart {
                scan_id: 1,
                filters: vec![ScanFilter {
                    name: None,
                    service: Some("180f".into()),
                }],
            },
        )
        .await;
        assert!(matches!(
            recv_reply(&mut client.ws).await,
            OpResult::Ok { .. }
        ));
        assert!(matches!(
            recv_reply(&mut client.ws).await,
            OpResult::Ok { .. }
        ));
        harness.wait_for_scan_start(1).await;
        harness
            .fake
            .emit_scan(test_advertisement("hr", Some("180d"), None, -50))
            .await;
        harness
            .fake
            .emit_scan(test_advertisement("battery", Some("180f"), None, -60))
            .await;
        assert_eq!(
            recv_scan_result(&mut client.ws, 1).await.device.value,
            "battery"
        );
        assert!(
            tokio::time::timeout(
                Duration::from_millis(100),
                recv_scan_result(&mut client.ws, 1),
            )
            .await
            .is_err()
        );
        send_command(&mut client.ws, 3, Op::ScanStop { scan_id: 1 }).await;
        assert!(matches!(
            recv_reply(&mut client.ws).await,
            OpResult::Ok { .. }
        ));
        harness
            .fake
            .emit_scan(test_advertisement("battery-2", Some("180f"), None, -60))
            .await;
        assert!(
            tokio::time::timeout(
                Duration::from_millis(100),
                recv_scan_result(&mut client.ws, 1),
            )
            .await
            .is_err()
        );
        client.close().await;
    }

    #[tokio::test]
    async fn scan_conc_07_server_advertises_exactly_its_configured_mode() {
        let harness = ScanWsHarness::new(ScanConcurrencyMode::Single, Duration::from_secs(1));
        let client = harness
            .client(
                "scan-a",
                &[
                    crate::protocol::frame::capabilities::SCAN_CONCURRENCY_MULTIPLEXED,
                    crate::protocol::frame::capabilities::SCAN_CONCURRENCY_SINGLE,
                    crate::protocol::frame::capabilities::SCAN_CONCURRENCY_UNCONTROLLED,
                ],
                &[crate::protocol::frame::capabilities::SCAN_CONCURRENCY_SINGLE],
            )
            .await;
        client.close().await;
    }

    #[tokio::test]
    async fn slots_is_negotiable_though_the_backend_advertises_nothing() {
        // `slots` is agent-level: the fake backend reports no capabilities at all, and that must
        // not be able to withhold it. The assertion lives in `client()`, which compares the
        // ServerHello set exactly.
        let harness = ScanWsHarness::new(ScanConcurrencyMode::Multiplexed, Duration::from_secs(1));
        let client = harness
            .client(
                "slots-a",
                &[capabilities::CONNECTION_SLOTS],
                &[capabilities::CONNECTION_SLOTS],
            )
            .await;
        client.close().await;
    }

    #[tokio::test]
    async fn slot_state_arrives_at_handshake_without_connecting_anything() {
        let harness = ScanWsHarness::new(ScanConcurrencyMode::Multiplexed, Duration::from_secs(1));
        let mut client = harness
            .client(
                "slots-a",
                &[capabilities::CONNECTION_SLOTS],
                &[capabilities::CONNECTION_SLOTS],
            )
            .await;

        // A client that negotiates `slots` and asks nothing else still learns the occupancy,
        // instead of waiting for a connection count to move on an agent that may be idle.
        assert_eq!(recv_slot_state(&mut client.ws).await, (8, 8));
        client.close().await;
    }

    #[tokio::test]
    async fn slot_state_counts_another_clients_lease() {
        let harness = ScanWsHarness::new(ScanConcurrencyMode::Multiplexed, Duration::from_secs(1));
        let mut holder = harness.client("slots-holder", &[], &[]).await;
        send_command(
            &mut holder.ws,
            1,
            Op::Connect {
                device: DeviceHandle {
                    value: "dev-1".into(),
                },
            },
        )
        .await;
        assert!(matches!(
            recv_reply(&mut holder.ws).await,
            OpResult::Ok { .. }
        ));

        // A second client's very first slot report must already account for the peripheral the
        // first client holds — the per-session count this replaced would have said "8 free".
        let mut watcher = harness
            .client(
                "slots-watcher",
                &[capabilities::CONNECTION_SLOTS],
                &[capabilities::CONNECTION_SLOTS],
            )
            .await;
        assert_eq!(recv_slot_state(&mut watcher.ws).await, (7, 8));

        watcher.close().await;
        holder.close().await;
    }

    /// The next `ScanResultBatch` for `scan_id`, as its device handles.
    async fn recv_scan_batch(
        ws: &mut tokio_tungstenite::WebSocketStream<tokio::io::DuplexStream>,
        scan_id: i64,
    ) -> Vec<String> {
        loop {
            if let Frame::Event {
                event:
                    AgentEvent::ScanResultBatch {
                        scan_id: event_scan_id,
                        advertisements,
                    },
            } = recv_frame(ws).await
                && event_scan_id == scan_id
            {
                return advertisements.into_iter().map(|a| a.device.value).collect();
            }
        }
    }

    #[tokio::test]
    async fn scan_batch_coalesces_a_window_into_one_event() {
        let harness = ScanWsHarness::new(ScanConcurrencyMode::Multiplexed, Duration::from_secs(1));
        let mut client = harness
            .client(
                "scan-a",
                &[capabilities::SCAN_BATCH],
                &[capabilities::SCAN_BATCH],
            )
            .await;
        send_command(
            &mut client.ws,
            1,
            Op::ScanStart {
                scan_id: 1,
                filters: vec![],
            },
        )
        .await;
        assert!(matches!(
            recv_reply(&mut client.ws).await,
            OpResult::Ok { .. }
        ));
        harness.wait_for_scan_start(1).await;

        // Three advertisements inside one window arrive as one event rather than three, which is
        // the whole point of the capability: one frame, one decode, one wakeup.
        for device in ["hr", "battery", "thermo"] {
            harness
                .fake
                .emit_scan(test_advertisement(device, None, None, -50))
                .await;
        }
        assert_eq!(
            recv_scan_batch(&mut client.ws, 1).await,
            vec!["hr", "battery", "thermo"],
            "order within a batch must follow arrival order"
        );
        client.close().await;
    }

    #[tokio::test]
    async fn scan_batch_flushes_early_once_a_burst_fills_the_buffer() {
        let harness = ScanWsHarness::new(ScanConcurrencyMode::Multiplexed, Duration::from_secs(1));
        let mut client = harness
            .client(
                "scan-a",
                &[capabilities::SCAN_BATCH],
                &[capabilities::SCAN_BATCH],
            )
            .await;
        send_command(
            &mut client.ws,
            1,
            Op::ScanStart {
                scan_id: 1,
                filters: vec![],
            },
        )
        .await;
        assert!(matches!(
            recv_reply(&mut client.ws).await,
            OpResult::Ok { .. }
        ));
        harness.wait_for_scan_start(1).await;

        // A flood must not grow the buffer without bound between ticks, so the batch caps out and
        // flushes on its own. Emitting one over the cap proves the flush was the size rule and not
        // the timer: the first batch is exactly SCAN_BATCH_MAX_SIZE, with the remainder left over.
        for index in 0..SCAN_BATCH_MAX_SIZE + 1 {
            harness
                .fake
                .emit_scan(test_advertisement(&format!("dev-{index}"), None, None, -50))
                .await;
        }
        let first = recv_scan_batch(&mut client.ws, 1).await;
        assert_eq!(first.len(), SCAN_BATCH_MAX_SIZE);
        assert_eq!(first[0], "dev-0");
        assert_eq!(
            recv_scan_batch(&mut client.ws, 1).await,
            vec![format!("dev-{}", SCAN_BATCH_MAX_SIZE)],
        );
        client.close().await;
    }

    #[tokio::test]
    async fn scan_results_are_unbatched_without_the_capability() {
        let harness = ScanWsHarness::new(ScanConcurrencyMode::Multiplexed, Duration::from_secs(1));
        let mut client = harness.client("scan-a", &[], &[]).await;
        send_command(
            &mut client.ws,
            1,
            Op::ScanStart {
                scan_id: 1,
                filters: vec![],
            },
        )
        .await;
        assert!(matches!(
            recv_reply(&mut client.ws).await,
            OpResult::Ok { .. }
        ));
        harness.wait_for_scan_start(1).await;

        harness
            .fake
            .emit_scan(test_advertisement("hr", None, None, -50))
            .await;
        // A batch is a capability-gated event type: sending one unnegotiated could break the
        // client's decode loop, which is exactly what §5.3 forbids.
        let batch = tokio::time::timeout(
            Duration::from_millis(300),
            recv_scan_batch(&mut client.ws, 1),
        )
        .await;
        assert!(batch.is_err(), "unexpected batch: {batch:?}");
        client.close().await;
    }

    #[tokio::test]
    async fn warm_lease_resume_does_not_reconnect_the_radio() {
        // The property a process-per-command client depends on: each invocation opens a transport,
        // issues `connect`, and expects to be talking to the peripheral its predecessor left
        // connected. If that `connect` re-drove the radio, every command would pay a physical
        // reconnect, which is the cost the transport-grace window exists to avoid — the window
        // would keep the *lease* while silently dropping its whole benefit.
        let harness = ScanWsHarness::new(ScanConcurrencyMode::Multiplexed, Duration::from_secs(30));
        let device = DeviceHandle {
            value: "dev-1".into(),
        };

        let mut first = harness.client("rble", &[], &[]).await;
        send_command(
            &mut first.ws,
            1,
            Op::Connect {
                device: device.clone(),
            },
        )
        .await;
        assert!(matches!(
            recv_reply(&mut first.ws).await,
            OpResult::Ok { .. }
        ));
        assert_eq!(harness.fake.connects.load(Ordering::Relaxed), 1);
        first.close().await;

        // Two more invocations under the same client id — which is what keys ownership, so this is
        // the same client resuming rather than a second one contending.
        for _ in 0..2 {
            let mut next = harness.client("rble", &[], &[]).await;
            send_command(
                &mut next.ws,
                1,
                Op::Connect {
                    device: device.clone(),
                },
            )
            .await;
            assert!(matches!(
                recv_reply(&mut next.ws).await,
                OpResult::Ok { .. }
            ));
            next.close().await;
        }

        assert_eq!(
            harness.fake.connects.load(Ordering::Relaxed),
            1,
            "the radio was re-driven on resume"
        );
        assert_eq!(harness.fake.disconnects.load(Ordering::Relaxed), 0);
    }

    #[tokio::test]
    async fn a_lease_whose_radio_link_dropped_still_reconnects() {
        // The complement, so the skip above cannot silently swallow a genuine reconnect: after the
        // radio drops, the lease survives its own grace window but the link is down, and the next
        // connect must actually reach the backend.
        let harness = ScanWsHarness::new(ScanConcurrencyMode::Multiplexed, Duration::from_secs(30));
        let device = DeviceHandle {
            value: "dev-1".into(),
        };

        let mut first = harness.client("rble", &[], &[]).await;
        send_command(
            &mut first.ws,
            1,
            Op::Connect {
                device: device.clone(),
            },
        )
        .await;
        assert!(matches!(
            recv_reply(&mut first.ws).await,
            OpResult::Ok { .. }
        ));
        harness.registry.on_ble_disconnected(&device.value);
        first.close().await;

        let mut second = harness.client("rble", &[], &[]).await;
        send_command(
            &mut second.ws,
            1,
            Op::Connect {
                device: device.clone(),
            },
        )
        .await;
        assert!(matches!(
            recv_reply(&mut second.ws).await,
            OpResult::Ok { .. }
        ));
        assert_eq!(harness.fake.connects.load(Ordering::Relaxed), 2);
        second.close().await;
    }

    #[tokio::test]
    async fn no_slot_state_without_the_capability() {
        let harness = ScanWsHarness::new(ScanConcurrencyMode::Multiplexed, Duration::from_secs(1));
        let mut client = harness.client("slots-none", &[], &[]).await;
        send_command(
            &mut client.ws,
            1,
            Op::Connect {
                device: DeviceHandle {
                    value: "dev-1".into(),
                },
            },
        )
        .await;
        assert!(matches!(
            recv_reply(&mut client.ws).await,
            OpResult::Ok { .. }
        ));

        // An un-negotiated event type could break a client's decode loop, so occupancy changing
        // under its feet must still produce nothing.
        let slot =
            tokio::time::timeout(Duration::from_millis(200), recv_slot_state(&mut client.ws)).await;
        assert!(slot.is_err(), "unexpected slot state: {slot:?}");
        client.close().await;
    }

    // ---- write policy over a real handshake (U7) ----
    //
    // Every other write-policy test drives `execute_op` directly with a hand-built
    // `negotiated_capabilities` set. This one instead goes through a real `ClientHello`, proving
    // the negotiated set an actual handshake produces is what the policy check reads — not just
    // what a test constructed to look like one.

    #[tokio::test]
    async fn named_principals_receive_the_same_write_policy_matrix_over_real_handshakes() {
        let mut harness =
            ScanWsHarness::new(ScanConcurrencyMode::Multiplexed, Duration::from_secs(1));
        harness.set_credentials(HashMap::from([
            ("lab-a".to_string(), "secret-a".to_string()),
            ("lab-b".to_string(), "secret-b".to_string()),
        ]));
        harness.set_write_policy(
            WritePolicy::decode(
                r#"{"version":1,"principals":{
                    "lab-a":{"writes":[{"service":"180d","characteristic":"2a39"}]},
                    "lab-b":{"writes":[{"service":"180d","characteristic":"2a39"}]}
                }}"#,
                &HashSet::from(["lab-a".to_string(), "lab-b".to_string()]),
            )
            .unwrap(),
        );

        for (principal, secret) in [("lab-a", "secret-a"), ("lab-b", "secret-b")] {
            let mut client = harness
                .client_with_bearer(
                    &format!("{principal}-policy-client"),
                    &[capabilities::WRITE_POLICY],
                    &[capabilities::WRITE_POLICY],
                    secret,
                )
                .await;
            let device = DeviceHandle {
                value: "dev-1".into(),
            };

            send_command(
                &mut client.ws,
                1,
                Op::Connect {
                    device: device.clone(),
                },
            )
            .await;
            assert!(matches!(
                recv_reply(&mut client.ws).await,
                OpResult::Ok { .. }
            ));

            send_command(
                &mut client.ws,
                2,
                Op::Write {
                    device: device.clone(),
                    char: test_char(),
                    value: vec![0x01],
                    with_response: true,
                },
            )
            .await;
            assert!(matches!(
                recv_reply(&mut client.ws).await,
                OpResult::Ok { .. }
            ));

            send_command(
                &mut client.ws,
                3,
                Op::Write {
                    device: device.clone(),
                    char: CharRef {
                        characteristic: "2a38".into(),
                        ..test_char()
                    },
                    value: vec![0x01],
                    with_response: true,
                },
            )
            .await;
            assert!(matches!(
                recv_reply(&mut client.ws).await,
                OpResult::Err { error } if error.kind == ErrorKind::PolicyDenied
            ));

            send_command(&mut client.ws, 4, Op::Disconnect { device }).await;
            assert!(matches!(
                recv_reply(&mut client.ws).await,
                OpResult::Ok { .. }
            ));
            client.close().await;
        }
    }

    // ---- agent.status (U3) ----

    async fn request_status(client: &mut ScanWsClient, cid: i64) -> AgentStatusDto {
        send_command(&mut client.ws, cid, Op::AgentStatus).await;
        match recv_reply(&mut client.ws).await {
            OpResult::Ok {
                payload: Some(ResultPayload::Status { status }),
            } => status,
            other => panic!("expected a status payload, got {other:?}"),
        }
    }

    #[tokio::test]
    async fn agent_status_shows_a_caller_its_own_leases_and_only_a_count_of_others() {
        let harness = ScanWsHarness::new(ScanConcurrencyMode::Multiplexed, Duration::from_secs(1));
        // A lease held by an entirely different principal, taken directly on the shared registry.
        harness
            .registry
            .acquire_lease("dev-other", "lab-b\0ci-runner", Default::default())
            .expect("the other tenant's lease must be granted");

        let mut client = harness.client("mine", &[], &[]).await;
        send_command(
            &mut client.ws,
            1,
            Op::Connect {
                device: DeviceHandle {
                    value: "dev-1".into(),
                },
            },
        )
        .await;
        assert!(matches!(
            recv_reply(&mut client.ws).await,
            OpResult::Ok { .. }
        ));

        let status = request_status(&mut client, 2).await;
        assert_eq!(status.leases.len(), 1);
        assert!(status.leases[0].mine);
        assert!(status.leases[0].connected);
        // The other tenant is a number, not a name: enough to explain the capacity, nothing more.
        assert_eq!(status.other_leases, 1);
        assert_eq!(status.slots.total, 8);
        assert_eq!(status.slots.free, 6);
        assert!(!status.operator_scope);
        assert_eq!(status.connected_clients, 1);
        assert_eq!(status.agent_info.as_deref(), Some(AGENT_INFO));
        assert_eq!(status.settings.scan_concurrency, "multiplexed");
        assert!(!status.settings.write_policy_enforced);
        client.close().await;
    }

    #[tokio::test]
    async fn agent_status_discloses_every_holder_only_under_operator_scope() {
        let mut harness =
            ScanWsHarness::new(ScanConcurrencyMode::Multiplexed, Duration::from_secs(1));
        harness.operator_token = Some("operator-secret".to_string());
        harness
            .registry
            .acquire_lease("dev-other", "lab-b\0ci-runner", Default::default())
            .expect("the other tenant's lease must be granted");

        // No credential, then a wrong one: neither may fail the connection, and neither may widen
        // disclosure. A wrong secret connecting normally is what lets a caller report "no operator
        // credential" instead of "agent unreachable".
        for offered in [None, Some("not-the-operator-secret")] {
            let mut client = harness
                .client_with_operator("plain", &[], &[], offered)
                .await;
            let status = request_status(&mut client, 1).await;
            assert!(!status.operator_scope, "offered={offered:?}");
            assert!(status.leases.is_empty(), "offered={offered:?}");
            assert_eq!(status.other_leases, 1, "offered={offered:?}");
            client.close().await;
        }

        let mut operator = harness
            .client_with_operator("ops", &[], &[], Some("operator-secret"))
            .await;
        let status = request_status(&mut operator, 1).await;
        assert!(status.operator_scope);
        assert_eq!(status.leases.len(), 1);
        // The other principal's client id is disclosed here and nowhere else.
        assert_eq!(status.leases[0].holder.as_deref(), Some("lab-b/ci-runner"));
        assert!(!status.leases[0].mine);
        // Nothing is left over once every lease is listed.
        assert_eq!(status.other_leases, 0);
        operator.close().await;
    }

    #[tokio::test]
    async fn agent_status_reports_remaining_grace_for_a_lease_whose_owner_dropped() {
        let harness = ScanWsHarness::new(ScanConcurrencyMode::Multiplexed, Duration::from_secs(60));
        harness
            .registry
            .acquire_lease("dev-warm", "lab-b\0gone", Default::default())
            .expect("lease must be granted");
        harness.registry.on_transport_drop("lab-b\0gone");

        let mut operator = harness.client_with_operator("ops", &[], &[], None).await;
        // Read as an operator would; without a token configured this connection has normal scope,
        // so drive the assertion off the registry snapshot the reply is built from.
        let lease = harness
            .registry
            .snapshot()
            .into_iter()
            .find(|l| l.handle == "dev-warm")
            .expect("the warm lease must still be held");
        assert!(lease.in_grace);
        let remaining = lease
            .remaining_grace_ms
            .expect("a pending release must report how long is left");
        // Measured against the registry's own transport grace, not the harness argument — that one
        // configures the scan coordinator, and hard-coding a number here would silently pass if the
        // two ever diverged.
        let configured = harness.registry.settings().transport_grace.as_millis() as i64;
        assert!(
            remaining > configured - 5_000 && remaining <= configured,
            "remaining was {remaining}ms against a {configured}ms window"
        );
        // And it is still occupying a slot, which is what the caller can see without disclosure.
        let status = request_status(&mut operator, 1).await;
        assert_eq!(status.slots.free, 7);
        assert_eq!(status.other_leases, 1);
        operator.close().await;
    }

    #[tokio::test]
    async fn scan_conc_08_legacy_client_gets_legacy_agent_busy_on_contention() {
        let harness = ScanWsHarness::new(ScanConcurrencyMode::Single, Duration::from_secs(1));
        let mut incumbent = harness
            .client(
                "scan-a",
                &[crate::protocol::frame::capabilities::SCAN_CONCURRENCY_SINGLE],
                &[crate::protocol::frame::capabilities::SCAN_CONCURRENCY_SINGLE],
            )
            .await;
        let mut legacy = harness.client("scan-b", &[], &[]).await;
        send_command(
            &mut incumbent.ws,
            1,
            Op::ScanStart {
                scan_id: 1,
                filters: vec![],
            },
        )
        .await;
        assert!(matches!(
            recv_reply(&mut incumbent.ws).await,
            OpResult::Ok { .. }
        ));
        harness.wait_for_scan_start(1).await;
        send_command(
            &mut legacy.ws,
            1,
            Op::ScanStart {
                scan_id: 1,
                filters: vec![],
            },
        )
        .await;
        assert!(matches!(
            recv_reply(&mut legacy.ws).await,
            OpResult::Err { error } if error.kind == ErrorKind::AgentBusy
        ));
        incumbent.close().await;
        legacy.close().await;
    }

    #[tokio::test]
    async fn scan_conc_09_dropped_connection_enters_grace_and_other_clients_survive() {
        let harness =
            ScanWsHarness::new(ScanConcurrencyMode::Multiplexed, Duration::from_millis(100));
        let mut dropped = harness
            .client(
                "scan-a",
                &[crate::protocol::frame::capabilities::SCAN_CONCURRENCY_MULTIPLEXED],
                &[crate::protocol::frame::capabilities::SCAN_CONCURRENCY_MULTIPLEXED],
            )
            .await;
        let mut survivor = harness
            .client(
                "scan-b",
                &[crate::protocol::frame::capabilities::SCAN_CONCURRENCY_MULTIPLEXED],
                &[crate::protocol::frame::capabilities::SCAN_CONCURRENCY_MULTIPLEXED],
            )
            .await;
        for ws in [&mut dropped.ws, &mut survivor.ws] {
            send_command(
                ws,
                1,
                Op::ScanStart {
                    scan_id: 1,
                    filters: vec![],
                },
            )
            .await;
            assert!(matches!(recv_reply(ws).await, OpResult::Ok { .. }));
        }
        harness.wait_for_scan_start(1).await;
        dropped.close().await;
        harness
            .fake
            .emit_scan(test_advertisement("survivor", None, Some("survivor"), -50))
            .await;
        assert_eq!(
            recv_scan_result(&mut survivor.ws, 1).await.device.value,
            "survivor"
        );
        tokio::time::sleep(Duration::from_millis(150)).await;
        let mut resumed = harness
            .client(
                "scan-a",
                &[crate::protocol::frame::capabilities::SCAN_CONCURRENCY_MULTIPLEXED],
                &[crate::protocol::frame::capabilities::SCAN_CONCURRENCY_MULTIPLEXED],
            )
            .await;
        send_command(
            &mut resumed.ws,
            1,
            Op::ScanStart {
                scan_id: 1,
                filters: vec![],
            },
        )
        .await;
        assert!(matches!(
            recv_reply(&mut resumed.ws).await,
            OpResult::Ok { .. }
        ));
        survivor.close().await;
        resumed.close().await;
    }

    #[tokio::test]
    async fn scan_conc_10_reconnect_within_grace_rebinds_on_the_first_attempt() {
        let harness = ScanWsHarness::new(ScanConcurrencyMode::Single, Duration::from_secs(1));
        let mut first = harness
            .client(
                "scan-a",
                &[crate::protocol::frame::capabilities::SCAN_CONCURRENCY_SINGLE],
                &[crate::protocol::frame::capabilities::SCAN_CONCURRENCY_SINGLE],
            )
            .await;
        send_command(
            &mut first.ws,
            1,
            Op::ScanStart {
                scan_id: 1,
                filters: vec![],
            },
        )
        .await;
        assert!(matches!(
            recv_reply(&mut first.ws).await,
            OpResult::Ok { .. }
        ));
        harness.wait_for_scan_start(1).await;
        first.close().await;

        let mut rebound = harness
            .client(
                "scan-a",
                &[crate::protocol::frame::capabilities::SCAN_CONCURRENCY_SINGLE],
                &[crate::protocol::frame::capabilities::SCAN_CONCURRENCY_SINGLE],
            )
            .await;
        send_command(
            &mut rebound.ws,
            1,
            Op::ScanStart {
                scan_id: 1,
                filters: vec![],
            },
        )
        .await;
        assert!(matches!(
            recv_reply(&mut rebound.ws).await,
            OpResult::Ok { .. }
        ));
        rebound.close().await;
    }

    #[tokio::test]
    async fn grace_expiry_releases_the_single_slot_for_a_different_key() {
        let harness = ScanWsHarness::new(ScanConcurrencyMode::Single, Duration::from_millis(100));
        let mut owner = harness
            .client(
                "scan-a",
                &[crate::protocol::frame::capabilities::SCAN_CONCURRENCY_SINGLE],
                &[crate::protocol::frame::capabilities::SCAN_CONCURRENCY_SINGLE],
            )
            .await;
        send_command(
            &mut owner.ws,
            1,
            Op::ScanStart {
                scan_id: 1,
                filters: vec![],
            },
        )
        .await;
        assert!(matches!(
            recv_reply(&mut owner.ws).await,
            OpResult::Ok { .. }
        ));
        owner.close().await;

        let mut contender = harness
            .client(
                "scan-b",
                &[crate::protocol::frame::capabilities::SCAN_CONCURRENCY_SINGLE],
                &[crate::protocol::frame::capabilities::SCAN_CONCURRENCY_SINGLE],
            )
            .await;
        send_command(
            &mut contender.ws,
            1,
            Op::ScanStart {
                scan_id: 2,
                filters: vec![],
            },
        )
        .await;
        assert!(matches!(
            recv_reply(&mut contender.ws).await,
            OpResult::Err { error } if error.kind == ErrorKind::ScanUnavailable
        ));
        tokio::time::sleep(Duration::from_millis(150)).await;
        send_command(
            &mut contender.ws,
            2,
            Op::ScanStart {
                scan_id: 2,
                filters: vec![],
            },
        )
        .await;
        assert!(matches!(
            recv_reply(&mut contender.ws).await,
            OpResult::Ok { .. }
        ));
        contender.close().await;
    }

    #[tokio::test]
    async fn scan_conc_11_stale_grace_cleanup_cannot_remove_a_rebound_scan() {
        let harness = ScanWsHarness::new(ScanConcurrencyMode::Single, Duration::from_millis(100));
        let mut first = harness
            .client(
                "scan-a",
                &[crate::protocol::frame::capabilities::SCAN_CONCURRENCY_SINGLE],
                &[crate::protocol::frame::capabilities::SCAN_CONCURRENCY_SINGLE],
            )
            .await;
        send_command(
            &mut first.ws,
            1,
            Op::ScanStart {
                scan_id: 1,
                filters: vec![],
            },
        )
        .await;
        assert!(matches!(
            recv_reply(&mut first.ws).await,
            OpResult::Ok { .. }
        ));
        harness.wait_for_scan_start(1).await;
        first.close().await;

        let mut rebound = harness
            .client(
                "scan-a",
                &[crate::protocol::frame::capabilities::SCAN_CONCURRENCY_SINGLE],
                &[crate::protocol::frame::capabilities::SCAN_CONCURRENCY_SINGLE],
            )
            .await;
        send_command(
            &mut rebound.ws,
            1,
            Op::ScanStart {
                scan_id: 1,
                filters: vec![],
            },
        )
        .await;
        assert!(matches!(
            recv_reply(&mut rebound.ws).await,
            OpResult::Ok { .. }
        ));
        tokio::time::sleep(Duration::from_millis(150)).await;
        harness
            .fake
            .emit_scan(test_advertisement(
                "still-alive",
                None,
                Some("still-alive"),
                -50,
            ))
            .await;
        assert_eq!(
            recv_scan_result(&mut rebound.ws, 1).await.device.value,
            "still-alive"
        );
        rebound.close().await;
    }

    #[tokio::test]
    async fn uncontrolled_mode_uses_the_legacy_backend_path_for_both_scans() {
        let harness = ScanWsHarness::new(ScanConcurrencyMode::Uncontrolled, Duration::from_secs(1));
        let mut first = harness
            .client(
                "scan-a",
                &[
                    crate::protocol::frame::capabilities::SCAN_CONCURRENCY_MULTIPLEXED,
                    crate::protocol::frame::capabilities::SCAN_CONCURRENCY_SINGLE,
                    crate::protocol::frame::capabilities::SCAN_CONCURRENCY_UNCONTROLLED,
                ],
                &[crate::protocol::frame::capabilities::SCAN_CONCURRENCY_UNCONTROLLED],
            )
            .await;
        let mut second = harness
            .client(
                "scan-b",
                &[
                    crate::protocol::frame::capabilities::SCAN_CONCURRENCY_MULTIPLEXED,
                    crate::protocol::frame::capabilities::SCAN_CONCURRENCY_SINGLE,
                    crate::protocol::frame::capabilities::SCAN_CONCURRENCY_UNCONTROLLED,
                ],
                &[crate::protocol::frame::capabilities::SCAN_CONCURRENCY_UNCONTROLLED],
            )
            .await;
        for ws in [&mut first.ws, &mut second.ws] {
            send_command(
                ws,
                1,
                Op::ScanStart {
                    scan_id: 1,
                    filters: vec![],
                },
            )
            .await;
            assert!(matches!(recv_reply(ws).await, OpResult::Ok { .. }));
        }
        harness.wait_for_scan_start(2).await;
        harness
            .fake
            .emit_scan_to_all(test_advertisement("legacy", None, Some("legacy"), -50))
            .await;
        assert_eq!(
            recv_scan_result(&mut first.ws, 1).await.device.value,
            "legacy"
        );
        assert_eq!(
            recv_scan_result(&mut second.ws, 1).await.device.value,
            "legacy"
        );
        first.close().await;
        second.close().await;
    }

    /// AUTH-PRINCIPAL-01: two credentials reusing one stable client ID must not let a lease or
    /// operation cross the principal boundary. `alpha` and `beta` share the raw
    /// `X-RemoteBle-Client` header value; the server's actual ownership key is
    /// `session_key(principal, client_id)` (see the handshake callback above), so the two
    /// connections are fully independent owners despite the shared raw id.
    #[tokio::test]
    async fn principal_isolation_holds_across_lease_and_operations() {
        use tokio_tungstenite::tungstenite::client::IntoClientRequest;

        let fake = Arc::new(FakeBackend::default());
        let backend: Arc<dyn BleBackend> = fake.clone();
        let registry = PeripheralRegistry::new(LeaseConfig::default());
        let credentials = Arc::new(HashMap::from([
            ("alpha".to_string(), "secret-a".to_string()),
            ("beta".to_string(), "secret-b".to_string()),
        ]));
        let strict = Arc::new(AtomicBool::new(false));
        let live_sessions = Arc::new(LiveSessionRegistry::default());
        let failed_auth_limiter = Arc::new(AuthFailureLimiter::default());
        let revoked_principals = Arc::new(parking_lot::Mutex::new(HashSet::new()));

        let request_for = |secret: &'static str| {
            let mut request = "ws://localhost/agent".into_client_request().unwrap();
            request
                .headers_mut()
                .insert("Authorization", format!("Bearer {secret}").parse().unwrap());
            request.headers_mut().insert(
                "X-RemoteBle-Client",
                "shared-raw-client-id".parse().unwrap(),
            );
            request
        };

        let (alpha_server_io, alpha_client_io) = tokio::io::duplex(65536);
        tokio::spawn(AgentServer::accept_connection(
            alpha_server_io,
            "127.0.0.1:1".parse().unwrap(),
            backend.clone(),
            registry.clone(),
            credentials.clone(),
            strict.clone(),
            live_sessions.clone(),
            failed_auth_limiter.clone(),
            revoked_principals.clone(),
            None,
        ));
        let (mut alpha, _) =
            tokio_tungstenite::client_async(request_for("secret-a"), alpha_client_io)
                .await
                .expect("alpha must be accepted");

        let (beta_server_io, beta_client_io) = tokio::io::duplex(65536);
        tokio::spawn(AgentServer::accept_connection(
            beta_server_io,
            "127.0.0.1:2".parse().unwrap(),
            backend.clone(),
            registry.clone(),
            credentials.clone(),
            strict.clone(),
            live_sessions.clone(),
            failed_auth_limiter.clone(),
            revoked_principals.clone(),
            None,
        ));
        let (mut beta, _) =
            tokio_tungstenite::client_async(request_for("secret-b"), beta_client_io)
                .await
                .expect("beta must be accepted despite sharing alpha's raw client id");

        let device = DeviceHandle {
            value: "dev".into(),
        };

        send_command(
            &mut alpha,
            1,
            Op::Connect {
                device: device.clone(),
            },
        )
        .await;
        assert!(matches!(recv_reply(&mut alpha).await, OpResult::Ok { .. }));

        // beta shares the raw client id but not the principal: it must not see alpha's lease.
        send_command(
            &mut beta,
            1,
            Op::Connect {
                device: device.clone(),
            },
        )
        .await;
        assert!(matches!(
            recv_reply(&mut beta).await,
            OpResult::Err { error } if error.kind == ErrorKind::PeripheralBusy
        ));

        send_command(
            &mut beta,
            2,
            Op::Read {
                device: device.clone(),
                char: CharRef {
                    service: "s".into(),
                    characteristic: "c".into(),
                    instance: 0,
                },
            },
        )
        .await;
        assert!(matches!(
            recv_reply(&mut beta).await,
            OpResult::Err { error } if error.kind == ErrorKind::PeripheralBusy
        ));
        assert_eq!(fake.reads.load(Ordering::Relaxed), 0);
    }

    #[test]
    fn agent_server_revoke_principal_validates_known_credentials_and_toggles_state() {
        let credentials = Arc::new(HashMap::from([(
            "alpha".to_string(),
            "secret-a".to_string(),
        )]));
        let server = AgentServer::new(
            ServerConfig {
                addr: "127.0.0.1:0".parse().unwrap(),
                credentials,
                strict_identifiers: Arc::new(AtomicBool::new(false)),
                scan_concurrency: ScanConcurrencyMode::Multiplexed,
                transport_grace: Duration::from_secs(10),
                operator_token: None,
                write_policy: WritePolicy::permissive(),
            },
            Arc::new(FakeBackend::default()),
            PeripheralRegistry::new(LeaseConfig::default()),
        );

        assert!(server.revoke_principal("unknown").is_err());
        assert!(!server.is_principal_revoked("alpha"));

        server.revoke_principal("alpha").unwrap();
        assert!(server.is_principal_revoked("alpha"));

        server.unrevoke_principal("alpha");
        assert!(!server.is_principal_revoked("alpha"));
    }

    /// AUTH-REVOKE-01: a credential revoked while a lease sits mid transport-grace must not be
    /// able to resume it. The revoked principal's next connection attempt — even carrying the
    /// same stable client id a warm-lease resume would use — fails re-authentication
    /// (`authenticate`, above) before the registry is ever consulted.
    #[tokio::test]
    async fn revoked_credential_cannot_resume_a_lease_during_transport_grace() {
        use tokio_tungstenite::tungstenite::client::IntoClientRequest;

        let backend: Arc<dyn BleBackend> = Arc::new(FakeBackend::default());
        let registry = PeripheralRegistry::new(LeaseConfig::default());
        let credentials = Arc::new(HashMap::from([(
            "alpha".to_string(),
            "secret-a".to_string(),
        )]));
        let strict = Arc::new(AtomicBool::new(false));
        let live_sessions = Arc::new(LiveSessionRegistry::default());
        let failed_auth_limiter = Arc::new(AuthFailureLimiter::default());
        let revoked_principals = Arc::new(parking_lot::Mutex::new(HashSet::new()));

        let request = || {
            let mut request = "ws://localhost/agent".into_client_request().unwrap();
            request
                .headers_mut()
                .insert("Authorization", "Bearer secret-a".parse().unwrap());
            request
                .headers_mut()
                .insert("X-RemoteBle-Client", "resuming-client".parse().unwrap());
            request
        };

        let (first_server_io, first_client_io) = tokio::io::duplex(4096);
        let first_accept = tokio::spawn(AgentServer::accept_connection(
            first_server_io,
            "127.0.0.1:1".parse().unwrap(),
            backend.clone(),
            registry.clone(),
            credentials.clone(),
            strict.clone(),
            live_sessions.clone(),
            failed_auth_limiter.clone(),
            revoked_principals.clone(),
            None,
        ));
        let (mut first, _) = tokio_tungstenite::client_async(request(), first_client_io)
            .await
            .expect("first connect must be accepted");
        send_command(
            &mut first,
            1,
            Op::Connect {
                device: DeviceHandle {
                    value: "dev".into(),
                },
            },
        )
        .await;
        assert!(matches!(recv_reply(&mut first).await, OpResult::Ok { .. }));

        // The transport drops (radio link stays warm, pending release within grace — see
        // LEASE-GRACE-01) and the credential is revoked while the lease is held.
        drop(first);
        first_accept
            .await
            .expect("first connection task must not panic");
        revoked_principals.lock().insert("alpha".to_string());

        // A resume attempt with the same stable client id and the revoked credential must be
        // refused pre-upgrade — never reaching the registry at all.
        let (second_server_io, second_client_io) = tokio::io::duplex(4096);
        tokio::spawn(AgentServer::accept_connection(
            second_server_io,
            "127.0.0.1:2".parse().unwrap(),
            backend.clone(),
            registry.clone(),
            credentials.clone(),
            strict.clone(),
            live_sessions.clone(),
            failed_auth_limiter.clone(),
            revoked_principals.clone(),
            None,
        ));
        let second_err = tokio_tungstenite::client_async(request(), second_client_io)
            .await
            .expect_err("a revoked credential must not be able to resume the lease");
        match second_err {
            tokio_tungstenite::tungstenite::Error::Http(response) => {
                assert_eq!(response.status(), http::StatusCode::UNAUTHORIZED);
            }
            other => panic!("expected an HTTP 401 handshake rejection, got {other:?}"),
        }
    }

    #[derive(Default)]
    struct FakeBackend {
        scans: Mutex<Vec<StreamKey>>,
        scan_filters: Mutex<Vec<Vec<ScanFilter>>>,
        scan_senders: Mutex<Vec<mpsc::Sender<AgentEvent>>>,
        observations: Mutex<Vec<StreamKey>>,
        stopped_observations: Mutex<Vec<StreamKey>>,
        reads: AtomicUsize,
        connects: AtomicUsize,
        disconnects: AtomicUsize,
        /// Descriptor ops this backend was actually asked to perform, so a test can tell a
        /// dispatched op from one the catch-all `Unsupported` arm swallowed.
        descriptor_reads: Mutex<Vec<DescRef>>,
        descriptor_writes: Mutex<Vec<(DescRef, Vec<u8>)>>,
    }

    /// Holds the reader pending forever while every writer attempt fails. This models a socket
    /// whose outbound half has died while its inbound half has not produced EOF yet.
    struct WriterFailureStream;

    impl AsyncRead for WriterFailureStream {
        fn poll_read(
            self: Pin<&mut Self>,
            _cx: &mut Context<'_>,
            _buf: &mut ReadBuf<'_>,
        ) -> Poll<io::Result<()>> {
            Poll::Pending
        }
    }

    impl AsyncWrite for WriterFailureStream {
        fn poll_write(
            self: Pin<&mut Self>,
            _cx: &mut Context<'_>,
            _buf: &[u8],
        ) -> Poll<io::Result<usize>> {
            Poll::Ready(Err(io::Error::new(
                io::ErrorKind::BrokenPipe,
                "test writer failure",
            )))
        }

        fn poll_flush(self: Pin<&mut Self>, _cx: &mut Context<'_>) -> Poll<io::Result<()>> {
            Poll::Ready(Ok(()))
        }

        fn poll_shutdown(self: Pin<&mut Self>, _cx: &mut Context<'_>) -> Poll<io::Result<()>> {
            Poll::Ready(Ok(()))
        }
    }

    #[async_trait]
    impl BleBackend for FakeBackend {
        fn capabilities(&self) -> Vec<String> {
            vec![]
        }
        async fn start_scan(
            &self,
            stream: StreamKey,
            filters: Vec<ScanFilter>,
            tx: mpsc::Sender<AgentEvent>,
        ) -> Result<(), AgentError> {
            self.scans.lock().push(stream);
            self.scan_filters.lock().push(filters);
            self.scan_senders.lock().push(tx);
            Ok(())
        }
        async fn stop_scan(&self, _stream: StreamKey) -> Result<(), AgentError> {
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
            self.connects.fetch_add(1, Ordering::Relaxed);
            Ok(())
        }
        async fn disconnect(&self, _device: &DeviceHandle) -> Result<(), AgentError> {
            self.disconnects.fetch_add(1, Ordering::Relaxed);
            Ok(())
        }
        async fn discover(&self, _device: &DeviceHandle) -> Result<ResultPayload, AgentError> {
            Ok(ResultPayload::Services { services: vec![] })
        }
        async fn read(
            &self,
            _device: &DeviceHandle,
            _char: &CharRef,
        ) -> Result<ResultPayload, AgentError> {
            self.reads.fetch_add(1, Ordering::Relaxed);
            Ok(ResultPayload::Bytes { value: vec![] })
        }
        async fn write(
            &self,
            _device: &DeviceHandle,
            _char: &CharRef,
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
            Err(AgentError::new(ErrorKind::Unsupported, None))
        }
        async fn read_descriptor(
            &self,
            _device: &DeviceHandle,
            desc_ref: &DescRef,
        ) -> Result<ResultPayload, AgentError> {
            self.descriptor_reads.lock().push(desc_ref.clone());
            Ok(ResultPayload::Bytes {
                value: vec![0x01, 0x00],
            })
        }
        async fn write_descriptor(
            &self,
            _device: &DeviceHandle,
            desc_ref: &DescRef,
            value: &[u8],
        ) -> Result<(), AgentError> {
            self.descriptor_writes
                .lock()
                .push((desc_ref.clone(), value.to_vec()));
            Ok(())
        }
        async fn start_observe(
            &self,
            stream: StreamKey,
            _device: &DeviceHandle,
            _char: &CharRef,
            _tx: mpsc::Sender<AgentEvent>,
        ) -> Result<(), AgentError> {
            self.observations.lock().push(stream);
            Ok(())
        }
        async fn stop_observe(&self, stream: StreamKey) -> Result<(), AgentError> {
            self.stopped_observations.lock().push(stream);
            Ok(())
        }
    }

    impl FakeBackend {
        async fn emit_scan(&self, advertisement: crate::protocol::events::AdvertisementDto) {
            let sender = self.scan_senders.lock().last().cloned();
            if let Some(sender) = sender {
                let _ = sender
                    .send(AgentEvent::ScanResult {
                        scan_id: 1,
                        advertisement,
                    })
                    .await;
            }
        }

        async fn emit_scan_to_all(&self, advertisement: crate::protocol::events::AdvertisementDto) {
            let senders = self.scan_senders.lock().clone();
            for sender in senders {
                let _ = sender
                    .send(AgentEvent::ScanResult {
                        scan_id: 1,
                        advertisement: advertisement.clone(),
                    })
                    .await;
            }
        }
    }

    fn context<'a>(
        client_id: &'a str,
        backend: &'a Arc<dyn BleBackend>,
        registry: &'a PeripheralRegistry,
        generation: u64,
    ) -> ExecuteContext<'a> {
        context_with_liveness(
            client_id,
            backend,
            registry,
            generation,
            Box::leak(Box::new(AtomicBool::new(true))),
        )
    }

    fn context_with_liveness<'a>(
        client_id: &'a str,
        backend: &'a Arc<dyn BleBackend>,
        registry: &'a PeripheralRegistry,
        generation: u64,
        live: &'a AtomicBool,
    ) -> ExecuteContext<'a> {
        let strict = Box::leak(Box::new(Arc::new(AtomicBool::new(false))));
        let translator = Box::leak(Box::new(Arc::new(HandleTranslator::new(
            agent_identifier_format(),
            strict.clone(),
        ))));
        let (event_tx, _) = mpsc::channel(EVENT_CHANNEL_CAP);
        let streams = Box::leak(Box::new(StreamReservations::default()));
        let scan_coordinator = Box::leak(Box::new(ScanCoordinator::new(
            backend.clone(),
            ScanConcurrencyMode::Multiplexed,
            Duration::from_secs(10),
            MAX_ACTIVE_SCANS,
        )));
        let scan_arbiter = Box::leak(Box::new(ScanArbiter::new(event_tx.clone())));
        let scan_bindings = Box::leak(Box::new(ScanBindings::default()));
        let scan_gates = Box::leak(Box::new(ScanGates::default()));
        let negotiated_capabilities = Box::leak(Box::new(parking_lot::Mutex::new(
            std::collections::BTreeSet::new(),
        )));
        let status = Box::leak(Box::new(StatusContext {
            source: Arc::new(StatusSource {
                started_at: Instant::now(),
                strict_identifiers: strict.clone(),
                live_sessions: Arc::new(LiveSessionRegistry::default()),
                device_names: Arc::new(DeviceNames::default()),
                write_policy: Arc::new(WritePolicy::permissive()),
            }),
            operator_scope: false,
        }));
        ExecuteContext {
            client_id,
            backend,
            registry,
            translator,
            event_tx,
            connection_live: live,
            stream_connection: generation,
            streams,
            scan_coordinator,
            scan_mode: ScanConcurrencyMode::Multiplexed,
            scan_arbiter,
            scan_bindings,
            scan_gates,
            negotiated_capabilities,
            status,
        }
    }

    /// As [context], but with a caller-supplied [WritePolicy] and negotiated-capability set — for
    /// the write-policy dispatch tests, which need both to differ from every other `execute_op`
    /// test in this module.
    fn context_with_policy<'a>(
        client_id: &'a str,
        backend: &'a Arc<dyn BleBackend>,
        registry: &'a PeripheralRegistry,
        generation: u64,
        policy: WritePolicy,
        negotiated: &[&str],
    ) -> ExecuteContext<'a> {
        let mut ctx = context(client_id, backend, registry, generation);
        *ctx.negotiated_capabilities.lock() = negotiated.iter().map(|s| s.to_string()).collect();
        ctx.status = Box::leak(Box::new(StatusContext {
            source: Arc::new(StatusSource {
                write_policy: Arc::new(policy),
                ..(*ctx.status.source).clone()
            }),
            operator_scope: ctx.status.operator_scope,
        }));
        ctx
    }

    #[tokio::test]
    async fn writer_failure_retires_a_reader_waiting_for_input() {
        let stream = tokio_tungstenite::WebSocketStream::from_raw_socket(
            WriterFailureStream,
            tokio_tungstenite::tungstenite::protocol::Role::Server,
            None,
        )
        .await;
        let backend: Arc<dyn BleBackend> = Arc::new(FakeBackend::default());
        let live_sessions = Arc::new(LiveSessionRegistry::default());
        assert!(live_sessions.try_acquire("test-client", 1));
        let task = tokio::spawn(AgentServer::handle_connection(
            stream,
            "test-client".into(),
            backend,
            PeripheralRegistry::new(LeaseConfig::default()),
            Arc::new(AtomicBool::new(false)),
            live_sessions,
            1,
        ));

        // The writer's first liveness ping fails immediately. The receive loop must select the
        // writer's terminal signal rather than remain blocked forever in `next()`.
        tokio::time::timeout(Duration::from_secs(1), task)
            .await
            .expect("writer failure must retire the connection")
            .expect("connection task must not panic");
    }

    #[tokio::test]
    async fn non_owner_read_is_rejected_before_the_backend_call() {
        let fake = Arc::new(FakeBackend::default());
        let backend: Arc<dyn BleBackend> = fake.clone();
        let registry = PeripheralRegistry::new(LeaseConfig::default());
        registry
            .acquire_lease("dev", "owner", Default::default())
            .unwrap();
        registry.on_connected("dev", "owner");
        let result = AgentServer::execute_op(
            Op::Read {
                device: DeviceHandle {
                    value: "dev".into(),
                },
                char: CharRef {
                    service: "s".into(),
                    characteristic: "c".into(),
                    instance: 0,
                },
            },
            context("other", &backend, &registry, 1),
        )
        .await;
        assert!(
            matches!(result, OpResult::Err { error } if error.kind == ErrorKind::PeripheralBusy)
        );
        assert_eq!(fake.reads.load(Ordering::Relaxed), 0);
    }

    fn test_descriptor() -> DescRef {
        DescRef {
            service: "180d".into(),
            characteristic: "2a37".into(),
            // Client Characteristic Configuration — the descriptor a client actually reaches for.
            descriptor: "2902".into(),
            instance: 0,
        }
    }

    #[tokio::test]
    async fn descriptor_read_reaches_the_backend() {
        let fake = Arc::new(FakeBackend::default());
        let backend: Arc<dyn BleBackend> = fake.clone();
        let registry = PeripheralRegistry::new(LeaseConfig::default());
        registry
            .acquire_lease("dev", "owner", Default::default())
            .unwrap();
        registry.on_connected("dev", "owner");

        let result = AgentServer::execute_op(
            Op::ReadDescriptor {
                device: DeviceHandle {
                    value: "dev".into(),
                },
                desc: test_descriptor(),
            },
            context("owner", &backend, &registry, 1),
        )
        .await;

        // Dispatched, not swallowed by the catch-all arm that used to answer UNSUPPORTED here.
        assert!(matches!(
            result,
            OpResult::Ok {
                payload: Some(ResultPayload::Bytes { ref value }),
            } if value == &[0x01, 0x00]
        ));
        assert_eq!(*fake.descriptor_reads.lock(), vec![test_descriptor()]);
    }

    #[tokio::test]
    async fn descriptor_write_reaches_the_backend_with_its_value() {
        let fake = Arc::new(FakeBackend::default());
        let backend: Arc<dyn BleBackend> = fake.clone();
        let registry = PeripheralRegistry::new(LeaseConfig::default());
        registry
            .acquire_lease("dev", "owner", Default::default())
            .unwrap();
        registry.on_connected("dev", "owner");

        let result = AgentServer::execute_op(
            Op::WriteDescriptor {
                device: DeviceHandle {
                    value: "dev".into(),
                },
                desc: test_descriptor(),
                value: vec![0x01, 0x00],
            },
            context("owner", &backend, &registry, 1),
        )
        .await;

        assert!(matches!(result, OpResult::Ok { .. }));
        assert_eq!(
            *fake.descriptor_writes.lock(),
            vec![(test_descriptor(), vec![0x01, 0x00])]
        );
    }

    #[tokio::test]
    async fn a_non_owner_is_rejected_before_a_descriptor_op_reaches_the_radio() {
        // Descriptor ops moved out of the catch-all arm, which authorized before answering. The
        // new arms must do the same, or implementing them would have quietly reopened the
        // cross-client hole Rig A case 3 closed: a handle is routing data, not a credential.
        let fake = Arc::new(FakeBackend::default());
        let backend: Arc<dyn BleBackend> = fake.clone();
        let registry = PeripheralRegistry::new(LeaseConfig::default());
        registry
            .acquire_lease("dev", "owner", Default::default())
            .unwrap();
        registry.on_connected("dev", "owner");

        for op in [
            Op::ReadDescriptor {
                device: DeviceHandle {
                    value: "dev".into(),
                },
                desc: test_descriptor(),
            },
            Op::WriteDescriptor {
                device: DeviceHandle {
                    value: "dev".into(),
                },
                desc: test_descriptor(),
                value: vec![0x01, 0x00],
            },
        ] {
            let result =
                AgentServer::execute_op(op, context("other", &backend, &registry, 1)).await;
            assert!(
                matches!(result, OpResult::Err { error } if error.kind == ErrorKind::PeripheralBusy)
            );
        }
        assert!(fake.descriptor_reads.lock().is_empty());
        assert!(fake.descriptor_writes.lock().is_empty());
    }

    /// Regression (Rig A case 3, 2026-07-28): an op this agent does not implement must still be
    /// *authorized* before it is answered. `ReadRssi` and `SetConnParams` fall through to the
    /// catch-all arm, which used to reply `Unsupported` without consulting the registry — so a
    /// non-owner got a different error kind than it does for every supported op, diverging from the
    /// Kotlin agent (whose `BleAgent` authorizes in every device-bearing branch).
    #[tokio::test]
    async fn non_owner_is_rejected_even_for_ops_this_agent_does_not_implement() {
        let fake = Arc::new(FakeBackend::default());
        let backend: Arc<dyn BleBackend> = fake.clone();
        let registry = PeripheralRegistry::new(LeaseConfig::default());
        registry
            .acquire_lease("dev", "owner", Default::default())
            .unwrap();
        registry.on_connected("dev", "owner");

        for op in [
            Op::ReadRssi {
                device: DeviceHandle {
                    value: "dev".into(),
                },
            },
            Op::SetConnParams {
                device: DeviceHandle {
                    value: "dev".into(),
                },
                profile: ConnProfile::Balanced,
                hint: None,
            },
        ] {
            let result =
                AgentServer::execute_op(op, context("other", &backend, &registry, 1)).await;
            assert!(
                matches!(result, OpResult::Err { error } if error.kind == ErrorKind::PeripheralBusy),
                "a non-owner must get PERIPHERAL_BUSY, not an answer about agent capabilities"
            );
        }
    }

    /// The owner, by contrast, gets the honest capability answer.
    #[tokio::test]
    async fn the_owner_still_gets_unsupported_for_ops_this_agent_does_not_implement() {
        let fake = Arc::new(FakeBackend::default());
        let backend: Arc<dyn BleBackend> = fake.clone();
        let registry = PeripheralRegistry::new(LeaseConfig::default());
        registry
            .acquire_lease("dev", "owner", Default::default())
            .unwrap();
        registry.on_connected("dev", "owner");

        let result = AgentServer::execute_op(
            Op::ReadRssi {
                device: DeviceHandle {
                    value: "dev".into(),
                },
            },
            context("owner", &backend, &registry, 1),
        )
        .await;
        assert!(matches!(result, OpResult::Err { error } if error.kind == ErrorKind::Unsupported));
    }

    // ---- write policy (U7) ----

    fn test_char() -> CharRef {
        CharRef {
            service: "180d".into(),
            characteristic: "2a39".into(),
            instance: 0,
        }
    }

    fn allowing_write(principal: &str) -> WritePolicy {
        WritePolicy::decode(
            &format!(
                r#"{{"version":1,"principals":{{"{principal}":{{"writes":[
                    {{"service":"180d","characteristic":"2a39","maximumBytes":1}}
                ]}}}}}}"#
            ),
            &HashSet::from([principal.to_string()]),
        )
        .unwrap()
    }

    fn denying_everything(principal: &str) -> WritePolicy {
        WritePolicy::decode(
            &format!(r#"{{"version":1,"principals":{{"{principal}":{{"writes":[]}}}}}}"#),
            &HashSet::from([principal.to_string()]),
        )
        .unwrap()
    }

    #[tokio::test]
    async fn permissive_policy_allows_a_write_by_default() {
        let fake = Arc::new(FakeBackend::default());
        let backend: Arc<dyn BleBackend> = fake.clone();
        let registry = PeripheralRegistry::new(LeaseConfig::default());
        registry
            .acquire_lease("dev", "owner", Default::default())
            .unwrap();
        registry.on_connected("dev", "owner");

        let result = AgentServer::execute_op(
            Op::Write {
                device: DeviceHandle {
                    value: "dev".into(),
                },
                char: test_char(),
                value: vec![0x01],
                with_response: true,
            },
            context_with_policy(
                "owner",
                &backend,
                &registry,
                1,
                WritePolicy::permissive(),
                &[],
            ),
        )
        .await;
        assert!(matches!(result, OpResult::Ok { .. }));
    }

    #[tokio::test]
    async fn a_configured_policy_denies_an_unlisted_or_empty_principal() {
        let fake = Arc::new(FakeBackend::default());
        let backend: Arc<dyn BleBackend> = fake.clone();
        let registry = PeripheralRegistry::new(LeaseConfig::default());
        registry
            .acquire_lease("dev", "owner", Default::default())
            .unwrap();
        registry.on_connected("dev", "owner");

        for policy in [
            denying_everything("owner"),
            // "owner" isn't in this policy at all — same outcome as an empty rule list.
            WritePolicy::decode(
                r#"{"version":1,"principals":{"someone-else":{"writes":[]}}}"#,
                &HashSet::from(["owner".to_string(), "someone-else".to_string()]),
            )
            .unwrap(),
        ] {
            let result = AgentServer::execute_op(
                Op::Write {
                    device: DeviceHandle {
                        value: "dev".into(),
                    },
                    char: test_char(),
                    value: vec![0x01],
                    with_response: true,
                },
                context_with_policy(
                    "owner",
                    &backend,
                    &registry,
                    1,
                    policy,
                    &[capabilities::WRITE_POLICY],
                ),
            )
            .await;
            assert!(matches!(
                result,
                OpResult::Err { error } if error.kind == ErrorKind::PolicyDenied
            ));
        }
    }

    #[tokio::test]
    async fn a_write_over_the_configured_byte_bound_is_denied() {
        let fake = Arc::new(FakeBackend::default());
        let backend: Arc<dyn BleBackend> = fake.clone();
        let registry = PeripheralRegistry::new(LeaseConfig::default());
        registry
            .acquire_lease("dev", "owner", Default::default())
            .unwrap();
        registry.on_connected("dev", "owner");

        let result = AgentServer::execute_op(
            Op::Write {
                device: DeviceHandle {
                    value: "dev".into(),
                },
                char: test_char(),
                value: vec![0x01, 0x02], // the rule allows at most 1 byte
                with_response: true,
            },
            context_with_policy(
                "owner",
                &backend,
                &registry,
                1,
                allowing_write("owner"),
                &[capabilities::WRITE_POLICY],
            ),
        )
        .await;
        assert!(matches!(
            result,
            OpResult::Err { error } if error.kind == ErrorKind::PolicyDenied
        ));
    }

    #[tokio::test]
    async fn policy_denied_requires_the_capability_otherwise_it_is_invalid_request() {
        // The crux: an unknown ErrorKind name would break a v1 client's decode, so a client that
        // never negotiated write.policy must not receive PolicyDenied at all.
        let fake = Arc::new(FakeBackend::default());
        let backend: Arc<dyn BleBackend> = fake.clone();
        let registry = PeripheralRegistry::new(LeaseConfig::default());
        registry
            .acquire_lease("dev", "owner", Default::default())
            .unwrap();
        registry.on_connected("dev", "owner");

        let write = || Op::Write {
            device: DeviceHandle {
                value: "dev".into(),
            },
            char: test_char(),
            value: vec![0xff], // exceeds the rule's 1-byte-of-a-different-value... still just over policy
            with_response: true,
        };

        let with_capability = AgentServer::execute_op(
            write(),
            context_with_policy(
                "owner",
                &backend,
                &registry,
                1,
                denying_everything("owner"),
                &[capabilities::WRITE_POLICY],
            ),
        )
        .await;
        assert!(matches!(
            with_capability,
            OpResult::Err { error } if error.kind == ErrorKind::PolicyDenied
        ));

        let without_capability = AgentServer::execute_op(
            write(),
            context_with_policy(
                "owner",
                &backend,
                &registry,
                2,
                denying_everything("owner"),
                &[],
            ),
        )
        .await;
        assert!(matches!(
            without_capability,
            OpResult::Err { error } if error.kind == ErrorKind::InvalidRequest
        ));
    }

    #[tokio::test]
    async fn peripheral_busy_is_answered_before_any_policy_check() {
        // Ordering matters: a caller that doesn't own the lease must never learn whether policy
        // would also have refused it — that would leak whether the policy permits a characteristic
        // on a device this caller cannot even touch.
        let fake = Arc::new(FakeBackend::default());
        let backend: Arc<dyn BleBackend> = fake.clone();
        let registry = PeripheralRegistry::new(LeaseConfig::default());
        registry
            .acquire_lease("dev", "owner", Default::default())
            .unwrap();
        registry.on_connected("dev", "owner");

        let result = AgentServer::execute_op(
            Op::Write {
                device: DeviceHandle {
                    value: "dev".into(),
                },
                char: test_char(),
                value: vec![0x01],
                with_response: true,
            },
            // "intruder" would also be denied by this policy, but must never find that out.
            context_with_policy(
                "intruder",
                &backend,
                &registry,
                1,
                denying_everything("intruder"),
                &[capabilities::WRITE_POLICY],
            ),
        )
        .await;
        assert!(matches!(
            result,
            OpResult::Err { error } if error.kind == ErrorKind::PeripheralBusy
        ));
    }

    #[tokio::test]
    async fn descriptor_writes_are_gated_independently_of_characteristic_writes() {
        let fake = Arc::new(FakeBackend::default());
        let backend: Arc<dyn BleBackend> = fake.clone();
        let registry = PeripheralRegistry::new(LeaseConfig::default());
        registry
            .acquire_lease("dev", "owner", Default::default())
            .unwrap();
        registry.on_connected("dev", "owner");

        let allowed_descriptor = test_descriptor();
        let policy = WritePolicy::decode(
            r#"{"version":1,"principals":{"owner":{"descriptorWrites":[{"service":"180d","characteristic":"2a37","descriptor":"2902","maximumBytes":2}]}}}"#,
            &HashSet::from(["owner".to_string()]),
        )
        .unwrap();
        let result = AgentServer::execute_op(
            Op::WriteDescriptor {
                device: DeviceHandle {
                    value: "dev".into(),
                },
                desc: allowed_descriptor.clone(),
                value: vec![0x01, 0x00],
            },
            context_with_policy(
                "owner",
                &backend,
                &registry,
                1,
                policy.clone(),
                &[capabilities::WRITE_POLICY],
            ),
        )
        .await;
        assert!(matches!(result, OpResult::Ok { .. }));
        assert_eq!(
            *fake.descriptor_writes.lock(),
            vec![(allowed_descriptor, vec![0x01, 0x00])]
        );

        let result = AgentServer::execute_op(
            Op::WriteDescriptor {
                device: DeviceHandle {
                    value: "dev".into(),
                },
                desc: DescRef {
                    descriptor: "2901".into(),
                    ..test_descriptor()
                },
                value: vec![0x01],
            },
            context_with_policy(
                "owner",
                &backend,
                &registry,
                2,
                policy,
                &[capabilities::WRITE_POLICY],
            ),
        )
        .await;
        assert!(matches!(
            result,
            OpResult::Err { error } if error.kind == ErrorKind::PolicyDenied
        ));
        assert_eq!(fake.descriptor_writes.lock().len(), 1);
    }

    #[tokio::test]
    async fn pair_and_unpair_are_gated_even_though_neither_is_implemented() {
        // Pairing itself is unimplemented here (parity: neither JVM, Android, nor Rust advertises
        // it today), so a policy that *allows* pairing still ends in UNSUPPORTED — but a policy
        // that denies it must short-circuit before that answer, not after.
        let fake = Arc::new(FakeBackend::default());
        let backend: Arc<dyn BleBackend> = fake.clone();
        let registry = PeripheralRegistry::new(LeaseConfig::default());
        registry
            .acquire_lease("dev", "owner", Default::default())
            .unwrap();
        registry.on_connected("dev", "owner");

        let denying = WritePolicy::decode(
            r#"{"version":1,"principals":{"owner":{"writes":[],"pairing":false}}}"#,
            &HashSet::from(["owner".to_string()]),
        )
        .unwrap();
        for op in [
            Op::Pair {
                device: DeviceHandle {
                    value: "dev".into(),
                },
            },
            Op::Unpair {
                device: DeviceHandle {
                    value: "dev".into(),
                },
            },
        ] {
            let result = AgentServer::execute_op(
                op,
                context_with_policy(
                    "owner",
                    &backend,
                    &registry,
                    1,
                    denying.clone(),
                    &[capabilities::WRITE_POLICY],
                ),
            )
            .await;
            assert!(matches!(
                result,
                OpResult::Err { error } if error.kind == ErrorKind::PolicyDenied
            ));
        }

        let allowing = WritePolicy::decode(
            r#"{"version":1,"principals":{"owner":{"writes":[],"pairing":true}}}"#,
            &HashSet::from(["owner".to_string()]),
        )
        .unwrap();
        let result = AgentServer::execute_op(
            Op::Pair {
                device: DeviceHandle {
                    value: "dev".into(),
                },
            },
            context_with_policy(
                "owner",
                &backend,
                &registry,
                2,
                allowing,
                &[capabilities::WRITE_POLICY],
            ),
        )
        .await;
        assert!(matches!(result, OpResult::Err { error } if error.kind == ErrorKind::Unsupported));
    }

    #[tokio::test]
    async fn write_policy_enforced_reflects_whether_a_policy_is_configured() {
        let fake = Arc::new(FakeBackend::default());
        let backend: Arc<dyn BleBackend> = fake.clone();
        let registry = PeripheralRegistry::new(LeaseConfig::default());

        let permissive_status = AgentServer::agent_status(
            "owner",
            &registry,
            &Arc::new(HandleTranslator::new(
                agent_identifier_format(),
                Arc::new(AtomicBool::new(false)),
            )),
            ScanConcurrencyMode::Multiplexed,
            &StatusContext {
                source: Arc::new(StatusSource {
                    started_at: Instant::now(),
                    strict_identifiers: Arc::new(AtomicBool::new(false)),
                    live_sessions: Arc::new(LiveSessionRegistry::default()),
                    device_names: Arc::new(DeviceNames::default()),
                    write_policy: Arc::new(WritePolicy::permissive()),
                }),
                operator_scope: false,
            },
        )
        .await;
        assert!(!permissive_status.settings.write_policy_enforced);

        let enforced_status = AgentServer::agent_status(
            "owner",
            &registry,
            &Arc::new(HandleTranslator::new(
                agent_identifier_format(),
                Arc::new(AtomicBool::new(false)),
            )),
            ScanConcurrencyMode::Multiplexed,
            &StatusContext {
                source: Arc::new(StatusSource {
                    started_at: Instant::now(),
                    strict_identifiers: Arc::new(AtomicBool::new(false)),
                    live_sessions: Arc::new(LiveSessionRegistry::default()),
                    device_names: Arc::new(DeviceNames::default()),
                    write_policy: Arc::new(denying_everything("owner")),
                }),
                operator_scope: false,
            },
        )
        .await;
        assert!(enforced_status.settings.write_policy_enforced);
        let _ = backend; // kept for symmetry with the rest of this module's tests
    }

    /// LEASE-DISCONNECT-01: `Op::Disconnect` releases the lease immediately, with no transport
    /// grace window — contrast with `registry::peripheral_lease::tests::
    /// reconnect_within_grace_keeps_lease_and_skips_teardown` (LEASE-GRACE-01), where the same
    /// device stays denied to another client until the grace timer elapses.
    #[tokio::test]
    async fn explicit_disconnect_releases_immediately_and_cannot_resume() {
        let fake = Arc::new(FakeBackend::default());
        let backend: Arc<dyn BleBackend> = fake.clone();
        let registry = PeripheralRegistry::new(LeaseConfig::default());
        registry
            .acquire_lease("dev", "owner", Default::default())
            .unwrap();
        registry.on_connected("dev", "owner");

        let result = AgentServer::execute_op(
            Op::Disconnect {
                device: DeviceHandle {
                    value: "dev".into(),
                },
            },
            context("owner", &backend, &registry, 1),
        )
        .await;
        assert!(matches!(result, OpResult::Ok { .. }));
        assert_eq!(fake.disconnects.load(Ordering::Relaxed), 1);

        // No grace window: another client can acquire the same device right away, and the
        // original owner can no longer act on it without a fresh Connect.
        assert!(
            registry
                .acquire_lease("dev", "someone-else", Default::default())
                .is_ok()
        );
        assert!(
            registry
                .authorize_connected("dev", "owner", Default::default())
                .is_err()
        );
    }

    #[tokio::test]
    async fn guaranteed_scans_use_the_coordinator_physical_stream() {
        let fake = Arc::new(FakeBackend::default());
        let backend: Arc<dyn BleBackend> = fake.clone();
        let registry = PeripheralRegistry::new(LeaseConfig::default());
        for (client, generation) in [("a", 1), ("b", 2)] {
            let result = AgentServer::execute_op(
                Op::ScanStart {
                    scan_id: 1,
                    filters: vec![],
                },
                context(client, &backend, &registry, generation),
            )
            .await;
            assert!(matches!(result, OpResult::Ok { .. }));
        }
        assert_eq!(
            *fake.scans.lock(),
            vec![
                StreamKey {
                    connection: 0,
                    local_id: i64::MIN
                },
                StreamKey {
                    connection: 0,
                    local_id: i64::MIN
                }
            ]
        );
    }

    #[test]
    fn configured_mode_is_the_only_advertised_scan_concurrency_capability() {
        let supported = supported_capabilities(
            vec![
                crate::protocol::frame::capabilities::SCAN_CONCURRENCY_MULTIPLEXED.into(),
                crate::protocol::frame::capabilities::SCAN_CONCURRENCY_SINGLE.into(),
                "other".into(),
            ],
            ScanConcurrencyMode::Uncontrolled,
        );
        assert_eq!(
            supported,
            vec![
                "other".to_string(),
                crate::protocol::frame::capabilities::SCAN_CONCURRENCY_UNCONTROLLED.into(),
            ]
        );
    }

    #[tokio::test]
    async fn same_local_observation_id_is_isolated_and_stop_targets_its_owner() {
        let fake = Arc::new(FakeBackend::default());
        let backend: Arc<dyn BleBackend> = fake.clone();
        let registry = PeripheralRegistry::new(LeaseConfig::default());
        for (client, device, generation) in [("a", "dev-a", 1), ("b", "dev-b", 2)] {
            registry
                .acquire_lease(device, client, Default::default())
                .unwrap();
            registry.on_connected(device, client);
            let _ = AgentServer::execute_op(
                Op::ObserveStart {
                    sub_id: 7,
                    device: DeviceHandle {
                        value: device.into(),
                    },
                    char: CharRef {
                        service: "s".into(),
                        characteristic: "c".into(),
                        instance: 0,
                    },
                },
                context(client, &backend, &registry, generation),
            )
            .await;
        }
        let _ = AgentServer::execute_op(
            Op::ObserveStop { sub_id: 7 },
            context("a", &backend, &registry, 1),
        )
        .await;
        assert_eq!(
            *fake.observations.lock(),
            vec![
                StreamKey {
                    connection: 1,
                    local_id: 7
                },
                StreamKey {
                    connection: 2,
                    local_id: 7
                }
            ]
        );
        assert_eq!(
            *fake.stopped_observations.lock(),
            vec![StreamKey {
                connection: 1,
                local_id: 7
            }]
        );
    }

    #[tokio::test]
    async fn mtu_is_reported_as_unsupported_when_the_backend_cannot_verify_it() {
        let fake = Arc::new(FakeBackend::default());
        let backend: Arc<dyn BleBackend> = fake;
        let registry = PeripheralRegistry::new(LeaseConfig::default());
        registry
            .acquire_lease("dev", "owner", Default::default())
            .unwrap();
        registry.on_connected("dev", "owner");
        let result = AgentServer::execute_op(
            Op::RequestMtu {
                device: DeviceHandle {
                    value: "dev".into(),
                },
                mtu: 185,
            },
            context("owner", &backend, &registry, 1),
        )
        .await;
        assert!(matches!(result, OpResult::Err { error } if error.kind == ErrorKind::Unsupported));
    }

    #[tokio::test]
    async fn operation_limits_are_invalid_requests_without_calling_the_backend() {
        let fake = Arc::new(FakeBackend::default());
        let backend: Arc<dyn BleBackend> = fake.clone();
        let registry = PeripheralRegistry::new(LeaseConfig::default());
        let char = CharRef {
            service: "180d".into(),
            characteristic: "2a37".into(),
            instance: 0,
        };
        let descriptor = DescRef {
            service: char.service.clone(),
            characteristic: char.characteristic.clone(),
            descriptor: "2902".into(),
            instance: 0,
        };
        let cases = [
            Op::ScanStart {
                scan_id: 1,
                filters: vec![
                    ScanFilter {
                        service: None,
                        name: None
                    };
                    MAX_SCAN_FILTERS + 1
                ],
            },
            Op::Write {
                device: DeviceHandle {
                    value: "dev".into(),
                },
                char: char.clone(),
                value: vec![0; MAX_WRITE_BYTES + 1],
                with_response: true,
            },
            Op::WriteDescriptor {
                device: DeviceHandle {
                    value: "dev".into(),
                },
                desc: descriptor,
                value: vec![0; MAX_WRITE_BYTES + 1],
            },
            Op::RequestMtu {
                device: DeviceHandle {
                    value: "dev".into(),
                },
                mtu: MAX_MTU + 1,
            },
            Op::RequestMtu {
                device: DeviceHandle {
                    value: "dev".into(),
                },
                mtu: MIN_MTU - 1,
            },
        ];

        for op in cases {
            let result =
                AgentServer::execute_op(op, context("owner", &backend, &registry, 1)).await;
            assert!(
                matches!(result, OpResult::Err { error } if error.kind == ErrorKind::InvalidRequest)
            );
        }
        assert!(fake.scans.lock().is_empty());
    }

    #[tokio::test]
    async fn cancelled_write_reservation_unblocks_the_next_write() {
        let tails = parking_lot::Mutex::new(HashMap::new());
        let first = reserve_write(&tails, "dev");
        let mut second = reserve_write(&tails, "dev");
        drop(first);
        assert!(second.predecessor.take().unwrap().await.is_err());
        release_write_tail(&tails, &second);
        drop(second);
        assert!(tails.lock().is_empty());
    }

    #[tokio::test]
    async fn late_connect_after_connection_retirement_unwinds_radio_and_lease() {
        let fake = Arc::new(FakeBackend::default());
        let backend: Arc<dyn BleBackend> = fake.clone();
        let registry = PeripheralRegistry::new(LeaseConfig::default());
        let live = AtomicBool::new(false);
        let result = AgentServer::execute_op(
            Op::Connect {
                device: DeviceHandle {
                    value: "dev".into(),
                },
            },
            context_with_liveness("owner", &backend, &registry, 1, &live),
        )
        .await;
        assert!(
            matches!(result, OpResult::Err { error } if error.kind == ErrorKind::TransportLost)
        );
        assert_eq!(fake.connects.load(Ordering::Relaxed), 1);
        assert_eq!(fake.disconnects.load(Ordering::Relaxed), 1);
        assert!(registry.held_by("owner").is_empty());
    }
}
