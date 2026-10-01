package dev.warsha.remoteble.client

import dev.warsha.remoteble.agent.AgentMonitor
import dev.warsha.remoteble.agent.AgentTlsIdentity
import dev.warsha.remoteble.agent.AgentWebSocketServer
import dev.warsha.remoteble.agent.JsseTlsFront
import dev.warsha.remoteble.protocol.AGENT_TLS_SERVER_NAME
import dev.warsha.remoteble.protocol.CborProtocolCodec
import dev.warsha.remoteble.protocol.CharRef
import dev.warsha.remoteble.protocol.DeviceHandle
import io.ktor.client.HttpClient
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.tls.HeldCertificate

/**
 * `TLS-PIN-01` and `02` for the Android client: the OkHttp pinned client against a real agent behind
 * its JSSE front. Agents are reached by IP, so a passing connection here also proves OkHttp's
 * host-name check defers to the pin.
 */
class OkHttpPinningTest {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val identity = identity()
    private val clients = mutableListOf<HttpClient>()
    private val servers = mutableListOf<AgentWebSocketServer>()
    private val char = CharRef("0000180d-0000-1000-8000-00805f9b34fb", "00002a37-0000-1000-8000-00805f9b34fb")

    @AfterTest
    fun tearDown() {
        servers.forEach { it.stop() }
        clients.forEach { it.close() }
        scope.cancel()
    }

    /** A throwaway identity with the agent certificate's SAN, so no key is kept as a fixture. */
    private fun identity(): AgentTlsIdentity {
        val held = HeldCertificate.Builder().ecdsa256().addSubjectAlternativeName(AGENT_TLS_SERVER_NAME).build()
        return AgentTlsIdentity(held.keyPair.private, held.certificate)
    }

    // No bound of its own: start() is already bounded by the server's BIND_TIMEOUT. A 5 s bound here
    // failed CI's coverage job every time, where this host-test JVM starts cold under Kover and its
    // first server start runs ten times slower than locally.
    private fun tlsServer(monitor: AgentMonitor? = null): AgentWebSocketServer =
        AgentWebSocketServer(port = 0, host = "127.0.0.1", monitor = monitor, tls = JsseTlsFront(identity))
            .also { servers += it }
            .also { runBlocking { it.start() } }

    private fun pinnedTransport(port: Int, pin: dev.warsha.remoteble.protocol.AgentFingerprint, reconnect: ReconnectPolicy = ReconnectPolicy()) =
        WebSocketAgentTransport(
            "wss://127.0.0.1:$port/agent",
            scope,
            pinnedWebSocketHttpClient(pin).also { clients += it },
            reconnect = reconnect,
        )

    @Test
    fun aPinnedOkHttpClientConnectsAndRunsOpsOverWss() = runBlocking {
        val server = tlsServer()
        val session = DefaultAgentSession(pinnedTransport(server.resolvedPort, identity.fingerprint), CborProtocolCodec(), scope)
        withTimeout(10.seconds) { session.transportState.first { it == TransportState.CONNECTED } }

        val peripheral = RemoteGattClient(DeviceHandle("FA:KE:00:00:00:0A"), session)
        peripheral.connect()

        assertEquals(listOf<Byte>(0x42, 0x07), peripheral.read(char).toList())
    }

    @Test
    fun aDifferentIdentityFailsBeforeTheAgentSeesAnyRequest() = runBlocking {
        val monitor = AgentMonitor()
        val server = tlsServer(monitor)
        val wrongPin = identity().fingerprint
        val transport = pinnedTransport(server.resolvedPort, wrongPin)

        val failure = assertFailsWith<AgentIdentityMismatchException> { transport.connect() }

        assertEquals(wrongPin, failure.expected)
        assertEquals(identity.fingerprint, failure.presented)
        assertEquals(TransportState.GAVE_UP, transport.state.value)
        // No HTTP was ever spoken, so the token never left this client.
        delay(300)
        assertTrue(monitor.snapshot().clients.isEmpty())
    }
}
