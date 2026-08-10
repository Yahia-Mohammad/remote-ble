package dev.warsha.remoteble.client

import dev.warsha.remoteble.protocol.CLIENT_ID_HEADER
import dev.warsha.remoteble.protocol.OPERATOR_HEADER
import dev.warsha.remoteble.protocol.INCOMPATIBLE_PROTOCOL_CLOSE_REASON
import dev.warsha.remoteble.log.Logger
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.websocket.Frame
import io.ktor.websocket.CloseReason
import io.ktor.websocket.close
import io.ktor.websocket.readBytes
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Exponential backoff for reconnect attempts: `base * 2^attempt`, capped at [max].
 */
class Backoff(
    private val base: Duration = 50.milliseconds,
    private val max: Duration = 2000.milliseconds,
) {
    fun delayFor(attempt: Int): Duration {
        val shifted = base * (1 shl attempt.coerceAtMost(16))
        return if (shifted > max) max else shifted
    }
}

/**
 * How the transport recovers a lost — or not-yet-established — link.
 *
 * [enabled] off makes connects one-shot: [WebSocketAgentTransport.connect] throws on failure and
 * nothing retries. [backoff] paces attempts. [maxAttempts] bounds a single recovery *episode*: after
 * that many consecutive failed attempts the transport stops, rests at
 * [TransportState.DISCONNECTED], and invokes [onGaveUp] exactly once; `null` (the default) means
 * retry forever. A later successful (re)connection resets the count, so a subsequent drop gets a
 * fresh episode. This lets a caller distinguish "still reconnecting" from "gave up — surface an
 * error to the user" rather than watching an unbounded, silent loop.
 */
data class ReconnectPolicy(
    val enabled: Boolean = true,
    val backoff: Backoff = Backoff(),
    val maxAttempts: Int? = null,
    val onGaveUp: (() -> Unit)? = null,
) {
    init {
        require(maxAttempts == null || maxAttempts >= 1) { "maxAttempts must be >= 1 or null (unlimited)" }
    }

    companion object {
        /** One-shot: no background reconnect; `connect()` throws on the first failure. */
        val None: ReconnectPolicy = ReconnectPolicy(enabled = false)
    }
}

/**
 * LAYER 1 over a Ktor WebSocket. Each protocol frame is one binary WS message.
 * The endpoint URL ("ws://host:port/path") is opaque to everything above this.
 *
 * [connect] is idempotent. If the *initial* attempt fails (e.g. the agent isn't up yet) and
 * [reconnect] is enabled, it arms the same [Backoff] loop instead of giving up — a client that
 * starts before its agent still connects once the agent appears, without the caller retrying.
 * With reconnect disabled, the initial attempt is one-shot and [connect] throws on failure.
 * On an unexpected close the transport goes DISCONNECTED (which makes the session fail in-flight
 * requests with TRANSPORT_LOST) and, if enabled, retries per [reconnect] until reconnected (or the
 * policy gives up) — at which point new requests succeed again. The incoming pipe survives reconnects.
 *
 * [authToken] is the Phase-7 auth hook: a suspend provider for the bearer credential.
 * It is invoked once per connection attempt (including every [Backoff] reconnect retry),
 * so a token that rotates or expires is refreshed on reconnect rather than replayed stale.
 * The SDK never caches the returned value and does not own the identity system; the provider
 * owns its own caching/expiry. If it throws, the attempt fails like any other connect error
 * and folds into the reconnect/backoff path. Return `null` (the default) — or a blank string — to
 * send no header, i.e. connect unauthenticated against a token-free agent.
 *
 * [clientId] is a *stable session id* generated once and re-sent on every reconnect, so the
 * agent recognises this client after a brief drop and lets it resume its peripheral ownership.
 * It is not a credential — it identifies, it does not authenticate.
 *
 * [operatorToken] is the agent's separate **operator** credential. Supplying it widens what
 * `agent.status` discloses to this session — every lease and its holder, rather than only its own —
 * and nothing else. It is deliberately a second provider rather than a wider [authToken]: the agent
 * requires the two secrets to be distinct, so a client bearer token can never quietly carry operator
 * reach. Refreshed per connection attempt like [authToken]; null (the default) sends no header, and
 * a wrong value is not a connect failure — the session simply proceeds at normal scope.
 */
