package dev.warsha.remoteble.agent

import dev.warsha.remoteble.log.Logger
import dev.warsha.remoteble.protocol.CLIENT_ID_HEADER
import dev.warsha.remoteble.protocol.CborProtocolCodec
import dev.warsha.remoteble.protocol.ClientHello
import dev.warsha.remoteble.protocol.INCOMPATIBLE_PROTOCOL_CLOSE_REASON
import dev.warsha.remoteble.protocol.ProtocolVersionSelection
import dev.warsha.remoteble.protocol.selectProtocolVersion
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.origin
import io.ktor.server.request.path
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.CloseReason
import io.ktor.websocket.close
import io.ktor.websocket.readBytes
import io.ktor.websocket.send
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/**
 * Hosts an agent backend behind a Ktor WebSocket endpoint. Each connection becomes
 * one bidirectional byte link: binary WS messages in/out are exactly the protocol
 * frames the [AgentBackend] consumes and produces. The transport knows nothing
 * about BLE — that lives entirely in the backend.
 *
 * When [credentials] are set, the agent endpoint requires `Authorization: Bearer <token>`
 * on the upgrade request; a missing/wrong credential is rejected with `401` before the
 * WebSocket handshake completes (the client never reaches CONNECTED). This is the
 * server half of the SDK's transport-level auth hook; the SDK owns no identity system.
 */
class AgentWebSocketServer(
    private val port: Int,
    private val host: String = "127.0.0.1",
    private val path: String = "/agent",
    private val backend: AgentBackend = FakeAgentBackend(),
    private val authToken: String? = null,
    private val credentials: ClientCredentials = ClientCredentials.legacy(authToken),
    /** Separate bearer credential for the optional HTTP dashboard and management reads. */
    private val operatorToken: String? = null,
    private val monitor: AgentMonitor? = null,
    private val registry: PeripheralRegistry? = null,
    // Shared identifier strict-mode switch, exposed on the dashboard for live toggling.
    private val strictMode: StrictModeState? = null,
    // Liveness: Ktor pings idle clients every [pingPeriod] and closes the session if no
    // pong arrives within [pongTimeout]. This promptly frees a client that vanished without
    // a TCP FIN (Wi-Fi drop, NAT timeout, sleep) — which also triggers its lease grace timer
    // — instead of waiting on the OS TCP keepalive (minutes).
    private val pingPeriod: Duration = DEFAULT_PING_PERIOD,
    private val pongTimeout: Duration = DEFAULT_PONG_TIMEOUT,
) {
    private var server: EmbeddedServer<*, *>? = null
    private val nextClientId = atomic(0L)
    private val liveSessions = LiveSessionRegistry()
    private val failedAuthLimiter = FailedAuthLimiter()
    // Separate from the client-plane limiter so a brute-force against the management dashboard and a
    // brute-force against the client upgrade can't consume each other's fixed-memory budgets.
    private val operatorAuthLimiter = FailedAuthLimiter()
    private val operatorCredentials = operatorToken?.let { ClientCredentials.legacy(it) }

    init {
        require(operatorToken?.isBlank() != true) { "operator token must not be blank" }
        require(
            !credentials.required || operatorToken == null ||
                credentials.authenticate("Bearer $operatorToken") == null,
        ) { "operator token must be distinct from every client credential" }
    }

    fun start() {
        // Local alias: inside the embeddedServer lambda the receiver is Ktor's Application,
        // whose own `monitor` (io.ktor.events.Events) would otherwise shadow this property.
        val statusMonitor = monitor
        val instance = embeddedServer(CIO, host = host, port = port) {
            install(WebSockets) {
                pingPeriodMillis = pingPeriod.inWholeMilliseconds
                timeoutMillis = pongTimeout.inWholeMilliseconds
                maxFrameSize = MAX_FRAME_BYTES.toLong()
            }
            // Gate the handshake before the route runs: reject bad credentials with 401
            // so the upgrade never succeeds, rather than accepting then closing the socket.
            if (credentials.required) {
                intercept(ApplicationCallPipeline.Plugins) {
                    if (call.request.path() == path &&
                        credentials.authenticate(call.request.headers[HttpHeaders.Authorization]) == null
                    ) {
                        val decision = failedAuthLimiter.recordFailure(call.request.origin.remoteHost)
                        if (decision.allowed) {
                            Logger.warn(LogTags.SERVER) { "client rejected: unauthorized (401)" }
                            call.respond(HttpStatusCode.Unauthorized)
                        } else {
                            if (decision.shouldLog) {
                                Logger.warn(LogTags.SERVER) { "client rejected: authentication rate limited (429)" }
                            }
                            call.respond(HttpStatusCode.TooManyRequests)
                        }
                        finish()
                    }
                }
            }
            routing {
                if (statusMonitor != null && operatorCredentials != null) {
                    dashboardRoutes(statusMonitor, operatorCredentials, operatorAuthLimiter, registry, strictMode)
                } else if (statusMonitor != null) {
                    Logger.warn(LogTags.SERVER) {
                        "status dashboard disabled: configure a separate operator credential to enable management reads"
                    }
                }
                webSocket(path) {
                    val clientId = nextClientId.incrementAndGet()
                    val address = call.request.origin.let { "${it.remoteHost}:${it.remotePort}" }
                    // Stable client identity (survives reconnects) for ownership; falls back to
                    // the per-connection id so a client that sends none simply never resumes.
                    val stableClientId = call.request.headers[CLIENT_ID_HEADER]?.takeIf { it.isNotBlank() }
                        ?: clientId.toString()
                    val principal = credentials.authenticate(call.request.headers[HttpHeaders.Authorization])
                        ?: run {
                            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "REMOTE_BLE_UNAUTHORIZED"))
                            return@webSocket
                        }
                    val clientKey = ClientCredentials.sessionKey(principal, stableClientId)
                    if (!liveSessions.tryAcquire(clientKey, clientId)) {
                        Logger.warn(LogTags.SERVER) { "client rejected: duplicate live session [c=$clientId]" }
                        close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, DUPLICATE_SESSION_CLOSE_REASON))
                        return@webSocket
                    }
                    statusMonitor?.clientConnected(clientId, address)
                    Logger.info(LogTags.SERVER) { "client connected [c=$clientId from=$address]" }
                    try {
                        val outgoing: suspend (ByteArray) -> Unit = { send(Frame.Binary(fin = true, data = it)) }
                        val codec = CborProtocolCodec()
                        var protocolRangeChecked = false
                        val incomingFrames: Flow<ByteArray> = incoming.receiveAsFlow()
                            .filterIsInstance<Frame.Binary>()
                            .map { frame ->
                                val bytes = frame.readBytes()
                                if (bytes.size > MAX_FRAME_BYTES) {
                                    Logger.warn(LogTags.SERVER) { "client rejected: frame exceeds $MAX_FRAME_BYTES bytes" }
                                    close(CloseReason(CloseReason.Codes.TOO_BIG, FRAME_TOO_LARGE_CLOSE_REASON))
                                    throw CancellationException(FRAME_TOO_LARGE_CLOSE_REASON)
                                }
                                // A normal client sends Hello first, so this adds one decode at
                                // connection start. Keep checking until Hello instead of treating
                                // an earlier command or malformed frame as a successful protocol
                                // check: commands are allowed without waiting for Hello's reply,
                                // but must not let a later incompatible Hello bypass the 1002 close.
                                if (!protocolRangeChecked) {
                                    val hello = runCatching { codec.decode(bytes) as? ClientHello }.getOrNull()
                                    if (hello != null) {
                                        protocolRangeChecked = true
                                        if (selectProtocolVersion(hello.minVersion, hello.maxVersion) !is ProtocolVersionSelection.Selected) {
                                            Logger.warn(LogTags.SERVER) {
                                                "client rejected: incompatible protocol v${hello.minVersion}..${hello.maxVersion}"
                                            }
                                            close(CloseReason(CloseReason.Codes.PROTOCOL_ERROR, INCOMPATIBLE_PROTOCOL_CLOSE_REASON))
                                            throw CancellationException(INCOMPATIBLE_PROTOCOL_CLOSE_REASON)
                                        }
                                    }
                                }
                                bytes
                            }
                        // Keep the session open until the backend's main job finishes
                        // (which happens when the client disconnects and incoming closes).
                        backend.serve(incomingFrames, outgoing, this, clientId, clientKey).join()
                    } finally {
                        // Only the connection that acquired this identity may release it. This
                        // makes cleanup generation-bound even if a future policy supersedes a
                        // live session instead of rejecting it.
                        liveSessions.release(clientKey, clientId)
                        statusMonitor?.clientDisconnected(clientId)
                        Logger.info(LogTags.SERVER) { "client disconnected [c=$clientId]" }
                    }
                }
            }
        }
        server = instance
        instance.start(wait = false)
    }

    fun stop(gracePeriodMillis: Long = 100, timeoutMillis: Long = 500) {
        server?.stop(gracePeriodMillis, timeoutMillis)
        server = null
    }

    companion object {
        const val MAX_FRAME_BYTES: Int = 1_048_576
        const val FRAME_TOO_LARGE_CLOSE_REASON: String = "REMOTE_BLE_FRAME_TOO_LARGE"
        const val DUPLICATE_SESSION_CLOSE_REASON: String = "REMOTE_BLE_DUPLICATE_SESSION"
        val DEFAULT_PING_PERIOD: Duration = 15.seconds
        val DEFAULT_PONG_TIMEOUT: Duration = 40.seconds
    }
}

