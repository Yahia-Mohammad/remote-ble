use futures_util::{SinkExt, StreamExt};
use std::borrow::Cow;
use std::collections::{HashMap, HashSet};
use std::net::{IpAddr, SocketAddr};
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::time::{Duration, Instant};
use tokio::net::TcpListener;
use tokio::sync::{mpsc, oneshot};
use tokio::task::JoinSet;
use tokio_tungstenite::tungstenite::handshake::server::{Request, Response};
use tokio_tungstenite::tungstenite::protocol::{
    CloseFrame, Message, WebSocketConfig, frame::coding::CloseCode,
};
use tracing::Instrument;

use crate::ble::backend::{BleBackend, StreamKey};
use crate::protocol::{
    codec::{decode_cbor, encode_cbor},
    errors::{AgentError, ErrorKind},
    events::AgentEvent,
    frame::Frame,
    op::Op,
    results::OpResult,
};
use crate::registry::peripheral_lease::PeripheralRegistry;
use crate::translate::{HandleTranslator, agent_identifier_format};
use crate::transport::negotiation::{
    HelloRequest, Negotiation, ProtocolVersionSelection, select_protocol_version,
};

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
    WebSocketConfig {
        max_message_size: Some(MAX_FRAME_BYTES),
        max_frame_size: Some(MAX_FRAME_BYTES),
        ..Default::default()
    }
}
static NEXT_WRITE_SEQUENCE: AtomicU64 = AtomicU64::new(1);

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

struct ExecuteContext<'a> {
    client_id: &'a str,
    backend: &'a Arc<dyn BleBackend>,
    registry: &'a PeripheralRegistry,
    translator: &'a Arc<HandleTranslator>,
    event_tx: mpsc::Sender<AgentEvent>,
    connection_live: &'a AtomicBool,
    stream_connection: u64,
    streams: &'a StreamReservations,
}

/// Per-connection reservations for streaming BLE work. Backend stream keys are scoped to the
/// WebSocket generation, but backend implementations retain their own registrations, so cap and
/// reserve them before starting work rather than relying on command concurrency.
#[derive(Default)]
struct StreamReservations {
    scans: parking_lot::Mutex<HashSet<StreamKey>>,
    observations: parking_lot::Mutex<HashSet<StreamKey>>,
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

fn authenticate(
    credentials: &HashMap<String, String>,
    authorization: Option<&str>,
) -> Option<String> {
    if credentials.is_empty() {
        return Some("anonymous".to_string());
    }
    let bearer = authorization?.strip_prefix("Bearer ")?;
    credentials
        .iter()
        .find(|(_, secret)| constant_time_eq(secret, bearer))
        .map(|(principal, _)| principal.clone())
}

fn session_key(principal: &str, client_id: &str) -> String {
    format!("{principal}\0{client_id}")
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
}

impl AgentServer {
    pub fn new(
        config: ServerConfig,
        backend: Arc<dyn BleBackend>,
        registry: PeripheralRegistry,
    ) -> Self {
        Self {
            config,
            backend,
            registry,
            live_sessions: Arc::new(LiveSessionRegistry::default()),
            failed_auth_limiter: Arc::new(AuthFailureLimiter::default()),
        }
    }

    // The WebSocket handshake callback must return `http::Response` in its Err
    // arm (tungstenite's API), which clippy flags as a large Err variant. The
    // type is fixed by the upstream signature, so the lint doesn't apply here.
    #[allow(clippy::result_large_err)]
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

