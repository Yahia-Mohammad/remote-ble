package dev.warsha.remoteble.client

import dev.warsha.remoteble.agent.AgentIdentityStore
import dev.warsha.remoteble.agent.AgentMonitor
import dev.warsha.remoteble.agent.AgentTlsIdentity
import dev.warsha.remoteble.agent.AgentWebSocketServer
import dev.warsha.remoteble.agent.JsseTlsFront
import dev.warsha.remoteble.log.LogLevel
import dev.warsha.remoteble.log.Logger
import dev.warsha.remoteble.protocol.AgentFingerprint
import dev.warsha.remoteble.protocol.CborProtocolCodec
import dev.warsha.remoteble.protocol.CharRef
import dev.warsha.remoteble.protocol.DeviceHandle
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.nio.file.Files
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
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
import org.junit.Assume

/**
 * The Kotlin half of the `TLS-PIN-*` scenarios in `docs/proposals/agent-transport-encryption.md`:
 * a real agent behind its JSSE TLS front, and the SDK's pinned CIO client, which only speaks
 * TLS 1.2, so every passing connection here is also `TLS-PIN-06`.
 */
class TlsPinningEndToEndTest {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val dir = Files.createTempDirectory("remoteble-tls")
    private val identity = AgentIdentityStore.loadOrCreate(dir.resolve("agent-identity.pem"))
    private val clients = mutableListOf<io.ktor.client.HttpClient>()
    private val servers = mutableListOf<AgentWebSocketServer>()
    private val logged = CopyOnWriteArrayList<String>()

    @OptIn(ExperimentalPathApi::class)
    @AfterTest
    fun tearDown() {
        Logger.configure(level = null)
        servers.forEach { it.stop() }
        clients.forEach { it.close() }
        scope.cancel()
        dir.deleteRecursively()
    }

    private fun tlsServer(
        id: AgentTlsIdentity = identity,
        host: String = "127.0.0.1",
        monitor: AgentMonitor? = null,
        operatorToken: String? = null,
    ) = AgentWebSocketServer(
        port = 0,
        host = host,
        monitor = monitor,
        operatorToken = operatorToken,
        tls = JsseTlsFront(id),
    ).also { servers += it }.startAndAwaitReady()

    private fun pinnedTransport(port: Int, pin: AgentFingerprint, reconnect: ReconnectPolicy = ReconnectPolicy()) =
        WebSocketAgentTransport(
            "wss://127.0.0.1:$port/agent",
            scope,
            pinnedWebSocketHttpClient(pin).also { clients += it },
            reconnect = reconnect,
        )

    @Test
    fun aPinnedClientConnectsAndRunsOpsOverWss() = runBlocking {
        val server = tlsServer()
        val session = DefaultAgentSession(pinnedTransport(server.resolvedPort, identity.fingerprint), CborProtocolCodec(), scope)
        withTimeout(10.seconds) { session.transportState.first { it == TransportState.CONNECTED } }

        val peripheral = RemoteGattClient(DeviceHandle("FA:KE:00:00:00:0A"), session)
        peripheral.connect()
        val value = peripheral.read(CharRef("0000180d-0000-1000-8000-00805f9b34fb", "00002a37-0000-1000-8000-00805f9b34fb"))

        assertEquals(listOf<Byte>(0x42, 0x07), value.toList())
    }

    @Test
    fun aDifferentIdentityFailsBeforeTheAgentSeesAnyRequest() = runBlocking {
        Logger.configure(level = LogLevel.DEBUG) { _, _, message, _ -> logged.add(message) }
        val monitor = AgentMonitor()
        val server = tlsServer(monitor = monitor)
        val wrongPin = AgentIdentityStore.loadOrCreate(dir.resolve("other.pem")).fingerprint
        val gaveUp = CompletableTracker()
        val transport = pinnedTransport(server.resolvedPort, wrongPin, ReconnectPolicy(onGaveUp = gaveUp::fire))

        val failure = assertFailsWith<AgentIdentityMismatchException> { transport.connect() }

        assertEquals(wrongPin, failure.expected)
        assertEquals(identity.fingerprint, failure.presented)
        assertEquals(TransportState.GAVE_UP, transport.state.value)
        assertEquals(1, gaveUp.count)
        // The upgrade request, and with it the token, never reached the agent: nothing was
        // accepted, rejected or rate-limited, because no HTTP was ever spoken.
        delay(300)
        assertTrue(monitor.snapshot().clients.isEmpty())
        assertTrue(logged.none { it.startsWith("client connected") || it.startsWith("client rejected") }, "logged: $logged")
    }

