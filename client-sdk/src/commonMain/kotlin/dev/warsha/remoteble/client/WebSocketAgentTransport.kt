package dev.warsha.remoteble.client

import dev.warsha.remoteble.protocol.CLIENT_ID_HEADER
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.websocket.Frame
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
 * LAYER 1 over a Ktor WebSocket. Each protocol frame is one binary WS message.
 * The endpoint URL ("ws://host:port/path") is opaque to everything above this.
 *
 * [connect] is idempotent. On an unexpected close the transport goes DISCONNECTED
 * (which makes the session fail in-flight requests with TRANSPORT_LOST) and, if
 * [autoReconnect], retries with [Backoff] until reconnected — at which point new
 * requests succeed again. The incoming pipe survives reconnects.
 *
 * [authToken] is the Phase-7 auth hook: a bearer credential injected at construction.
 * The SDK does not own the identity system.
 *
 * [clientId] is a *stable session id* generated once and re-sent on every reconnect, so the
 * agent recognises this client after a brief drop and lets it resume its peripheral ownership.
 * It is not a credential — it identifies, it does not authenticate.
 */
@OptIn(ExperimentalUuidApi::class)
class WebSocketAgentTransport(
    private val url: String,
    private val scope: CoroutineScope,
    private val httpClient: HttpClient,
    private val authToken: String? = null,
    private val autoReconnect: Boolean = true,
    private val backoff: Backoff = Backoff(),
    private val clientId: String = Uuid.random().toString(),
) : AgentTransport {

    private val _state = MutableStateFlow(TransportState.DISCONNECTED)
    override val state: StateFlow<TransportState> = _state.asStateFlow()

    private val incomingChannel = Channel<ByteArray>(Channel.UNLIMITED)
    override val incoming: Flow<ByteArray> = incomingChannel.receiveAsFlow()

    private val connectMutex = Mutex()
    private var session: DefaultClientWebSocketSession? = null
    private var closed = false

    override suspend fun connect() {
        connectMutex.withLock {
            if (closed) throw TransportClosedException("transport closed")
            if (_state.value == TransportState.CONNECTED) return
            openSession()
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
            httpClient.webSocketSession(urlString = url) {
                authToken?.let { header(HttpHeaders.Authorization, "Bearer $it") }
                header(CLIENT_ID_HEADER, clientId)
            }
        } catch (e: Throwable) {
            _state.value = TransportState.DISCONNECTED
            throw e
        }
        session = s
        _state.value = TransportState.CONNECTED
        scope.launch { receiveLoop(s) }
    }

    private suspend fun receiveLoop(s: DefaultClientWebSocketSession) {
        try {
            for (frame in s.incoming) {
                if (frame is Frame.Binary) incomingChannel.trySend(frame.readBytes())
            }
        } catch (_: Throwable) {
            // fall through to disconnect handling
        } finally {
            onDisconnected(s)
        }
    }

    private fun onDisconnected(closedSession: DefaultClientWebSocketSession) {
        // Ignore stale receive loops from a session we already replaced.
        if (session !== closedSession && session != null) return
        session = null
        _state.value = TransportState.DISCONNECTED
        if (autoReconnect && !closed) {
            scope.launch { reconnectWithBackoff() }
        }
    }

    private suspend fun reconnectWithBackoff() {
        var attempt = 0
        while (!closed && _state.value != TransportState.CONNECTED) {
            delay(backoff.delayFor(attempt++))
            if (closed) return
            try {
                connectMutex.withLock {
                    if (closed || _state.value == TransportState.CONNECTED) return
                    openSession()
                }
                return
            } catch (_: Throwable) {
                // retry on next iteration
            }
        }
    }
}
