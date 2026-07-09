package dev.warsha.remoteble.androidclient.ble

import dev.warsha.remoteble.client.AgentSession
import dev.warsha.remoteble.client.DefaultAgentSession
import dev.warsha.remoteble.client.RemoteAdvertisement
import dev.warsha.remoteble.client.RemotePeripheral
import dev.warsha.remoteble.client.RemoteScanner
import dev.warsha.remoteble.client.TransportState
import dev.warsha.remoteble.client.WebSocketAgentTransport
import dev.warsha.remoteble.client.defaultWebSocketHttpClient
import dev.warsha.remoteble.protocol.CborProtocolCodec
import dev.warsha.remoteble.protocol.DeviceHandle
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Owns the lifetime of the link to the host agent: the Ktor [HttpClient] and the
 * [AgentSession] layered on top of it. A session is created lazily on first [connect] and
 * reused while it is alive and pointed at the same URL; changing the URL or losing the
 * transport rebuilds it.
 *
 * Everything is scoped to the [scope] passed in (the ViewModel's), so teardown is a single
 * [close] plus the scope's own cancellation.
 */
class AgentConnection(private val scope: CoroutineScope) {

    private val session = MutableStateFlow<AgentSession?>(null)
    private var client: HttpClient? = null
    private var url: String? = null
    private var token: String? = null

    /** The live transport state of the current session, or [TransportState.DISCONNECTED] when idle. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<TransportState> = session
        .flatMapLatest { it?.transportState ?: flowOf(TransportState.DISCONNECTED) }
        .stateIn(scope, SharingStarted.Eagerly, TransportState.DISCONNECTED)

    /**
     * Returns a session connected to [url], (re)building one if needed, and suspends until
     * the transport reports [TransportState.CONNECTED]. Throws on timeout.
     */
    suspend fun connect(url: String, token: String): AgentSession {
        val session = obtain(url.trim(), token.trim())
        withTimeout(CONNECT_TIMEOUT) {
            session.transportState.first { it == TransportState.CONNECTED }
        }
        return session
    }

    /** Advertisements seen by [session]'s remote scanner; collecting starts the scan. */
    fun advertisements(session: AgentSession): Flow<RemoteAdvertisement> =
        RemoteScanner(session).advertisements

    /** Builds a Kable [RemotePeripheral] backed by [session] for the given device. */
    fun peripheral(session: AgentSession, handle: DeviceHandle, name: String?): RemotePeripheral =
        RemotePeripheral(handle, session, name)

    /** Releases the socket and session. Safe to call when already idle. */
    fun close() {
        session.value = null
        client?.close()
        client = null
        url = null
        token = null
    }

    private fun obtain(url: String, token: String): AgentSession {
        val current = session.value
        if (current != null && this.url == url && this.token == token &&
            current.transportState.value != TransportState.DISCONNECTED
        ) {
            return current
        }
        close()
        val newClient = defaultWebSocketHttpClient().also { client = it }
        // Blank token → no Authorization header (token-free agent); otherwise present it as the
        // bearer credential. Read via the provider lambda so a rotated value would be picked up on
        // reconnect (see WebSocketAgentTransport.authToken / F5).
        return DefaultAgentSession(
            WebSocketAgentTransport(url, scope, newClient, authToken = { token.ifBlank { null } }),
            CborProtocolCodec(),
            scope,
        ).also {
            session.value = it
            this.url = url
            this.token = token
        }
    }

    private companion object {
        val CONNECT_TIMEOUT: Duration = 15.seconds
    }
}
