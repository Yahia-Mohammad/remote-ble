package dev.warsha.remoteble.agent

import dev.warsha.remoteble.protocol.CLIENT_ID_HEADER
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
import io.ktor.websocket.readBytes
import io.ktor.websocket.send
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CoroutineScope
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
 * When [authToken] is set, the agent endpoint requires `Authorization: Bearer <token>`
 * on the upgrade request; a missing/wrong credential is rejected with `401` before the
 * WebSocket handshake completes (the client never reaches CONNECTED). This is the
 * server half of the SDK's transport-level auth hook; the SDK owns no identity system.
 */
class AgentWebSocketServer(
    private val port: Int,
    private val path: String = "/agent",
    private val backend: AgentBackend = FakeAgentBackend(),
    private val authToken: String? = null,
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

    fun start() {
        // Local alias: inside the embeddedServer lambda the receiver is Ktor's Application,
        // whose own `monitor` (io.ktor.events.Events) would otherwise shadow this property.
        val statusMonitor = monitor
        val instance = embeddedServer(CIO, port = port) {
            install(WebSockets) {
                pingPeriodMillis = pingPeriod.inWholeMilliseconds
                timeoutMillis = pongTimeout.inWholeMilliseconds
            }
            // Gate the handshake before the route runs: reject bad credentials with 401
            // so the upgrade never succeeds, rather than accepting then closing the socket.
            authToken?.let { expected ->
                intercept(ApplicationCallPipeline.Plugins) {
                    if (call.request.path() == path &&
                        call.request.headers[HttpHeaders.Authorization] != "Bearer $expected"
                    ) {
                        call.respond(HttpStatusCode.Unauthorized)
                        finish()
                    }
                }
            }
            routing {
                statusMonitor?.let { dashboardRoutes(it, registry, strictMode) }
                webSocket(path) {
                    val clientId = nextClientId.incrementAndGet()
                    val address = call.request.origin.let { "${it.remoteHost}:${it.remotePort}" }
                    // Stable client identity (survives reconnects) for ownership; falls back to
                    // the per-connection id so a client that sends none simply never resumes.
                    val clientKey = call.request.headers[CLIENT_ID_HEADER]?.takeIf { it.isNotBlank() }
                        ?: clientId.toString()
                    statusMonitor?.clientConnected(clientId, address)
                    try {
                        val outgoing: suspend (ByteArray) -> Unit = { send(Frame.Binary(fin = true, data = it)) }
                        val incomingFrames: Flow<ByteArray> = incoming.receiveAsFlow()
                            .filterIsInstance<Frame.Binary>()
                            .map { it.readBytes() }
                        // Keep the session open until the backend's main job finishes
                        // (which happens when the client disconnects and incoming closes).
                        backend.serve(incomingFrames, outgoing, this, clientId, clientKey).join()
                    } finally {
                        statusMonitor?.clientDisconnected(clientId)
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
        val DEFAULT_PING_PERIOD: Duration = 15.seconds
        val DEFAULT_PONG_TIMEOUT: Duration = 40.seconds
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
