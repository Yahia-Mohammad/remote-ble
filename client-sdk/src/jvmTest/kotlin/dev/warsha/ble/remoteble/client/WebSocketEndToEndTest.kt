package dev.warsha.ble.remoteble.client

import dev.warsha.ble.remoteble.agent.AgentWebSocketServer
import dev.warsha.ble.remoteble.agent.BlackholeBackend
import dev.warsha.ble.remoteble.protocol.CborProtocolCodec
import dev.warsha.ble.remoteble.protocol.CharRef
import dev.warsha.ble.remoteble.protocol.DeviceHandle
import dev.warsha.ble.remoteble.protocol.ErrorKind
import dev.warsha.ble.remoteble.protocol.Op
import dev.warsha.ble.remoteble.protocol.OpResult
import dev.warsha.ble.remoteble.agent.FakeAgent
import java.net.ServerSocket
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

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

    private fun transportTo(
        port: Int,
        backoff: Backoff = Backoff(),
        authToken: String? = null,
        autoReconnect: Boolean = true,
    ) = WebSocketAgentTransport(
        "ws://localhost:$port/agent", scope, httpClient,
        authToken = authToken, autoReconnect = autoReconnect, backoff = backoff,
    )

    private suspend fun AgentSession.awaitConnected() =
        withTimeout(10.seconds) { transportState.first { it == TransportState.CONNECTED } }

    @Test
    fun fullOpSetAndStreamsOverWebSocket() = runBlocking {
        val port = freePort()
        val server = AgentWebSocketServer(port).also { it.start() }
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
        val server = AgentWebSocketServer(port, backend = BlackholeBackend()).also { it.start() }
        try {
            val session = DefaultAgentSession(transportTo(port), CborProtocolCodec(), scope)
            session.awaitConnected()

            val result = session.request(Op.Read(device, char), timeout = 500.milliseconds)

            val err = assertIs<OpResult.Err>(result)
            assertEquals(ErrorKind.TIMEOUT, err.error.kind)
        } finally {
            server.stop()
        }
        Unit
    }

    @Test
    fun serverRestartReconnectsAndInFlightFailsCleanly() = runBlocking {
        val port = freePort()
        var server = AgentWebSocketServer(port).also { it.start() }
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
            server = AgentWebSocketServer(port).also { it.start() }
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
        var server = AgentWebSocketServer(port).also { it.start() }
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
                server = AgentWebSocketServer(port).also { it.start() }
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
    fun disconnectedDeviceIsNotReplayedOnReconnect() = runBlocking {
        val port = freePort()
        var server = AgentWebSocketServer(port).also { it.start() }
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
                server = AgentWebSocketServer(port).also { it.start() }
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
        val server = AgentWebSocketServer(port, authToken = TOKEN).also { it.start() }
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
        val server = AgentWebSocketServer(port, authToken = TOKEN).also { it.start() }
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

    private companion object {
        const val TOKEN = "s3cr3t-bearer-token"
    }
}