/** Tracks the one live WebSocket generation permitted for each stable client identity. */
internal class LiveSessionRegistry {
    private val lock = SynchronizedObject()
    private val generations = mutableMapOf<String, Long>()

    fun tryAcquire(clientKey: String, generation: Long): Boolean = synchronized(lock) {
        if (clientKey in generations) return false
        generations[clientKey] = generation
        true
    }

    fun release(clientKey: String, generation: Long): Unit = synchronized(lock) {
        if (generations[clientKey] == generation) generations.remove(clientKey)
    }
}

/**
 * Wires a per-connection byte link to some agent implementation, returning its main
 * [Job]. [connectionId] is the server-assigned id for this client (monitoring); [clientKey]
 * is the client's stable identity (ownership, survives reconnects — see `CLIENT_ID_HEADER`).
 */
fun interface AgentBackend {
    fun serve(
        incoming: Flow<ByteArray>,
        outgoing: suspend (ByteArray) -> Unit,
        scope: CoroutineScope,
        connectionId: Long,
        clientKey: String,
    ): Job
}

/** Hosts the canned [FakeAgent] (Phases 3) — replaced by a real BLE backend in Phase 4. */
class FakeAgentBackend(private val config: FakeAgent.Config = FakeAgent.Config()) : AgentBackend {
    override fun serve(incoming: Flow<ByteArray>, outgoing: suspend (ByteArray) -> Unit, scope: CoroutineScope, connectionId: Long, clientKey: String): Job =
        FakeAgent(incoming, outgoing, scope, config).start()
}

/** Accepts the connection but never replies — for exercising client-side request timeouts. */
class BlackholeBackend : AgentBackend {
    override fun serve(incoming: Flow<ByteArray>, outgoing: suspend (ByteArray) -> Unit, scope: CoroutineScope, connectionId: Long, clientKey: String): Job =
        scope.launch { incoming.collect { /* swallow, never reply */ } }
}
