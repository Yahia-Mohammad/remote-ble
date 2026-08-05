package dev.warsha.remoteble.agent

import dev.warsha.remoteble.log.Logger
import dev.warsha.remoteble.protocol.CLIENT_ID_HEADER
import dev.warsha.remoteble.protocol.CborProtocolCodec
import dev.warsha.remoteble.protocol.ClientHello
import dev.warsha.remoteble.protocol.INCOMPATIBLE_PROTOCOL_CLOSE_REASON
import dev.warsha.remoteble.protocol.OPERATOR_HEADER
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

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
    /**
     * Whether the dashboard answers non-loopback requests. Off by default: it is the high-privilege
     * plane and travels unencrypted, so it is loopback-only unless an operator deliberately opts in.
     * See `Dashboard.allowedOrigin`.
     */
    private val allowRemoteDashboard: Boolean = false,
    private val monitor: AgentMonitor? = null,
    private val registry: PeripheralRegistry? = null,
    // Shared identifier strict-mode switch. The dashboard *reports* it (`GET /api/strict`) but
    // cannot change it: `Dashboard.kt` is read-only by design and has no mutation endpoint. This
    // comment previously said "for live toggling", which described an intention rather than the code
    // and was read back as fact when weighing what a mobile dashboard would add (item 20).
    private val strictMode: StrictModeState? = null,
    // Liveness: Ktor pings idle clients every [pingPeriod] and closes the session if no
    // pong arrives within [pongTimeout]. This promptly frees a client that vanished without
    // a TCP FIN (Wi-Fi drop, NAT timeout, sleep) — which also triggers its lease grace timer
    // — instead of waiting on the OS TCP keepalive (minutes).
    private val pingPeriod: Duration = DEFAULT_PING_PERIOD,
    private val pongTimeout: Duration = DEFAULT_PONG_TIMEOUT,
) {
    private var server: EmbeddedServer<*, *>? = null

    // The scope owning the running engine's job, so [stop] retires it with the server rather than
    // leaving a SupervisorJob (and its exception handler) alive for the process's lifetime.
    private var engineJob: CoroutineScope? = null
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

    /**
     * Starts the server and **does not return until the listening socket is actually bound**,
     * throwing [AgentBindException] if it is not.
     *
     * Suspending is the whole point. Ktor's `start(wait = false)` returns before CIO binds, so a
     * bind error surfaced on a CIO worker *after* `start()` had already reported success — which
     * made it unreportable by construction, and on Kotlin/Native fatal, since an exception on a
     * `MultiWorkerDispatcher` worker with no handler aborts the process (Rig B case 4, finding 10).
     * Awaiting `resolvedConnectors()` moves that failure back onto the caller's thread, where it
     * can become an `AgentStartResult.Failed`.
     *
     * The same race also produced test flakes — a client could connect before the bind and, because
     * an *initial* connect failure schedules no reconnect, silently never connect. That was worked
     * around by polling the port; awaiting here removes the need.
     */
    suspend fun start() {
        // Local alias: inside the embeddedServer lambda the receiver is Ktor's Application,
        // whose own `monitor` (io.ktor.events.Events) would otherwise shadow this property.
        val statusMonitor = monitor
        // Where an engine-side failure goes. Without this the CIO accept job has no parent to
        // report to, so a BindException reaches the *thread's* uncaught-exception handler and
        // kills the process — measured on a Pixel 8, where the crash log names
        // `FATAL EXCEPTION: DefaultDispatcher-worker-N` rather than anything on the caller's
        // stack. Awaiting the bind is not sufficient on its own: the process is already gone by
        // the time the await could report. Owning the engine's job is what makes it reportable.
        val engineFailure = CompletableDeferred<Throwable>()
        val engineScope = CoroutineScope(
            SupervisorJob() + CoroutineExceptionHandler { _, failure -> engineFailure.complete(failure) },
        )
        val instance = engineScope.embeddedServer(CIO, host = host, port = port) {
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
                    dashboardRoutes(
                        statusMonitor,
                        operatorCredentials,
                        operatorAuthLimiter,
                        registry,
                        strictMode,
                        allowRemoteDashboard,
                    )
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
                    // Optional second credential, widening only what `agent.status` discloses.
                    // A missing or wrong value is NOT a rejection: the session proceeds at normal
                    // scope and says so in its status reply, so a client that asked for
                    // operator-only fields without the secret can tell that apart from an
                    // unreachable agent or one too old to know the capability. A *wrong* value is
                    // still a guess at the operator secret, so it is rate-limited like any other.
                    val operatorScope = call.request.headers[OPERATOR_HEADER]
                        ?.takeIf { it.isNotBlank() }
                        ?.let { offered ->
                            val accepted = operatorCredentials?.authenticate(offered) != null
                            if (!accepted) {
                                val decision = operatorAuthLimiter.recordFailure(call.request.origin.remoteHost)
                                if (decision.shouldLog) {
                                    Logger.warn(LogTags.SERVER) {
                                        "operator scope refused on upgrade [c=$clientId]: bad credential"
                                    }
                                }
                            }
                            accepted
                        } ?: false
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
                        backend.serve(incomingFrames, outgoing, this, clientId, clientKey, operatorScope).join()
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
        engineJob = engineScope
        // Three different failure shapes, all measured rather than assumed:
        //  - JVM/Ktor 3.5: `start(wait = false)` throws *synchronously*, as a
        //    JobCancellationException whose root cause is the BindException;
        //  - Android: the accept job fails on a `DefaultDispatcher` worker after `start()` has
        //    returned — this is the one that killed the process, and only [engineScope]'s handler
        //    catches it;
        //  - Kotlin/Native: same asynchronous shape, which Rig B saw abort the process
        //    (case 4, finding 10).
        // Handling only the shape of whichever platform you happen to be standing on is how this
        // stayed unreportable for a whole rig, so all three route through one conversion.
        bindGuarded(instance, engineScope, engineFailure) { instance.start(wait = false) }
        awaitBind(instance, engineScope, engineFailure)
    }

    /**
     * Runs a bind step, converting every failure shape into [AgentBindException].
     *
     * A failed CIO bind does NOT arrive as an IOException: the engine cancels its own job, so what
     * reaches us is a [CancellationException]. Rethrowing that blindly — the reflex, and correct
     * almost everywhere else — would turn a reportable failure straight back into the silent one.
     * The discriminator is *whose* job was cancelled: if the caller's coroutine is still active,
     * the cancellation came from the engine, not from our caller. Genuine caller cancellation
     * still propagates untouched.
     *
     * [engineFailure], when the engine's handler has recorded something, is the better cause: it
     * is the actual `BindException`, where the cancellation only says "… is cancelling".
     */
    private suspend inline fun <T> bindGuarded(
        instance: EmbeddedServer<*, *>,
        engineScope: CoroutineScope,
        engineFailure: CompletableDeferred<Throwable>,
        block: () -> T,
    ): T =
        try {
            block()
        } catch (cancelled: CancellationException) {
            abandon(instance, engineScope)
            if (!currentCoroutineContext().isActive) throw cancelled
            throw bindFailure(engineFailure, cancelled)
        } catch (failure: Throwable) {
            abandon(instance, engineScope)
            throw bindFailure(engineFailure, failure)
        }

    private fun bindFailure(engineFailure: CompletableDeferred<Throwable>, fallback: Throwable?): AgentBindException {
        val cause = engineFailure.takeIf { it.isCompleted }?.getCompleted() ?: fallback
        Logger.warn(LogTags.SERVER) { "bind failed [$host:$port]: ${cause?.rootCause()?.message}" }
        return AgentBindException(host, port, cause)
    }

    /**
     * Waits for CIO to report its bound connectors, or for the engine to report a failure.
     *
     * Bounded by [BIND_TIMEOUT] rather than waiting indefinitely: a bind that neither succeeds nor
     * reports a failure would otherwise hang `start()` forever, which from the UI is
     * indistinguishable from the frozen agent this change exists to prevent. A failed start also
     * tears the half-built server down and clears [server], so a subsequent [stop] cannot be handed
     * an instance that never bound — and, more importantly, so the *next* start is not the one that
     * discovers the port is still held.
     */
    private suspend fun awaitBind(
        instance: EmbeddedServer<*, *>,
        engineScope: CoroutineScope,
        engineFailure: CompletableDeferred<Throwable>,
    ) {
        val connectors = bindGuarded(instance, engineScope, engineFailure) {
            withTimeoutOrNull(BIND_TIMEOUT) { instance.engine.resolvedConnectors() }
        }
        // The engine can report a failure while `resolvedConnectors()` still returns a connector
        // list — the list describes what was *requested* on some paths, not what was bound. Treat
        // a recorded engine failure as authoritative over a hopeful-looking list.
        if (engineFailure.isCompleted) {
            abandon(instance, engineScope)
            throw bindFailure(engineFailure, null)
        }
        if (connectors.isNullOrEmpty()) {
            abandon(instance, engineScope)
            Logger.warn(LogTags.SERVER) { "bind did not complete within $BIND_TIMEOUT [$host:$port]" }
            throw AgentBindException(host, port, null)
        }
        Logger.info(LogTags.SERVER) { "listening on $host:$port$path" }
    }

    private fun abandon(instance: EmbeddedServer<*, *>, engineScope: CoroutineScope) {
        runCatchingNonCancellation { instance.stop(0, 0) }
        engineScope.cancel()
        server = null
        engineJob = null
    }

    fun stop(gracePeriodMillis: Long = 100, timeoutMillis: Long = 500) {
        server?.stop(gracePeriodMillis, timeoutMillis)
        engineJob?.cancel()
        server = null
        engineJob = null
    }

    companion object {
        const val MAX_FRAME_BYTES: Int = 1_048_576
        const val FRAME_TOO_LARGE_CLOSE_REASON: String = "REMOTE_BLE_FRAME_TOO_LARGE"
        const val DUPLICATE_SESSION_CLOSE_REASON: String = "REMOTE_BLE_DUPLICATE_SESSION"
        val DEFAULT_PING_PERIOD: Duration = 15.seconds
        val DEFAULT_PONG_TIMEOUT: Duration = 40.seconds

        /** How long [start] waits for the listening socket before calling the bind failed. */
        val BIND_TIMEOUT: Duration = 10.seconds
    }
}

/**
 * The agent could not take its listening socket — most often because the port is already held,
 * whether by a previous agent instance or by an unrelated app.
 *
 * [cause] is null when the bind simply never completed within [AgentWebSocketServer.BIND_TIMEOUT],
 * which is a different fact from a bind that actively failed and is worth keeping distinguishable.
 */
class AgentBindException(
    val host: String,
    val port: Int,
    cause: Throwable?,
) : Exception(
    // The engine's own cancellation message ("… is cancelling") says nothing useful; the operator
    // needs the root cause, which is where "Address already in use" actually lives.
    "could not bind $host:$port" + (cause?.rootCause()?.message?.let { ": $it" } ?: " (timed out)"),
    cause,
)

/** The deepest [Throwable.cause] in the chain — the bind error under the engine's cancellation. */
internal fun Throwable.rootCause(): Throwable {
    var current = this
    // Bounded rather than recursive: a malformed cause chain must not be able to loop forever.
    repeat(MAX_CAUSE_DEPTH) {
        current = current.cause ?: return current
    }
    return current
}

private const val MAX_CAUSE_DEPTH = 16

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
        /**
         * Whether this connection presented a valid operator credential on the upgrade
         * (`OPERATOR_HEADER`). Decided at the transport, where the credential arrives, and carried
         * here because it widens what `agent.status` may disclose. It authorizes nothing else.
         */
        operatorScope: Boolean,
    ): Job
}

/** Hosts the canned [FakeAgent] (Phases 3) — replaced by a real BLE backend in Phase 4. */
class FakeAgentBackend(private val config: FakeAgent.Config = FakeAgent.Config()) : AgentBackend {
    override fun serve(incoming: Flow<ByteArray>, outgoing: suspend (ByteArray) -> Unit, scope: CoroutineScope, connectionId: Long, clientKey: String, operatorScope: Boolean): Job =
        FakeAgent(incoming, outgoing, scope, config).start()
}

/** Accepts the connection but never replies — for exercising client-side request timeouts. */
class BlackholeBackend : AgentBackend {
    override fun serve(incoming: Flow<ByteArray>, outgoing: suspend (ByteArray) -> Unit, scope: CoroutineScope, connectionId: Long, clientKey: String, operatorScope: Boolean): Job =
        scope.launch { incoming.collect { /* swallow, never reply */ } }
}
