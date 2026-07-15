use futures_util::{SinkExt, StreamExt};
use std::collections::HashMap;
use std::net::SocketAddr;
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::time::{Duration, Instant};
use tokio::net::TcpListener;
use tokio::sync::{mpsc, oneshot};
use tokio::task::JoinSet;
use tokio_tungstenite::tungstenite::handshake::server::{Request, Response};
use tokio_tungstenite::tungstenite::protocol::Message;
use tracing::Instrument;

use crate::ble::backend::{BleBackend, StreamKey};
use crate::protocol::{
    codec::{decode_cbor, encode_cbor},
    errors::{AgentError, ErrorKind},
    events::AgentEvent,
    frame::{Frame, PROTOCOL_VERSION},
    op::Op,
    results::OpResult,
};
use crate::registry::peripheral_lease::PeripheralRegistry;
use crate::translate::{HandleTranslator, agent_identifier_format};
use crate::transport::negotiation::{HelloRequest, Negotiation};

/// Outbound frame buffer per connection. Bounds memory for a slow/stalled client.
const FRAME_CHANNEL_CAP: usize = 512;
/// Max commands executing concurrently per connection. Caps spawned tasks so a command
/// flood can't exhaust memory; the read loop backpressures (stops accepting) once hit.
const MAX_INFLIGHT_OPS: usize = 64;
/// How often the agent pings an otherwise-idle client to probe liveness.
const PING_PERIOD: Duration = Duration::from_secs(15);
/// Close a connection if nothing is heard from the peer for this long (covers a missed
/// pong plus jitter — comfortably more than [PING_PERIOD]).
const LIVENESS_TIMEOUT: Duration = Duration::from_secs(40);
/// Protocol scan/subscription IDs are scoped to a client connection. This monotonically assigned
/// generation makes their backend keys unique even when clients reuse the same local IDs.
static NEXT_CONNECTION_GENERATION: AtomicU64 = AtomicU64::new(1);
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
    event_tx: mpsc::UnboundedSender<AgentEvent>,
    connection_live: &'a AtomicBool,
    stream_connection: u64,
}

pub struct ServerConfig {
    pub addr: SocketAddr,
    pub auth_token: Option<String>,
    /// Agent-wide identifier strict-mode switch (capability `identifier.translate`). Shared across
    /// connections; when set, handles pass through untranslated.
    pub strict_identifiers: Arc<AtomicBool>,
}

pub struct AgentServer {
    config: ServerConfig,
    backend: Arc<dyn BleBackend>,
    registry: PeripheralRegistry,
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
            let auth_token = self.config.auth_token.clone();
            let strict = self.config.strict_identifiers.clone();