    @Test
    fun theIdentitySurvivesARestartAndAResetBreaksThePin() = runBlocking {
        val path = dir.resolve("restart.pem")
        val before = AgentIdentityStore.loadOrCreate(path)
        tlsServer(before).stop()

        val restarted = tlsServer(AgentIdentityStore.loadOrCreate(path))
        pinnedTransport(restarted.resolvedPort, before.fingerprint, ReconnectPolicy.None).apply {
            connect()
            assertEquals(TransportState.CONNECTED, state.value)
            close()
        }
        restarted.stop()

        val reset = tlsServer(AgentIdentityStore.loadOrCreate(path, reset = true))
        assertFailsWith<AgentIdentityMismatchException> {
            pinnedTransport(reset.resolvedPort, before.fingerprint, ReconnectPolicy.None).connect()
        }
        Unit
    }

    @Test
    fun theAgentRecordsTheRealPeerNotTheRelay() = runBlocking {
        val monitor = AgentMonitor()
        val server = tlsServer(monitor = monitor)

        rawTlsSocket("127.0.0.1", server.resolvedPort).use { socket ->
            socket.outputStream.write(upgradeRequest("/agent").toByteArray())
            socket.outputStream.flush()
            withTimeout(5.seconds) { while (monitor.snapshot().clients.isEmpty()) delay(20) }

            // The relay's own connection to the plain listener has a different local port; seeing
            // the client's port proves the address came from the registry, not from the relay.
            assertEquals("127.0.0.1:${socket.localPort}", monitor.snapshot().clients.single().address)
        }
    }

    @Test
    fun theDashboardStillRefusesANonLoopbackPeerBehindTheFront() = runBlocking {
        val lan = lanAddress()
        Assume.assumeTrue("needs a non-loopback IPv4 address", lan != null)
        val server = tlsServer(host = "0.0.0.0", monitor = AgentMonitor(), operatorToken = "operator-secret")
        val basic = "Basic " + Base64.getEncoder().encodeToString("operator:operator-secret".toByteArray())

        val fromLoopback = httpsStatus("127.0.0.1", server.resolvedPort, "/api/state", basic)
        val fromLan = httpsStatus(lan!!, server.resolvedPort, "/api/state", basic)

        // Both arrive at CIO from loopback through the relay. Only the registry tells them apart,
        // and without it the LAN request would see the dashboard.
        assertEquals(200, fromLoopback)
        assertEquals(404, fromLan)
    }

    private fun httpsStatus(host: String, port: Int, path: String, authorization: String): Int =
        rawTlsSocket(host, port).use { socket ->
            socket.outputStream.write(
                "GET $path HTTP/1.1\r\nHost: $host:$port\r\nAuthorization: $authorization\r\nConnection: close\r\n\r\n".toByteArray(),
            )
            socket.outputStream.flush()
            socket.inputStream.bufferedReader().readLine().split(' ')[1].toInt()
        }

    // A fresh nonce per request, as RFC 6455 requires of a real client.
    private fun upgradeRequest(path: String) =
        "GET $path HTTP/1.1\r\nHost: agent\r\nConnection: Upgrade\r\nUpgrade: websocket\r\n" +
            "Sec-WebSocket-Version: 13\r\nSec-WebSocket-Key: ${websocketKey()}\r\n\r\n"

    private fun websocketKey(): String = Base64.getEncoder().encodeToString(ByteArray(16).also(SecureRandom()::nextBytes))

    /** A TLS socket that accepts any certificate: these tests probe the agent, not the pinning. */
    private fun rawTlsSocket(host: String, port: Int): SSLSocket {
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustAll), null) }
        return (context.socketFactory.createSocket(InetAddress.getByName(host), port) as SSLSocket)
            .apply { soTimeout = 5_000; startHandshake() }
    }

    private fun lanAddress(): String? =
        NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.asSequence() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress }
            ?.hostAddress

    private class CompletableTracker {
        @Volatile var count = 0
        fun fire() { count++ }
    }
}
