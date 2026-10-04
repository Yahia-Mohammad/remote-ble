package dev.warsha.remoteble.androidclient.ble

import dev.warsha.remoteble.client.AgentSession
import dev.warsha.remoteble.client.DefaultAgentSession
import dev.warsha.remoteble.client.RemoteAdvertisement
import dev.warsha.remoteble.client.RemotePeripheral
import dev.warsha.remoteble.client.RemoteScanner
import dev.warsha.remoteble.client.TransportState
import dev.warsha.remoteble.client.WebSocketAgentTransport
import dev.warsha.remoteble.client.defaultWebSocketHttpClient
import dev.warsha.remoteble.client.pinnedWebSocketHttpClient
import dev.warsha.remoteble.protocol.AgentFingerprint
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
import kotlinx.coroutines.withTimeoutOrNull
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
    private var fingerprint: AgentFingerprint? = null

    /** The live transport state of the current session, or [TransportState.DISCONNECTED] when idle. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<TransportState> = session
        .flatMapLatest { it?.transportState ?: flowOf(TransportState.DISCONNECTED) }
        .stateIn(scope, SharingStarted.Eagerly, TransportState.DISCONNECTED)

    /**
     * Returns a session connected to [url], (re)building one if needed, and suspends until
     * the transport reports [TransportState.CONNECTED]. With a [fingerprint] the agent is trusted by
     * that pin alone ([pinnedWebSocketHttpClient]); a different key fails with
     * `AgentIdentityMismatchException` before the token is sent.
     *
     * Throws [IllegalStateException] naming the likely cause when the transport gives up or the
     * timeout passes: a pin that no longer matches is a re-pairing problem, not a network one, and
     * saying "timed out" for it leaves the user nowhere to go.
     */
    suspend fun connect(url: String, token: String, fingerprint: AgentFingerprint? = null): AgentSession {
        val target = url.trim()
        val session = obtain(target, token.trim(), fingerprint)
        val reached = withTimeoutOrNull(CONNECT_TIMEOUT) {
            session.transportState.first { it == TransportState.CONNECTED || it in TERMINAL_STATES }
        }
        when (reached) {
            TransportState.CONNECTED -> return session
            TransportState.GAVE_UP -> error(
                if (fingerprint != null) {
                    "the agent no longer presents the identity it was paired with. Pair again if it was reset."
                } else {
                    "the connection was refused, and retrying cannot change that (see the log)."
                },
            )
            TransportState.INCOMPATIBLE_PROTOCOL -> error("the agent speaks an incompatible protocol version.")
            else -> error(
                if (fingerprint == null && target.startsWith("wss://", ignoreCase = true)) {
                    "no answer within ${CONNECT_TIMEOUT.inWholeSeconds}s. An encrypted agent is trusted through " +
                        "its pairing: pair with it again rather than typing its address."
                } else {
                    "no answer within ${CONNECT_TIMEOUT.inWholeSeconds}s."
                },
            )
        }
    }

    /** Advertisements seen by [session]'s remote scanner; collecting starts the scan. */
    fun advertisements(session: AgentSession): Flow<RemoteAdvertisement> =
        RemoteScanner(session).advertisements

    /** Builds a Kable [RemotePeripheral] backed by [session] for the given device. */
    fun peripheral(session: AgentSession, handle: DeviceHandle, name: String?): RemotePeripheral =
        RemotePeripheral(handle, session, name)

    /** Releases the socket and session. Safe to call when already idle. */
    suspend fun close() {
        val retiring = session.value
        session.value = null
        retiring?.close()
        client?.close()
        client = null
        url = null
        token = null
        fingerprint = null
    }

    private suspend fun obtain(url: String, token: String, fingerprint: AgentFingerprint?): AgentSession {
        val current = session.value
        if (current != null && this.url == url && this.token == token && this.fingerprint == fingerprint &&
            current.transportState.value != TransportState.DISCONNECTED &&
            current.transportState.value !in TERMINAL_STATES
        ) {
            return current
        }
        close()
        val newClient = (fingerprint?.let(::pinnedWebSocketHttpClient) ?: defaultWebSocketHttpClient()).also { client = it }
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
            this.fingerprint = fingerprint
        }
    }

    private companion object {
        val CONNECT_TIMEOUT: Duration = 15.seconds

        /** States a transport does not leave on its own: a session in one is never reused. */
        val TERMINAL_STATES = setOf(TransportState.GAVE_UP, TransportState.INCOMPATIBLE_PROTOCOL)
    }
}