            tokio::spawn(async move {
                let mut client_id = format!("anon-{}", peer_addr);
                let mut authorized = auth_token.is_none();

                let callback =
                    |req: &Request,
                     response: Response|
                     -> Result<Response, http::Response<Option<String>>> {
                        if let Some(token) = &auth_token {
                            if let Some(auth_hdr) = req.headers().get("Authorization")
                                && let Ok(str_val) = auth_hdr.to_str()
                                && str_val == format!("Bearer {}", token)
                            {
                                authorized = true;
                            }
                        } else {
                            authorized = true;
                        }

                        if let Some(cid_hdr) = req.headers().get("X-RemoteBle-Client")
                            && let Ok(str_val) = cid_hdr.to_str()
                        {
                            client_id = str_val.to_string();
                        }

                        if !authorized {
                            tracing::warn!("client rejected from {}: unauthorized", peer_addr);
                            let rejected = http::Response::builder()
                                .status(401)
                                .body(Some("Unauthorized".to_string()))
                                .unwrap();
                            return Err(rejected);
                        }

                        Ok(response)
                    };

                match tokio_tungstenite::accept_hdr_async(stream, callback).await {
                    Ok(ws_stream) => {
                        let span =
                            tracing::info_span!("conn", client = %client_id, peer = %peer_addr);
                        tracing::info!(parent: &span, "Client connected");
                        Self::handle_connection(ws_stream, client_id, backend, registry, strict)
                            .instrument(span)
                            .await;
                    }
                    Err(e) => {
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
        let (frame_tx, mut frame_rx) = mpsc::channel::<Frame>(FRAME_CHANNEL_CAP);
        let inflight = Arc::new(tokio::sync::Semaphore::new(MAX_INFLIGHT_OPS));
        // Every command task belongs to this WebSocket generation. Retiring the connection aborts
        // and joins the set before lease cleanup, so no detached command can later resurrect an
        // abandoned connection/lease.
        let connection_live = Arc::new(AtomicBool::new(true));
        let mut command_tasks = JoinSet::new();
        let stream_connection = NEXT_CONNECTION_GENERATION.fetch_add(1, Ordering::Relaxed);
        // Reserved in the sequential receive loop, rather than inside tasks, to preserve command
        // receive order for writes to the same physical device while other work stays concurrent.
        let write_tails = Arc::new(parking_lot::Mutex::new(HashMap::new()));

        // Liveness: a client that vanishes without a TCP FIN (Wi-Fi drop, NAT timeout, sleep)
        // would otherwise hold its slot/lease until the OS keepalive fires (minutes). We ping
        // periodically and close the link if we've heard nothing — including the auto-pong
        // tungstenite sends for our ping — within LIVENESS_TIMEOUT. Updated on every inbound frame.
        let last_activity = Arc::new(parking_lot::Mutex::new(Instant::now()));
        let last_activity_send = last_activity.clone();

        let send_task = tokio::spawn(async move {
            let mut ping = tokio::time::interval(PING_PERIOD);
            ping.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);
            loop {
                tokio::select! {
                    maybe_frame = frame_rx.recv() => {
                        match maybe_frame {
                            Some(frame) => {
                                if let Ok(bytes) = encode_cbor(&frame)
                                    && ws_sender.send(Message::Binary(bytes)).await.is_err()
                                {
                                    break;
                                }
                            }
                            None => break,
                        }
                    }
                    _ = ping.tick() => {
                        if last_activity_send.lock().elapsed() > LIVENESS_TIMEOUT {
                            tracing::info!("client idle past liveness timeout; closing");
                            let _ = ws_sender.send(Message::Close(None)).await;
                            break;
                        }
                        if ws_sender.send(Message::Ping(Vec::new())).await.is_err() {
                            break;
                        }
                    }
                }
            }
        });

        let (event_tx, mut event_rx) = mpsc::unbounded_channel::<AgentEvent>();
        let frame_tx_event = frame_tx.clone();
        let translator_event = translator.clone();
        let event_task = tokio::spawn(async move {
            while let Some(event) = event_rx.recv().await {
                // Forward-translate the real handle the event carries into the client's format, then
                // shed rather than block the radio when the client can't keep up.
                let event = translator_event.to_client_event(event);
                match frame_tx_event.try_send(Frame::Event { event }) {
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

        while let Some(msg_res) = ws_receiver.next().await {
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
                                    version: PROTOCOL_VERSION,
                                    capabilities: caps,
                                    agent_info: Some(
                                        concat!("RemoteBle-Agent-RS ", env!("CARGO_PKG_VERSION"))
                                            .into(),
                                    ),
                                };
                                let _ = frame_tx.send(reply_frame).await;
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
                                            },
                                        )
                                        .await;
                                        // Releasing the sender starts the next reserved write
                                        // before a slow client can delay it on reply backpressure.
                                        if let Some(reservation) = &write_reservation {
                                            release_write_tail(&write_tails, reservation);
                                        }
                                        drop(write_reservation);
                                        let _ = frame_tx.send(Frame::Reply { cid, result }).await;
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

        // Transport gone: keep this client's links warm and let the registry release them on
        // grace-expiry (a reconnect within the window resumes). Stop both pump tasks — the event
        // pump would otherwise outlive the connection, kept alive by event_tx clones still held
        // in the backend's `connected` map.
        registry.on_transport_drop(&client_id);
        tracing::info!("Client disconnected: {}", client_id);
        send_task.abort();
        event_task.abort();
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
        } = context;
        match op {
            Op::ScanStart { scan_id, filters } => OpResult::from_unit(
                backend
                    .start_scan(
                        StreamKey {
                            connection: stream_connection,
                            local_id: scan_id,
                        },
                        filters,
                        event_tx,
                    )
                    .await,
            ),
            Op::ScanStop { scan_id } => OpResult::from_unit(
                backend
                    .stop_scan(StreamKey {
                        connection: stream_connection,
                        local_id: scan_id,
                    })
                    .await,
            ),
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
                if !(23..=517).contains(&mtu) {
                    return OpResult::err(AgentError::new(
                        ErrorKind::Unsupported,
                        Some("requested MTU must be between 23 and 517".into()),
                    ));
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
                OpResult::from_unit(
                    backend
                        .start_observe(
                            StreamKey {
                                connection: stream_connection,
                                local_id: sub_id,
                            },
                            &device,
                            &char,
                            event_tx,
                        )
                        .await,
                )
            }
            Op::ObserveStop { sub_id } => OpResult::from_unit(
                backend
                    .stop_observe(StreamKey {
                        connection: stream_connection,
                        local_id: sub_id,
                    })
                    .await,
            ),
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
    use std::sync::atomic::AtomicUsize;

    #[derive(Default)]
    struct FakeBackend {
        scans: Mutex<Vec<StreamKey>>,
        observations: Mutex<Vec<StreamKey>>,
        stopped_observations: Mutex<Vec<StreamKey>>,
        reads: AtomicUsize,
        connects: AtomicUsize,
        disconnects: AtomicUsize,
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
            _tx: mpsc::UnboundedSender<AgentEvent>,
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
            _tx: mpsc::UnboundedSender<AgentEvent>,
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
            _tx: mpsc::UnboundedSender<AgentEvent>,
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
        let (event_tx, _) = mpsc::unbounded_channel();
        ExecuteContext {
            client_id,
            backend,
            registry,
            translator,
            event_tx,
            connection_live: live,
            stream_connection: generation,
        }
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