@OptIn(ExperimentalUuidApi::class)
class WebSocketAgentTransport(
    private val url: String,
    private val scope: CoroutineScope,
    private val httpClient: HttpClient,
    private val authToken: suspend () -> String? = { null },
    private val reconnect: ReconnectPolicy = ReconnectPolicy(),
    private val clientId: String = Uuid.random().toString(),
    private val operatorToken: suspend () -> String? = { null },
) : AgentTransport {

    private val _state = MutableStateFlow(TransportState.DISCONNECTED)
    override val state: StateFlow<TransportState> = _state.asStateFlow()

    // Keep the transport's handoff bounded. A consumer that cannot decode incoming frames fast
    // enough is disconnected instead of letting an event flood retain an unbounded byte queue.
    private val incomingChannel = Channel<ByteArray>(INCOMING_FRAME_CAPACITY)
    override val incoming: Flow<ByteArray> = incomingChannel.receiveAsFlow()

    private val connectMutex = Mutex()
    private var session: DefaultClientWebSocketSession? = null
    private var closed = false

    override suspend fun connect() {
        connectMutex.withLock {
            if (closed) throw TransportClosedException("transport closed")
            if (_state.value == TransportState.CONNECTED) return
            try {
                openSession()
            } catch (e: Throwable) {
                if (reconnect.enabled && !closed) {
                    Logger.warn(LogTags.TRANSPORT) {
                        "initial connect failed, starting reconnect loop: ${e.message}"
                    }
                    scope.launch { reconnectWithBackoff() }
                } else {
                    Logger.error(LogTags.TRANSPORT) { "initial connect failed (reconnect disabled): ${e.message}" }
                    throw e
                }
            }
        }
    }

    override suspend fun send(frame: ByteArray) {
        val current = session ?: throw TransportClosedException("not connected")
        try {
            current.send(Frame.Binary(fin = true, data = frame))
        } catch (e: Throwable) {
            throw TransportClosedException(e.message)
        }
    }

    override suspend fun close() {
        closed = true
        _state.value = TransportState.DISCONNECTED
        session?.close()
        session = null
        incomingChannel.close()
    }

    /** Caller holds [connectMutex]. Establishes a session or throws (state reset to DISCONNECTED). */
    private suspend fun openSession() {
        _state.value = TransportState.CONNECTING
        val s = try {
            val token = authToken()
            val operator = operatorToken()
            httpClient.webSocketSession(urlString = url) {
                token?.takeIf { it.isNotBlank() }?.let { header(HttpHeaders.Authorization, "Bearer $it") }
                header(CLIENT_ID_HEADER, clientId)
                operator?.takeIf { it.isNotBlank() }?.let { header(OPERATOR_HEADER, "Bearer $it") }
            }
        } catch (e: Throwable) {
            _state.value = TransportState.DISCONNECTED
            Logger.debug(LogTags.TRANSPORT) { "openSession failed: ${e.message}" }
            throw e
        }
        session = s
        _state.value = TransportState.CONNECTED
        Logger.info(LogTags.TRANSPORT) { "CONNECTED [cid=$clientId]" }
        scope.launch { receiveLoop(s) }
    }

    private suspend fun receiveLoop(s: DefaultClientWebSocketSession) {
        try {
            for (frame in s.incoming) {
                when (frame) {
                    is Frame.Binary -> {
                        if (incomingChannel.trySend(frame.readBytes()).isFailure) {
                            Logger.warn(LogTags.TRANSPORT) { "incoming frame buffer overflow; closing [cid=$clientId]" }
                            s.close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, INCOMING_OVERFLOW_CLOSE_REASON))
                            return
                        }
                    }
                    else -> Unit
                }
            }
        } catch (_: Throwable) {
            Logger.debug(LogTags.TRANSPORT) { "receive loop closed" }
        } finally {
            // Ktor normally completes `incoming` without yielding a Frame.Close. Its close reason
            // lives on the session Deferred, so derive terminal protocol state from there instead
            // of relying on a control frame reaching the application-level loop.
            val reason = try {
                s.closeReason.await()
            } catch (_: Throwable) {
                null
            }
            val incompatibleProtocol = reason?.knownReason == CloseReason.Codes.PROTOCOL_ERROR &&
                reason.message == INCOMPATIBLE_PROTOCOL_CLOSE_REASON
            onDisconnected(s, incompatibleProtocol)
        }
    }

    private fun onDisconnected(closedSession: DefaultClientWebSocketSession, incompatibleProtocol: Boolean) {
        if (session !== closedSession && session != null) return
        session = null
        if (incompatibleProtocol) {
            // A new session with a different supported range may be created by the caller, but this
            // instance must not reconnect and disguise a stable incompatibility as a network blip.
            closed = true
            _state.value = TransportState.INCOMPATIBLE_PROTOCOL
            Logger.warn(LogTags.TRANSPORT) { "INCOMPATIBLE_PROTOCOL [cid=$clientId]" }
            return
        }
        when {
            closed -> {
                // A deliberate close(). Stays DISCONNECTED: GAVE_UP exists to tell an observer
                // something unexpected happened and will not be repaired, which is not news to
                // whoever called close().
                _state.value = TransportState.DISCONNECTED
                Logger.info(LogTags.TRANSPORT) { "DISCONNECTED (closed) [cid=$clientId]" }
            }
            reconnect.enabled -> {
                _state.value = TransportState.DISCONNECTED
                Logger.info(LogTags.TRANSPORT) { "DISCONNECTED [cid=$clientId]" }
                scope.launch { reconnectWithBackoff() }
            }
            else -> {
                // Dropped with no policy to retry it. Saying DISCONNECTED here would invite every
                // observer to wait for a recovery that nothing is going to attempt.
                _state.value = TransportState.GAVE_UP
                Logger.info(LogTags.TRANSPORT) { "GAVE_UP (reconnect disabled) [cid=$clientId]" }
            }
        }
    }

    /**
     * Retries [openSession] with [ReconnectPolicy.backoff] until connected, closed, or —
     * when [ReconnectPolicy.maxAttempts] is set — that many consecutive attempts have failed,
     * in which case it gives up (resting at DISCONNECTED) and fires [ReconnectPolicy.onGaveUp].
     */
    private suspend fun reconnectWithBackoff() {
        val maxAttempts = reconnect.maxAttempts
        var attempt = 0
        while (!closed && _state.value != TransportState.CONNECTED) {
            delay(reconnect.backoff.delayFor(attempt))
            if (closed) return
            attempt++
            try {
                connectMutex.withLock {
                    if (closed || _state.value == TransportState.CONNECTED) return
                    openSession()
                }
                Logger.info(LogTags.TRANSPORT) { "reconnected after $attempt attempt(s) [cid=$clientId]" }
                return
            } catch (_: Throwable) {
                if (maxAttempts != null && attempt >= maxAttempts) {
                    Logger.error(LogTags.TRANSPORT) { "reconnect gave up after $attempt attempt(s) [cid=$clientId]" }
                    // Publish the give-up *before* the callback: an onGaveUp handler that reads
                    // this transport's state must not observe the state that preceded its own
                    // trigger. The state is the SDK's signal; the callback stays the caller's hook.
                    if (!closed) {
                        _state.value = TransportState.GAVE_UP
                        reconnect.onGaveUp?.invoke()
                    }
                    return
                }
                Logger.warn(LogTags.TRANSPORT) {
                    "reconnect attempt $attempt failed, backing off [cid=$clientId]"
                }
            }
        }
    }

    private companion object {
        const val INCOMING_FRAME_CAPACITY: Int = 256
        const val INCOMING_OVERFLOW_CLOSE_REASON: String = "REMOTE_BLE_INCOMING_OVERFLOW"
    }
}