            tokio::spawn(async move {
                let mut client_id = format!("anon-{}", peer_addr);
                let mut principal = None;
                let mut session_generation = None;

                let callback =
                    |req: &Request,
                     response: Response|
                     -> Result<Response, http::Response<Option<String>>> {
                        principal = authenticate(
                            credentials.as_ref(),
                            req.headers()
                                .get("Authorization")
                                .and_then(|header| header.to_str().ok()),
                        );

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
                        if client_id.trim().is_empty()
                            || client_id.len() > 128
                            || client_id.contains('\0')
                        {
                            let rejected = http::Response::builder()
                                .status(400)
                                .body(Some("Invalid X-RemoteBle-Client".to_string()))
                                .unwrap();
                            return Err(rejected);
                        }
                        client_id = session_key(principal, &client_id);
                        let generation = NEXT_SESSION_GENERATION.fetch_add(1, Ordering::Relaxed);
                        if !live_sessions.try_acquire(&client_id, generation) {
                            tracing::warn!(
                                "client rejected from {}: duplicate live session",
                                peer_addr
                            );
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
                        Self::handle_connection(
                            ws_stream,
                            client_id,
                            backend,
                            registry,
                            strict,
                            live_sessions,
                            session_generation.expect("accepted session must have a generation"),
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
            });
        }
    }

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
        // Reserved in the sequential receive loop, rather than inside tasks, to preserve command
        // receive order for writes to the same physical device while other work stays concurrent.
        let write_tails = Arc::new(parking_lot::Mutex::new(HashMap::new()));

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
                                            && ws_sender.send(Message::Binary(bytes)).await.is_err()
                                        {
                                            break "writer send failed";
                                        }
                                    }
                                    Outbound::IncompatibleProtocolClose => {
                                        let _ = ws_sender.send(Message::Close(Some(CloseFrame {
                                            code: CloseCode::Protocol,
                                            reason: Cow::Borrowed(INCOMPATIBLE_PROTOCOL_CLOSE_REASON),
                                        }))).await;
                                        break "protocol incompatible";
                                    }
                                    Outbound::FrameTooLargeClose => {
                                        let _ = ws_sender.send(Message::Close(Some(CloseFrame {
                                            code: CloseCode::Size,
                                            reason: Cow::Borrowed(FRAME_TOO_LARGE_CLOSE_REASON),
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
                        if ws_sender.send(Message::Ping(Vec::new())).await.is_err() {
                            break "ping failed";
                        }
                    }
                }
            };
            // A full channel already contains a terminal signal; either way the reader will
            // retire this generation exactly once.
            let _ = terminal_tx.try_send(reason);
        });

        let (event_tx, mut event_rx) = mpsc::channel::<AgentEvent>(EVENT_CHANNEL_CAP);
        let frame_tx_event = frame_tx.clone();
        let translator_event = translator.clone();
        let event_task = tokio::spawn(async move {
            while let Some(event) = event_rx.recv().await {
                // Forward-translate the real handle the event carries into the client's format, then
                // shed rather than block the radio when the client can't keep up.
                let event = translator_event.to_client_event(event);
                match frame_tx_event.try_send(Outbound::Frame(Frame::Event { event })) {
                    Ok(()) => {}
                    Err(mpsc::error::TrySendError::Full(_)) => {
                        tracing::warn!("outbound frame buffer full; dropping event")
                    }
                    Err(mpsc::error::TrySendError::Closed(_)) => break,
                }
            }
        });

        // Per-connection handshake state — first hello wins; see [Negotiation].
        let mut negotiation = Negotiation::new();
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
                                    || backend.capabilities(),
                                    || registry.held_by(&client_id),
                                );
                                let reply_frame = Frame::ServerHello {
                                    version,
                                    capabilities: caps,
                                    agent_info: Some(
                                        concat!("RemoteBle-Agent-RS ", env!("CARGO_PKG_VERSION"))
                                            .into(),
                                    ),
                                };
                                let _ = frame_tx.send(Outbound::Frame(reply_frame)).await;
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
                                let streams = streams.clone();
                                let op = translator.to_real_op(op);
                                let write_reservation = match &op {
                                    Op::Write { device, .. } => {
                                        Some(reserve_write(&write_tails, &device.value))
                                    }
                                    _ => None,
                                };
                                command_tasks.spawn(
                                    async move {
                                        let _permit = permit;
                                        let mut write_reservation = write_reservation;
                                        if let Some(reservation) = &mut write_reservation
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
                                            },
                                        )
                                        .await;
                                        // Releasing the sender starts the next reserved write
                                        // before a slow client can delay it on reply backpressure.
                                        if let Some(reservation) = &write_reservation {
                                            release_write_tail(&write_tails, reservation);
                                        }
                                        drop(write_reservation);
                                        let _ = frame_tx
                                            .send(Outbound::Frame(Frame::Reply { cid, result }))
                                            .await;
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

        if let Err(e) = backend.stop_connection_streams(stream_connection).await {
            tracing::warn!("failed to stop connection-owned BLE streams: {}", e);
        }
        streams.clear();

