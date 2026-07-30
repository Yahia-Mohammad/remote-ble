package dev.warsha.remoteble.client

import dev.warsha.remoteble.agent.AgentBackend
import dev.warsha.remoteble.agent.AgentMonitor
import dev.warsha.remoteble.agent.AgentWebSocketServer
import dev.warsha.remoteble.agent.BlackholeBackend
import dev.warsha.remoteble.agent.BleAgentBackend
import dev.warsha.remoteble.agent.BleBackend
import dev.warsha.remoteble.agent.ClientCredentials
import dev.warsha.remoteble.protocol.AdvertisementDto
import dev.warsha.remoteble.protocol.CborProtocolCodec
import dev.warsha.remoteble.protocol.CharRef
import dev.warsha.remoteble.protocol.ClientHello
import dev.warsha.remoteble.protocol.Command
import dev.warsha.remoteble.protocol.ConnProfile
import dev.warsha.remoteble.protocol.DeviceHandle
import dev.warsha.remoteble.protocol.ErrorKind
import dev.warsha.remoteble.protocol.Op
import dev.warsha.remoteble.protocol.OpResult
import dev.warsha.remoteble.protocol.ScanFilter
import dev.warsha.remoteble.protocol.ServiceNode
import dev.warsha.remoteble.protocol.INCOMPATIBLE_PROTOCOL_CLOSE_REASON
import dev.warsha.remoteble.protocol.CLIENT_ID_HEADER
import dev.warsha.remoteble.agent.FakeAgent
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
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
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.server.cio.CIO
import io.ktor.server.application.install
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket

/**
 * A no-radio [BleBackend] that always succeeds, for tests that need [BleAgentBackend]'s real
 * cross-connection [dev.warsha.remoteble.agent.PeripheralRegistry] ownership semantics — unlike
 * [FakeAgent], which has no cross-client leasing at all. `agent`'s own `FakeBleBackend` lives in
 * its `commonTest` source set and isn't visible across the module boundary.
 */
private class MinimalBleBackend : BleBackend {
    override fun scan(filters: List<ScanFilter>) = emptyFlow<AdvertisementDto>()
    override suspend fun connect(device: DeviceHandle) = Unit
    override suspend fun disconnect(device: DeviceHandle) = Unit
    override suspend fun discover(device: DeviceHandle) = emptyList<ServiceNode>()
    override suspend fun read(device: DeviceHandle, char: CharRef) = ByteArray(0)
    override suspend fun write(device: DeviceHandle, char: CharRef, value: ByteArray, withResponse: Boolean) = Unit
    override fun observe(device: DeviceHandle, char: CharRef) = emptyFlow<ByteArray>()
    override suspend fun requestMtu(device: DeviceHandle, mtu: Int) = mtu
}

/**
 * Proves the transport seam: the unchanged session + RemoteGattClient/RemoteScanSource
 * run against a real Ktor WebSocket to the agent's FakeAgent over localhost. Same
 * behaviors as the in-memory Phase-2 suite, plus reconnection on server restart.
 */
class WebSocketEndToEndTest {

    private val device = DeviceHandle("FA:KE:00:00:00:0A")
    private val char = CharRef(
        service = "0000180d-0000-1000-8000-00805f9b34fb",
        characteristic = "00002a37-0000-1000-8000-00805f9b34fb",
    )

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val httpClient = defaultWebSocketHttpClient()

