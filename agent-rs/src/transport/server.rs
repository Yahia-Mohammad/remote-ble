use futures_util::{SinkExt, StreamExt};
use std::net::SocketAddr;
use std::sync::Arc;
use std::sync::atomic::AtomicBool;
use std::time::{Duration, Instant};
use tokio::net::TcpListener;
use tokio::sync::mpsc;
use tokio_tungstenite::tungstenite::handshake::server::{Request, Response};
use tokio_tungstenite::tungstenite::protocol::Message;

use crate::ble::backend::BleBackend;
use crate::protocol::{
    codec::{decode_cbor, encode_cbor},
    errors::{AgentError, ErrorKind},
    events::AgentEvent,
    frame::{Frame, PROTOCOL_VERSION, capabilities},
    op::Op,
    results::OpResult,
};
use crate::registry::peripheral_lease::PeripheralRegistry;
use crate::translate::{HandleTranslator, agent_identifier_format};

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
                        tracing::info!(
                            "Client connected: {} (client_id: {})",
                            peer_addr,
                            client_id
                        );
                        Self::handle_connection(ws_stream, client_id, backend, registry, strict)
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

        let mut _negotiated_caps = std::collections::BTreeSet::new();

        while let Some(msg_res) = ws_receiver.next().await {
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
                        tracing::debug!("Decoded frame: {:?}", frame);
                        match frame {
                            Frame::ClientHello {
                                min_version: _,
                                max_version: _,
                                capabilities: wanted,
                                identifier_format,
                            } => {
                                // Negotiated set = clientWanted ∩ agentSupported. We only
                                // advertise what the backend actually implements, so a client
                                // never negotiates a capability we'd then answer UNSUPPORTED —
                                // plus the agent-level `identifier.translate` (radio-independent).
                                let mut supported: std::collections::BTreeSet<String> =
                                    backend.capabilities().into_iter().collect();
                                supported.insert(capabilities::IDENTIFIER_TRANSLATION.to_string());
                                _negotiated_caps =
                                    wanted.intersection(&supported).cloned().collect();
                                translator.configure(
                                    identifier_format,
                                    _negotiated_caps.contains(capabilities::IDENTIFIER_TRANSLATION),
                                );
                                let reply_frame = Frame::ServerHello {
                                    version: PROTOCOL_VERSION,
                                    capabilities: _negotiated_caps.clone(),
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
                                tokio::spawn(async move {
                                    let _permit = permit;
                                    // Reverse-translate the op's client-facing handle back to the
                                    // real radio handle up front, so the registry and backend deal
                                    // only in real handles.
                                    let op = translator.to_real_op(op);
                                    let result = Self::execute_op(
                                        op,
                                        &client_id,
                                        &backend,
                                        &registry,
                                        &translator,
                                        event_tx,
                                    )
                                    .await;
                                    let _ = frame_tx.send(Frame::Reply { cid, result }).await;
                                });
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

        // Transport gone: keep this client's links warm and let the registry release them on
        // grace-expiry (a reconnect within the window resumes). Stop both pump tasks — the event
        // pump would otherwise outlive the connection, kept alive by event_tx clones still held
        // in the backend's `connected` map.
        registry.on_transport_drop(&client_id);
        tracing::info!("Client disconnected: {}", client_id);
        send_task.abort();
        event_task.abort();
    }

    async fn execute_op(
        op: Op,
        client_id: &str,
        backend: &Arc<dyn BleBackend>,
        registry: &PeripheralRegistry,
        translator: &Arc<HandleTranslator>,
        event_tx: mpsc::UnboundedSender<AgentEvent>,
    ) -> OpResult {
        match op {
            Op::ScanStart {
                scan_id,
                filters: _,
            } => OpResult::from_unit(backend.start_scan(scan_id, event_tx).await),
            Op::ScanStop { scan_id } => OpResult::from_unit(backend.stop_scan(scan_id).await),
            Op::Connect { device } => {
                if let Err(e) = registry.acquire_lease(&device.value, client_id) {
                    return OpResult::err(e);
                }
                match backend.connect(&device, event_tx).await {
                    Ok(_) => {
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
                registry.release_lease(&device.value, client_id);
                translator.evict(&device.value);
                OpResult::from_unit(backend.disconnect(&device).await)
            }
            Op::Discover { device } => OpResult::from_payload(backend.discover(&device).await),
            Op::Read { device, char } => OpResult::from_payload(backend.read(&device, &char).await),
            Op::Write {
                device,
                char,
                value,
                with_response,
            } => OpResult::from_unit(backend.write(&device, &char, &value, with_response).await),
            Op::RequestMtu { device, mtu } => {
                OpResult::from_payload(backend.request_mtu(&device, mtu).await)
            }
            Op::ObserveStart {
                sub_id,
                device,
                char,
            } => OpResult::from_unit(
                backend
                    .start_observe(sub_id, &device, &char, event_tx)
                    .await,
            ),
            Op::ObserveStop { sub_id } => OpResult::from_unit(backend.stop_observe(sub_id).await),
            _ => OpResult::err(AgentError::new(
                ErrorKind::Unsupported,
                Some("Operation not supported on this agent".into()),
            )),
        }
    }
}
