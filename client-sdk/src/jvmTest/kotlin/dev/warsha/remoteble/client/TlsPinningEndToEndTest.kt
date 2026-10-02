package dev.warsha.remoteble.client

import dev.warsha.remoteble.agent.AgentIdentityStore
import dev.warsha.remoteble.agent.AgentMonitor
import dev.warsha.remoteble.agent.AgentTlsIdentity
import dev.warsha.remoteble.agent.AgentWebSocketServer
import dev.warsha.remoteble.agent.BleAgentBackend
import dev.warsha.remoteble.agent.ClientCredentials
import dev.warsha.remoteble.agent.JsseTlsFront
import dev.warsha.remoteble.agent.TlsFront
import dev.warsha.remoteble.log.LogLevel
import dev.warsha.remoteble.log.Logger
import dev.warsha.remoteble.protocol.AgentFingerprint
import dev.warsha.remoteble.protocol.AgentPairing
import dev.warsha.remoteble.protocol.CborProtocolCodec
import dev.warsha.remoteble.protocol.CharRef
import dev.warsha.remoteble.protocol.DeviceHandle
import dev.warsha.remoteble.protocol.ErrorKind
import dev.warsha.remoteble.protocol.Op
import dev.warsha.remoteble.protocol.OpResult
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
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
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
    private val char = CharRef("0000180d-0000-1000-8000-00805f9b34fb", "00002a37-0000-1000-8000-00805f9b34fb")

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
        val value = peripheral.read(char)

        assertEquals(listOf<Byte>(0x42, 0x07), value.toList())
    }

    @Test
    fun aPairingUriConnectsPinnedWithItsTokenAndAnotherIdentityIsRefused() = runBlocking<Unit> {
        val server = AgentWebSocketServer(port = 0, authToken = "pairing-token", tls = JsseTlsFront(identity))
            .also { servers += it }.startAndAwaitReady()
        fun transportFor(pairing: AgentPairing) =
            WebSocketAgentTransport(pairing.url, scope, pairingWebSocketHttpClient(pairing).also { clients += it }, authToken = { pairing.token })

        // Through the URI, as a client that scanned or pasted it would.
        val paired = AgentPairing.parse(AgentPairing("127.0.0.1", server.resolvedPort, "pairing-token", identity.fingerprint).toUri())
        val transport = transportFor(paired)
        transport.connect()
        withTimeout(10.seconds) { transport.state.first { it == TransportState.CONNECTED } }

        val impostor = AgentIdentityStore.loadOrCreate(dir.resolve("impostor.pem")).fingerprint
        val misled = AgentPairing("127.0.0.1", server.resolvedPort, "pairing-token", impostor)
        assertFailsWith<AgentIdentityMismatchException> { transportFor(misled).connect() }
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

    /**
     * TLS-PIN-04: the agent restarts on the same port with the same identity. The pinned client
     * reconnects over `wss://` on its own, and the session's replay restores a subscription the
     * app never re-collected, exactly as over `ws://`.
     */
    @Test
    fun aPinnedClientReconnectsAndResumesItsSubscriptionAfterARestart() = runBlocking {
        var server = tlsServer()
        val port = server.resolvedPort
        val session = DefaultAgentSession(
            pinnedTransport(port, identity.fingerprint, ReconnectPolicy(backoff = Backoff(50.milliseconds, 200.milliseconds))),
            CborProtocolCodec(),
            scope,
        )
        withTimeout(10.seconds) { session.transportState.first { it == TransportState.CONNECTED } }
        val peripheral = RemoteGattClient(DeviceHandle("FA:KE:00:00:00:0A"), session)
        peripheral.connect()
        val received = Channel<ByteArray>(Channel.UNLIMITED)
        val observer = peripheral.observe(char).onEach { received.trySend(it) }.launchIn(scope)
        try {
            withTimeout(10.seconds) { received.receive() }

            server.stop()
            withTimeout(10.seconds) { session.transportState.first { it == TransportState.DISCONNECTED } }
            server = AgentWebSocketServer(port, tls = JsseTlsFront(identity)).also { servers += it }.startAndAwaitReady()
            withTimeout(15.seconds) { session.transportState.first { it == TransportState.CONNECTED } }

            while (received.tryReceive().isSuccess) { /* drop the pre-restart backlog */ }
            repeat(3) { withTimeout(10.seconds) { received.receive() } }
        } finally {
            observer.cancel()
        }
    }

    /**
     * TLS-PIN-04: a transport drop inside the grace window. The same principal and stable client id
     * reconnect over `wss://` and resume the lease the agent held for them; another principal is
     * still refused it meanwhile.
     */
    @Test
    fun aLeaseHeldThroughATransportDropResumesOverWss() = runBlocking {
        val server = AgentWebSocketServer(
            port = 0,
            credentials = ClientCredentials.of(mapOf("alpha" to "secret-a", "beta" to "secret-b")),
            backend = BleAgentBackend(StubBleBackend()),
            tls = JsseTlsFront(identity),
        ).also { servers += it }.startAndAwaitReady()
        val device = DeviceHandle(StubBleBackend.DEVICE)
        suspend fun session(secret: String, clientId: String) = DefaultAgentSession(
            WebSocketAgentTransport(
                "wss://127.0.0.1:${server.resolvedPort}/agent",
                scope,
                pinnedWebSocketHttpClient(identity.fingerprint).also { clients += it },
                authToken = { secret },
                reconnect = ReconnectPolicy.None,
                clientId = clientId,
            ),
            CborProtocolCodec(),
            scope,
        ).also { s -> withTimeout(10.seconds) { s.transportState.first { it == TransportState.CONNECTED } } }

        val first = session("secret-a", "resume-me")
        assertIs<OpResult.Ok>(first.request(Op.Connect(device)))
        first.close()

        val other = session("secret-b", "someone-else")
        assertEquals(ErrorKind.PERIPHERAL_BUSY, assertIs<OpResult.Err>(other.request(Op.Connect(device))).error.kind)

        val resumed = session("secret-a", "resume-me")
        assertIs<OpResult.Ok>(resumed.request(Op.Connect(device)))
        assertIs<OpResult.Ok>(resumed.request(Op.Read(device, char)))
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
    fun aConnectionRelayedBeforeStartReturnsStillGetsItsRealPeer() = runBlocking {
        // The front accepts from the moment it binds, but the server learns of it only when start()
        // resumes, which a busy caller (a phone's main thread) can hold up. A request relayed in
        // between must still resolve to its real peer, not to the relay on loopback.
        val monitor = AgentMonitor()
        val listening = CompletableDeferred<Int>()
        val slowToReturn = TlsFront.Factory { host, port, upstreamPort, onFailure ->
            JsseTlsFront(identity).start(host, port, upstreamPort, onFailure).also {
                listening.complete(it.port)
                delay(1.seconds)
            }
        }
        val server = AgentWebSocketServer(port = 0, monitor = monitor, tls = slowToReturn).also { servers += it }
        val starting = scope.launch { server.start() }

        rawTlsSocket("127.0.0.1", withTimeout(10.seconds) { listening.await() }).use { socket ->
            socket.outputStream.write(upgradeRequest("/agent").toByteArray())
            socket.outputStream.flush()
            withTimeout(5.seconds) { while (monitor.snapshot().clients.isEmpty()) delay(20) }

            assertEquals("127.0.0.1:${socket.localPort}", monitor.snapshot().clients.single().address)
        }
        starting.join()
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