    @AfterTest
    fun tearDown() {
        httpClient.close()
        scope.cancel()
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun String.isLoopbackLiteral(): Boolean =
        this == "localhost" || this == "::1" || startsWith("127.")

    private fun incompatibleProtocolServer(port: Int): EmbeddedServer<*, *> =
        embeddedServer(CIO, port = port) {
            install(WebSockets)
            routing {
                webSocket("/agent") {
                    close(CloseReason(CloseReason.Codes.PROTOCOL_ERROR, INCOMPATIBLE_PROTOCOL_CLOSE_REASON))
                }
            }
        }.startAndAwaitReady(port)

    private fun EmbeddedServer<*, *>.startAndAwaitReady(port: Int): EmbeddedServer<*, *> {
        start(wait = false)
        val deadline = System.nanoTime() + 5.seconds.inWholeNanoseconds
        while (true) {
            try {
                Socket().use { it.connect(InetSocketAddress("localhost", port), 200) }
                return this
            } catch (_: IOException) {
                check(System.nanoTime() < deadline) { "server on port $port did not start" }
                Thread.sleep(10)
            }
        }
    }

    private fun transportTo(
        port: Int,
        backoff: Backoff = Backoff(),
        authToken: String? = null,
        autoReconnect: Boolean = true,
    ) = WebSocketAgentTransport(
        "ws://localhost:$port/agent", scope, httpClient,
        authToken = { authToken },
        reconnect = ReconnectPolicy(enabled = autoReconnect, backoff = backoff),
    )

    private suspend fun AgentSession.awaitConnected() =
        withTimeout(10.seconds) { transportState.first { it == TransportState.CONNECTED } }

    @Test
    fun fullOpSetAndStreamsOverWebSocket() = runBlocking {
        val port = freePort()
        val server = AgentWebSocketServer(port).also { it.startAndAwaitReady(port) }
        try {
            val session = DefaultAgentSession(transportTo(port), CborProtocolCodec(), scope)
            session.awaitConnected()
            val peripheral = RemoteGattClient(device, session)

            peripheral.connect()
            assertEquals(listOf<Byte>(0x42, 0x07), peripheral.read(char).toList())
            peripheral.write(char, byteArrayOf(1, 2, 3), withResponse = true)
            assertEquals(FakeAgent.DEFAULT_SERVICES, peripheral.discover())
            assertEquals(247, peripheral.requestMtu(247))

            val notifications = withTimeout(10.seconds) { peripheral.observe(char).take(3).toList() }
            assertEquals(3, notifications.size)
            assertEquals(FakeAgent.DEFAULT_NOTIFICATIONS[0].toList(), notifications[0].toList())

            val advertisements = withTimeout(10.seconds) {
                RemoteScanSource(session).advertisements().take(3).toList()
            }
            assertEquals(3, advertisements.size)
            assertEquals(FakeAgent.DEFAULT_ADVERTISEMENTS[0], advertisements[0])
        } finally {
            server.stop()
        }
        Unit
    }

    @Test
    fun requestTimesOutWhenAgentNeverReplies() = runBlocking {
        val port = freePort()
        val server = AgentWebSocketServer(port, backend = BlackholeBackend()).also { it.startAndAwaitReady(port) }
        try {
            val session = DefaultAgentSession(transportTo(port), CborProtocolCodec(), scope)
            session.awaitConnected()

            val result = session.request(Op.Read(device, char), timeout = 500.milliseconds, retry = RetryPolicies.None)

            val err = assertIs<OpResult.Err>(result)
            assertEquals(ErrorKind.TIMEOUT, err.error.kind)
        } finally {
            server.stop()
        }
        Unit
    }

    @Test
    fun incompatibleHelloClosesTheRealWebSocketWithTheStableProtocolSignal() = runBlocking {
        val port = freePort()
        val server = AgentWebSocketServer(port).also { it.startAndAwaitReady(port) }
        try {
            val socket = httpClient.webSocketSession(urlString = "ws://localhost:$port/agent")
            socket.send(Frame.Binary(true, CborProtocolCodec().encode(ClientHello(minVersion = 2, maxVersion = 3))))
            val reason = withTimeout(5.seconds) { socket.closeReason.await() }
            assertEquals(CloseReason.Codes.PROTOCOL_ERROR, reason?.knownReason)
            assertEquals(INCOMPATIBLE_PROTOCOL_CLOSE_REASON, reason?.message)
        } finally {
            server.stop()
        }
        Unit
    }

    @Test
    fun transportMapsARealIncompatibleCloseToTerminalStateWithoutReconnect() = runBlocking {
        val port = freePort()
        val server = incompatibleProtocolServer(port)
        try {
            val transport = transportTo(port, autoReconnect = false)
            transport.connect()
            withTimeout(5.seconds) {
                transport.state.first { it == TransportState.INCOMPATIBLE_PROTOCOL }
            }
            assertEquals(TransportState.INCOMPATIBLE_PROTOCOL, transport.state.value)
        } finally {
            server.stop()
        }
        Unit
    }

    @Test
    fun incompatibleHelloStillClosesWhenACommandArrivesFirst() = runBlocking {
        val port = freePort()
        val server = AgentWebSocketServer(port).also { it.startAndAwaitReady(port) }
        try {
            val socket = httpClient.webSocketSession(urlString = "ws://localhost:$port/agent")
            socket.send(Frame.Binary(true, CborProtocolCodec().encode(Command(1, Op.Connect(device)))))
            socket.send(Frame.Binary(true, CborProtocolCodec().encode(ClientHello(minVersion = 2, maxVersion = 3))))
            val reason = withTimeout(5.seconds) { socket.closeReason.await() }
            assertEquals(CloseReason.Codes.PROTOCOL_ERROR, reason?.knownReason)
            assertEquals(INCOMPATIBLE_PROTOCOL_CLOSE_REASON, reason?.message)
        } finally {
            server.stop()
        }
        Unit
    }

    @Test
    fun rejectsASecondLiveSocketForTheSameStableClientIdentity() = runBlocking {
        val port = freePort()
        val server = AgentWebSocketServer(port).also { it.startAndAwaitReady(port) }
        try {
            val first = httpClient.webSocketSession(urlString = "ws://localhost:$port/agent") {
                header(CLIENT_ID_HEADER, "shared-test-client")
            }
            // The client-visible handshake completes as soon as the 101 response lands, which
            // Ktor writes before the route body — where tryAcquire lives — ever runs. Opening
            // `second` right after that risks racing `first`'s own registration, so force a real
            // round-trip on `first` first: a reply can only arrive once the server has passed the
            // duplicate-session check and entered backend.serve for it.
            first.send(Frame.Binary(true, CborProtocolCodec().encode(ClientHello())))
            withTimeout(5.seconds) { first.incoming.receive() }

            val second = httpClient.webSocketSession(urlString = "ws://localhost:$port/agent") {
                header(CLIENT_ID_HEADER, "shared-test-client")
            }

            val reason = withTimeout(5.seconds) { second.closeReason.await() }
            assertEquals(CloseReason.Codes.VIOLATED_POLICY, reason?.knownReason)
            assertEquals(AgentWebSocketServer.DUPLICATE_SESSION_CLOSE_REASON, reason?.message)
            first.close()
        } finally {
            server.stop()
        }
        Unit
    }

    /**
     * AUTH-PRINCIPAL-01: two credentials reusing one stable client ID must not let a lease,
     * operation, or warm resume cross the principal boundary. `alpha` and `beta` share the raw
     * `X-RemoteBle-Client` value; the agent's actual ownership key is
     * `ClientCredentials.sessionKey(principal, stableClientId)` (`AgentWebSocketServer.kt`), so the
     * two connections must be treated as fully independent owners despite the shared raw id —
     * live, and still while `alpha`'s transport sits in its post-drop grace window (LEASE-GRACE-01).
     */
    @Test
    fun principalIsolationHoldsAcrossLeaseOperationsAndDuringTransportGrace() = runBlocking {
        val port = freePort()
        val server = AgentWebSocketServer(
            port = port,
            credentials = ClientCredentials.of(mapOf("alpha" to "secret-a", "beta" to "secret-b")),
            backend = BleAgentBackend(MinimalBleBackend()),
        ).also { it.startAndAwaitReady(port) }
        try {
            val sharedRawClientId = "shared-raw-client-id"
            fun sessionFor(secret: String) = DefaultAgentSession(
                WebSocketAgentTransport(
                    "ws://localhost:$port/agent", scope, httpClient,
                    authToken = { secret },
                    reconnect = ReconnectPolicy.None,
                    clientId = sharedRawClientId,
                ),
                CborProtocolCodec(),
                scope,
            )

            val sessionAlpha = sessionFor("secret-a")
            sessionAlpha.awaitConnected()
            val sessionBeta = sessionFor("secret-b")
            sessionBeta.awaitConnected()

            // Sharing the raw client id does not trip the duplicate-live-session check
            // (LEASE-DUPLICATE-01 is principal-scoped): both connections are accepted at once.
            assertEquals(TransportState.CONNECTED, sessionAlpha.transportState.value)
            assertEquals(TransportState.CONNECTED, sessionBeta.transportState.value)

            assertIs<OpResult.Ok>(sessionAlpha.request(Op.Connect(device)))

            // beta shares the raw client id but not the principal: it must not see alpha's lease.
            val betaConnectWhileLive = sessionBeta.request(Op.Connect(device))
            assertEquals(ErrorKind.PERIPHERAL_BUSY, assertIs<OpResult.Err>(betaConnectWhileLive).error.kind)
            val betaReadWhileLive = sessionBeta.request(Op.Read(device, char))
            assertEquals(ErrorKind.PERIPHERAL_BUSY, assertIs<OpResult.Err>(betaReadWhileLive).error.kind)

            // alpha's transport drops: the radio link stays warm, pending release within grace
            // (LEASE-GRACE-01). beta — same raw client id — still cannot warm-resume alpha's lease.
            sessionAlpha.close()
            val betaConnectDuringGrace = sessionBeta.request(Op.Connect(device))
            assertEquals(ErrorKind.PERIPHERAL_BUSY, assertIs<OpResult.Err>(betaConnectDuringGrace).error.kind)
        } finally {
            server.stop()
        }
        Unit
    }

    /**
     * AUTH-REVOKE-01: a credential revoked while a lease sits mid transport-grace must not be
     * able to resume it. The revoked principal's next connection attempt — even carrying the same
     * stable client id a warm-lease resume would use — fails re-authentication at the handshake
     * gate ([ClientCredentials.authenticate]) before the registry is ever consulted.
     */
    @Test
    fun revokedCredentialCannotResumeALeaseDuringTransportGrace() = runBlocking {
        val port = freePort()
        val credentials = ClientCredentials.of(mapOf("alpha" to "secret-a"))
        val server = AgentWebSocketServer(
            port = port,
            credentials = credentials,
            backend = BleAgentBackend(MinimalBleBackend()),
        ).also { it.startAndAwaitReady(port) }
        try {
            val resumingClientId = "resuming-client"
            val session = DefaultAgentSession(
                WebSocketAgentTransport(
                    "ws://localhost:$port/agent", scope, httpClient,
                    authToken = { "secret-a" },
                    reconnect = ReconnectPolicy.None,
                    clientId = resumingClientId,
                ),
                CborProtocolCodec(),
                scope,
            )
            session.awaitConnected()
            assertIs<OpResult.Ok>(session.request(Op.Connect(device)))

            // Revoke while the lease is still live, then drop the transport: the lease enters its
            // grace window (LEASE-GRACE-01) still owned by the now-revoked principal.
            credentials.revoke("alpha")
            session.close()

            // A resume attempt with the same stable client id and the revoked credential must
            // never reach CONNECTED — rejected at the handshake, not at the lease.
            val resumeSession = DefaultAgentSession(
                WebSocketAgentTransport(
                    "ws://localhost:$port/agent", scope, httpClient,
                    authToken = { "secret-a" },
                    reconnect = ReconnectPolicy.None,
                    clientId = resumingClientId,
                ),
                CborProtocolCodec(),
                scope,
            )
            val reached = withTimeoutOrNull(3.seconds) {
                resumeSession.transportState.first { it == TransportState.CONNECTED }
            }
            assertNull(reached, "a revoked credential must not be able to resume a warm lease")
        } finally {
            server.stop()
        }
        Unit
    }

    @Test
    fun dashboardReadsRequireTheirOwnOperatorCredential() = runBlocking {
        val port = freePort()
        val server = AgentWebSocketServer(
            port = port,
            authToken = TOKEN,
            operatorToken = "operator-secret",
            monitor = AgentMonitor(),
        ).also { it.startAndAwaitReady(port) }
        try {
            val endpoint = "http://localhost:$port/api/state"
            assertEquals(HttpStatusCode.Unauthorized, httpClient.get(endpoint).status)
            assertEquals(
                HttpStatusCode.Unauthorized,
                httpClient.get(endpoint) { header(HttpHeaders.Authorization, "Bearer $TOKEN") }.status,
            )
            assertEquals(
                HttpStatusCode.OK,
                httpClient.get(endpoint) { header(HttpHeaders.Authorization, "Bearer operator-secret") }.status,
            )
            assertEquals(
                HttpStatusCode.OK,
                httpClient.get(endpoint) {
                    header(HttpHeaders.Authorization, "Basic b3BlcmF0b3I6b3BlcmF0b3Itc2VjcmV0")
                }.status,
            )
        } finally {
            server.stop()
        }
        Unit
    }

    @Test
    fun withNoOperatorTokenTheDashboardIsAbsentRatherThanUnauthorized() = runBlocking {
        // The distinction matters and is easy to lose: 404 means the routes were never registered,
        // 401 means they exist and are guarded. This is the mobile agent's shipped default — no
        // operator credential, so no dashboard at all — and it is why a `404` on `/` is the healthy
        // answer from a phone-hosted agent rather than a symptom (Rig B case 1). If a future change
        // registered the routes unconditionally and relied on the credential check alone, an agent
        // with no operator token would start answering 401 here, and a misconfigured deployment
        // would look like a locked door instead of no door.
        val port = freePort()
        val server = AgentWebSocketServer(
            port = port,
            authToken = TOKEN,
            monitor = AgentMonitor(),
        ).also { it.startAndAwaitReady(port) }
        try {
            for (path in listOf("/", "/api/state", "/api/strict", "/api/log-level")) {
                assertEquals(
                    HttpStatusCode.NotFound,
                    httpClient.get("http://localhost:$port$path").status,
                    "$path must not exist without an operator credential",
                )
            }
            // The client plane is unaffected — the agent is still serving its actual purpose.
            assertEquals(
                HttpStatusCode.Unauthorized,
                httpClient.get("http://localhost:$port/agent").status,
                "the WebSocket endpoint should still be there, just not upgradable by a plain GET",
            )
        } finally {
            server.stop()
        }
        Unit
    }

    @Test
    fun theDashboardIsLoopbackOnlyUnlessRemoteAccessIsOptedInto() = runBlocking {
        // The dashboard is the high-privilege plane — every client address, every lease, the activity
        // log — and it travels over unencrypted HTTP, so by default it answers only this machine.
        // Binding a non-loopback host is what makes the test meaningful: connecting to 127.0.0.1 would
        // be a loopback request whatever the policy, so it could never fail.
        val lanHost = java.net.InetAddress.getLocalHost().hostAddress
        assertTrue(!lanHost.isLoopbackLiteral(), "test needs a non-loopback local address, got $lanHost")

        val port = freePort()
        val server = AgentWebSocketServer(
            port = port,
            host = "0.0.0.0",
            authToken = TOKEN,
            operatorToken = "operator-secret",
            monitor = AgentMonitor(),
        ).also { it.startAndAwaitReady(port) }
        try {
            val authorized: suspend (String) -> HttpStatusCode = { hostPart ->
                httpClient.get("http://$hostPart:$port/api/state") {
                    header(HttpHeaders.Authorization, "Bearer operator-secret")
                }.status
            }
            // Correct credential, wrong origin: 404, so it does not even advertise that it exists.
            assertEquals(
                HttpStatusCode.NotFound,
                authorized(lanHost),
                "a non-loopback request must not reach the dashboard by default",
            )
            assertEquals(HttpStatusCode.OK, authorized("127.0.0.1"), "loopback must still work")
        } finally {
            server.stop()
        }

        // Opted in, the same non-loopback request is served — which is what proves the refusal above
        // was the policy and not the address being unreachable in this environment.
        val openPort = freePort()
        val openServer = AgentWebSocketServer(
            port = openPort,
            host = "0.0.0.0",
            authToken = TOKEN,
            operatorToken = "operator-secret",
            allowRemoteDashboard = true,
            monitor = AgentMonitor(),
        ).also { it.startAndAwaitReady(openPort) }
        try {
            assertEquals(
                HttpStatusCode.OK,
                httpClient.get("http://$lanHost:$openPort/api/state") {
                    header(HttpHeaders.Authorization, "Bearer operator-secret")
                }.status,
                "with the opt-in set, the same off-device request must be served",
            )
        } finally {
            openServer.stop()
        }
        Unit
    }

    @Test
    fun anOperatorTokenEqualToAClientCredentialIsRejectedAtConstruction() = runBlocking {
        // The guard that keeps the two planes from silently collapsing into one. Without it, passing
        // the same string twice would hand every client an observer of all other clients' addresses,
        // leases and activity — exactly what the op plane refuses them (proved on real radio by Rig A
        // case 3). The mobile UI checks this before starting so the user gets an actionable message,
        // but the invariant belongs here, where it cannot be bypassed.
        assertFailsWith<IllegalArgumentException> {
            AgentWebSocketServer(port = freePort(), authToken = TOKEN, operatorToken = TOKEN)
        }
        Unit
    }

    @Test
    fun oversizedFrameIsRejectedBeforeProtocolDecoding() = runBlocking {
        val port = freePort()
        val server = AgentWebSocketServer(port).also { it.startAndAwaitReady(port) }
        try {
            val socket = httpClient.webSocketSession(urlString = "ws://localhost:$port/agent")
            socket.send(Frame.Binary(true, ByteArray(AgentWebSocketServer.MAX_FRAME_BYTES + 1)))
            val reason = withTimeout(5.seconds) { socket.closeReason.await() }
            assertEquals(CloseReason.Codes.TOO_BIG, reason?.knownReason)
        } finally {
            server.stop()
        }
        Unit
    }

    @Test
    fun serverRestartReconnectsAndInFlightFailsCleanly() = runBlocking {
        val port = freePort()
        var server = AgentWebSocketServer(port).also { it.startAndAwaitReady(port) }
        try {
            val session = DefaultAgentSession(
                transportTo(port, backoff = Backoff(50.milliseconds, 200.milliseconds)),
                CborProtocolCodec(),
                scope,
            )
            session.awaitConnected()
            assertIs<OpResult.Ok>(session.request(Op.Connect(device)))

            // Server down -> transport drops -> requests fail with TRANSPORT_LOST.
            server.stop()
            withTimeout(10.seconds) { session.transportState.first { it == TransportState.DISCONNECTED } }
            val lost = session.request(Op.Connect(device), timeout = 1.seconds)
            assertEquals(ErrorKind.TRANSPORT_LOST, assertIs<OpResult.Err>(lost).error.kind)

            // Server back -> transport reconnects -> new requests succeed.
            server = AgentWebSocketServer(port).also { it.startAndAwaitReady(port) }
            withTimeout(15.seconds) { session.transportState.first { it == TransportState.CONNECTED } }
            assertIs<OpResult.Ok>(session.request(Op.Connect(device)))
        } finally {
            server.stop()
        }
        Unit
    }

    @Test
    fun activeSubscriptionResumesAfterServerRestart() = runBlocking {
        val port = freePort()
        var server = AgentWebSocketServer(port).also { it.startAndAwaitReady(port) }
        try {
            val session = DefaultAgentSession(
                transportTo(port, backoff = Backoff(50.milliseconds, 200.milliseconds)),
                CborProtocolCodec(),
                scope,
            )
            session.awaitConnected()
            val peripheral = RemoteGattClient(device, session)
            peripheral.connect()

            // One long-lived subscription, collected once and never re-collected by the app.
            val received = Channel<ByteArray>(Channel.UNLIMITED)
            val observer = peripheral.observe(char).onEach { received.trySend(it) }.launchIn(scope)
            try {
                // Notifications stream before the outage.
                withTimeout(10.seconds) { received.receive() }

                // Drop the agent. A fresh agent keeps NO subscription state.
                server.stop()
                withTimeout(10.seconds) { session.transportState.first { it == TransportState.DISCONNECTED } }
                server = AgentWebSocketServer(port).also { it.startAndAwaitReady(port) }
                withTimeout(15.seconds) { session.transportState.first { it == TransportState.CONNECTED } }

                // Discard any pre-drop backlog, then prove a SUSTAINED stream resumes on the
                // same flow without the app re-subscribing — only the session's replay of
                // observe.start could restore it on the new agent.
                while (received.tryReceive().isSuccess) { /* drop backlog buffered before/at the drop */ }
                repeat(5) { withTimeout(10.seconds) { received.receive() } }
            } finally {
                observer.cancel()
            }
        } finally {
            server.stop()
        }
        Unit
    }

    @Test
    fun connParamsReplayedAfterServerRestart() = runBlocking {
        val port = freePort()
        // A fresh FakeAgent per connection keeps no conn.params state of its own, so the only way
        // a *new* agent instance sees a "conn.params" command after the restart is the session's
        // own reconcileOnReconnect replaying it — this taps the wire to prove that, the same way
        // activeSubscriptionResumesAfterServerRestart proves observe.start replay via the resumed
        // notification stream.
        val received = Channel<Op.SetConnParams>(Channel.UNLIMITED)
        fun spyBackend() = AgentBackend { incoming, outgoing, backendScope, _, _ ->
            val codec = CborProtocolCodec()
            val tapped = incoming.onEach { bytes ->
                val frame = codec.decode(bytes)
                if (frame is Command) (frame.op as? Op.SetConnParams)?.let { received.trySend(it) }
            }
            FakeAgent(tapped, outgoing, backendScope, FakeAgent.Config()).start()
        }

        var server = AgentWebSocketServer(port, backend = spyBackend()).also { it.startAndAwaitReady(port) }
        try {
            val session = DefaultAgentSession(
                transportTo(port, backoff = Backoff(50.milliseconds, 200.milliseconds)),
                CborProtocolCodec(),
                scope,
            )
            session.awaitConnected()
            val peripheral = RemoteGattClient(device, session)
            peripheral.connect()
            peripheral.setConnParams(ConnProfile.LOW_LATENCY)
            withTimeout(10.seconds) { received.receive() } // the initial explicit request

            server.stop()
            withTimeout(10.seconds) { session.transportState.first { it == TransportState.DISCONNECTED } }
            server = AgentWebSocketServer(port, backend = spyBackend()).also { it.startAndAwaitReady(port) }
            withTimeout(15.seconds) { session.transportState.first { it == TransportState.CONNECTED } }

            val replayed = withTimeout(10.seconds) { received.receive() }
            assertEquals(device, replayed.device)
            assertEquals(ConnProfile.LOW_LATENCY, replayed.profile)
        } finally {
            server.stop()
        }
        Unit
    }

    @Test
    fun disconnectedDeviceIsNotReplayedOnReconnect() = runBlocking {
        val port = freePort()
        var server = AgentWebSocketServer(port).also { it.startAndAwaitReady(port) }
        try {
            val session = DefaultAgentSession(
                transportTo(port, backoff = Backoff(50.milliseconds, 200.milliseconds)),
                CborProtocolCodec(),
                scope,
            )
            session.awaitConnected()
            val peripheral = RemoteGattClient(device, session)
            peripheral.connect()

            val received = Channel<ByteArray>(Channel.UNLIMITED)
            val observer = peripheral.observe(char).onEach { received.trySend(it) }.launchIn(scope)
            try {
                withTimeout(10.seconds) { received.receive() } // streaming before the drop

                // Explicitly disconnect the device: the session must forget it AND its
                // subscription, so neither is replayed when the IP link comes back.
                assertIs<OpResult.Ok>(session.request(Op.Disconnect(device)))

                server.stop()
                withTimeout(10.seconds) { session.transportState.first { it == TransportState.DISCONNECTED } }
                server = AgentWebSocketServer(port).also { it.startAndAwaitReady(port) }
                withTimeout(15.seconds) { session.transportState.first { it == TransportState.CONNECTED } }

                // The fresh agent never received a replayed observe.start, so the subscription
                // stays dark. Drain any pre-drop backlog, then assert no new notifications.
                while (received.tryReceive().isSuccess) { /* discard pre-drop backlog */ }
                val resumed = withTimeoutOrNull(1500.milliseconds) { received.receive() }
                assertNull(resumed, "a disconnected device's subscription must not be replayed")
            } finally {
                observer.cancel()
            }
        } finally {
            server.stop()
        }
        Unit
    }

    @Test
    fun acceptsConnectionWithValidToken() = runBlocking {
        val port = freePort()
        val server = AgentWebSocketServer(port, authToken = TOKEN).also { it.startAndAwaitReady(port) }
        try {
            val session = DefaultAgentSession(transportTo(port, authToken = TOKEN), CborProtocolCodec(), scope)
            session.awaitConnected()
            assertIs<OpResult.Ok>(session.request(Op.Connect(device)))
        } finally {
            server.stop()
        }
        Unit
    }

    @Test
    fun rejectsConnectionWithWrongToken() = runBlocking {
        val port = freePort()
        val server = AgentWebSocketServer(port, authToken = TOKEN).also { it.startAndAwaitReady(port) }
        try {
            // No reconnect loop: the 401 fails the single handshake attempt outright.
            val session = DefaultAgentSession(
                transportTo(port, authToken = "wrong-token", autoReconnect = false),
                CborProtocolCodec(),
                scope,
            )
            // The handshake is rejected (401), so the transport never reaches CONNECTED.
            val reached = withTimeoutOrNull(3.seconds) {
                session.transportState.first { it == TransportState.CONNECTED }
            }
            assertNull(reached, "connection with a wrong token must not establish")

            val result = session.request(Op.Connect(device), timeout = 1.seconds)
            assertEquals(ErrorKind.TRANSPORT_LOST, assertIs<OpResult.Err>(result).error.kind)
        } finally {
            server.stop()
        }
        Unit
    }

    @Test
    fun authTokenProviderIsReadFreshOnEachReconnect() = runBlocking {
        val port = freePort()
        // The provider hands back whatever `currentToken` holds *now* — never a cached first value.
        val currentToken = AtomicReference(TOKEN)
        val calls = AtomicInteger(0)
        val provider: suspend () -> String? = { calls.incrementAndGet(); currentToken.get() }

        var server = AgentWebSocketServer(port, authToken = TOKEN).also { it.startAndAwaitReady(port) }
        try {
            val transport = WebSocketAgentTransport(
                "ws://localhost:$port/agent", scope, httpClient,
                authToken = provider,
                reconnect = ReconnectPolicy(backoff = Backoff(50.milliseconds, 200.milliseconds)),
            )
            val session = DefaultAgentSession(transport, CborProtocolCodec(), scope)
            session.awaitConnected()
            val afterFirst = calls.get()
            assertTrue(afterFirst >= 1, "provider must be invoked for the initial connection")

            // Rotate the credential on BOTH ends. A transport that cached the first token would
            // now present a stale value and get 401'd forever; reconnect proves it re-reads.
            server.stop()
            withTimeout(10.seconds) { session.transportState.first { it == TransportState.DISCONNECTED } }
            currentToken.set(TOKEN2)
            server = AgentWebSocketServer(port, authToken = TOKEN2).also { it.startAndAwaitReady(port) }

            withTimeout(15.seconds) { session.transportState.first { it == TransportState.CONNECTED } }
            assertIs<OpResult.Ok>(session.request(Op.Connect(device)))
            assertTrue(calls.get() > afterFirst, "provider must be re-invoked on reconnect")
        } finally {
            server.stop()
        }
        Unit
    }

    @Test
    fun throwingAuthTokenProviderFoldsIntoBackoffAndRecovers() = runBlocking {
        val port = freePort()
        val calls = AtomicInteger(0)
        // Succeeds for the initial connect, then throws on the next two attempts (as a token
        // refresh would on failure) before recovering — the backoff loop must survive the throw.
        val provider: suspend () -> String? = {
            val n = calls.incrementAndGet()
            if (n in 2..3) throw RuntimeException("token refresh failed")
            TOKEN
        }
        var server = AgentWebSocketServer(port, authToken = TOKEN).also { it.startAndAwaitReady(port) }
        try {
            val transport = WebSocketAgentTransport(
                "ws://localhost:$port/agent", scope, httpClient,
                authToken = provider,
                reconnect = ReconnectPolicy(backoff = Backoff(50.milliseconds, 200.milliseconds)),
            )
            val session = DefaultAgentSession(transport, CborProtocolCodec(), scope)
            session.awaitConnected() // call #1 → TOKEN

            // Force a reconnect; attempts #2/#3 throw from the provider but must not kill the loop.
            server.stop()
            withTimeout(10.seconds) { session.transportState.first { it == TransportState.DISCONNECTED } }
            server = AgentWebSocketServer(port, authToken = TOKEN).also { it.startAndAwaitReady(port) }

            withTimeout(15.seconds) { session.transportState.first { it == TransportState.CONNECTED } }
            assertIs<OpResult.Ok>(session.request(Op.Connect(device)))
            assertTrue(calls.get() >= 4, "backoff must have retried past the throwing attempts")
        } finally {
            server.stop()
        }
        Unit
    }

    @Test
    fun initialConnectRetriesUntilAgentAppears() = runBlocking {
        val port = freePort()
        // No server yet — the first connect attempt fails. With reconnect enabled the transport
        // must keep trying (not strand the client), so a client that starts before its agent
        // still connects once the agent comes up.
        val session = DefaultAgentSession(
            transportTo(port, backoff = Backoff(50.milliseconds, 200.milliseconds)),
            CborProtocolCodec(),
            scope,
        )
        delay(300) // let the initial attempt fail and the backoff loop take over
        val server = AgentWebSocketServer(port).also { it.startAndAwaitReady(port) }
        try {
            withTimeout(15.seconds) { session.transportState.first { it == TransportState.CONNECTED } }
            assertIs<OpResult.Ok>(session.request(Op.Connect(device)))
        } finally {
            server.stop()
        }
        Unit
    }

    @Test
    fun boundedReconnectGivesUpAfterMaxAttempts() = runBlocking {
        val port = freePort() // nothing ever listens here
        val gaveUp = CompletableDeferred<Unit>()
        val transport = WebSocketAgentTransport(
            "ws://localhost:$port/agent", scope, httpClient,
            reconnect = ReconnectPolicy(
                backoff = Backoff(20.milliseconds, 40.milliseconds),
                maxAttempts = 3,
                onGaveUp = { gaveUp.complete(Unit) },
            ),
        )
        // Initial connect fails (no server) and arms the bounded loop; it must give up, not spin.
        runCatching { transport.connect() }
        withTimeout(5.seconds) { gaveUp.await() }
        // GAVE_UP, not DISCONNECTED: this used to rest at DISCONNECTED, which made "still
        // retrying" and "stopped retrying" indistinguishable to every observer — the root of
        // RemotePeripheral.state sitting at Connected forever (Rig B follow-up 12).
        assertEquals(TransportState.GAVE_UP, transport.state.value)
    }

    @Test
    fun onGaveUpSeesTheGiveUpStateItWasTriggeredBy() = runBlocking {
        // The callback is a caller's hook and may read the transport; it must not observe the
        // state that preceded its own trigger.
        val port = freePort()
        val observed = CompletableDeferred<TransportState>()
        lateinit var transport: WebSocketAgentTransport
        transport = WebSocketAgentTransport(
            "ws://localhost:$port/agent", scope, httpClient,
            reconnect = ReconnectPolicy(
                backoff = Backoff(20.milliseconds, 40.milliseconds),
                maxAttempts = 2,
                onGaveUp = { observed.complete(transport.state.value) },
            ),
        )
        runCatching { transport.connect() }
        assertEquals(TransportState.GAVE_UP, withTimeout(5.seconds) { observed.await() })
    }

    private companion object {
        const val TOKEN = "s3cr3t-bearer-token"
        const val TOKEN2 = "rotated-bearer-token"
    }
}