        // Stop and join every connection-owned pump before scheduling grace. Otherwise an old
        // writer/event task can outlive cleanup and send or mutate state after a new generation
        // reconnects under the same stable identity.
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
        } = context;
        match op {
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
            Op::ScanStart { scan_id, filters } => {
                let stream = StreamKey {
                    connection: stream_connection,
                    local_id: scan_id,
                };
                let inserted = match streams.reserve_scan(stream) {
                    Ok(inserted) => inserted,
                    Err(error) => return OpResult::err(error),
                };
                let result = backend.start_scan(stream, filters, event_tx).await;
                if result.is_err() && inserted {
                    streams.release_scan(stream);
                }
                OpResult::from_unit(result)
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
                if let Err(e) = registry.acquire_lease(&device.value, client_id) {
                    return OpResult::err(e);
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
                if let Err(e) = registry.authorize_connected(&device.value, client_id) {
                    return OpResult::err(e);
                }
                registry.release_lease(&device.value, client_id);
                translator.evict(&device.value);
                OpResult::from_unit(backend.disconnect(&device).await)
            }
            Op::Discover { device } => {
                if let Err(e) = registry.authorize_connected(&device.value, client_id) {
                    return OpResult::err(e);
                }
                OpResult::from_payload(backend.discover(&device).await)
            }
            Op::Read { device, char } => {
                if let Err(e) = registry.authorize_connected(&device.value, client_id) {
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
                if let Err(e) = registry.authorize_connected(&device.value, client_id) {
                    return OpResult::err(e);
                }
                OpResult::from_unit(backend.write(&device, &char, &value, with_response).await)
            }
            Op::RequestMtu { device, mtu } => {
                if let Err(e) = registry.authorize_connected(&device.value, client_id) {
                    return OpResult::err(e);
                }
                OpResult::from_payload(backend.request_mtu(&device, mtu).await)
            }
            Op::ObserveStart {
                sub_id,
                device,
                char,
            } => {
                if let Err(e) = registry.authorize_connected(&device.value, client_id) {
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
            _ => OpResult::err(AgentError::new(
                ErrorKind::Unsupported,
                Some("Operation not supported on this agent".into()),
            )),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::protocol::{
        op::{CharRef, DeviceHandle, ScanFilter},
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
        assert_eq!(
            authenticate(&credentials, Some("Bearer secret-a")),
            Some("alpha".to_string())
        );
        assert_eq!(authenticate(&credentials, Some("Bearer wrong")), None);
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
            .send(Message::Binary(vec![0; MAX_FRAME_BYTES + 1]))
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

    #[derive(Default)]
    struct FakeBackend {
        scans: Mutex<Vec<StreamKey>>,
        observations: Mutex<Vec<StreamKey>>,
        stopped_observations: Mutex<Vec<StreamKey>>,
        reads: AtomicUsize,
        connects: AtomicUsize,
        disconnects: AtomicUsize,
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
            _filters: Vec<ScanFilter>,
            _tx: mpsc::Sender<AgentEvent>,
        ) -> Result<(), AgentError> {
            self.scans.lock().push(stream);
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
        ExecuteContext {
            client_id,
            backend,
            registry,
            translator,
            event_tx,
            connection_live: live,
            stream_connection: generation,
            streams,
        }
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
        registry.acquire_lease("dev", "owner").unwrap();
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

    #[tokio::test]
    async fn same_local_scan_id_is_isolated_by_connection_generation() {
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
                    connection: 1,
                    local_id: 1
                },
                StreamKey {
                    connection: 2,
                    local_id: 1
                }
            ]
        );
    }

    #[tokio::test]
    async fn same_local_observation_id_is_isolated_and_stop_targets_its_owner() {
        let fake = Arc::new(FakeBackend::default());
        let backend: Arc<dyn BleBackend> = fake.clone();
        let registry = PeripheralRegistry::new(LeaseConfig::default());
        for (client, device, generation) in [("a", "dev-a", 1), ("b", "dev-b", 2)] {
            registry.acquire_lease(device, client).unwrap();
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
        registry.acquire_lease("dev", "owner").unwrap();
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
            Op::RequestMtu {
                device: DeviceHandle {
                    value: "dev".into(),
                },
                mtu: MAX_MTU + 1,
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
