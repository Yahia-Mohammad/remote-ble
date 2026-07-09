package dev.warsha.remoteble.client

import dev.warsha.remoteble.agent.AgentWebSocketServer
import dev.warsha.remoteble.agent.BleAgentBackend
import dev.warsha.remoteble.protocol.AgentException
import dev.warsha.remoteble.protocol.CborProtocolCodec
import dev.warsha.remoteble.protocol.CharRef
import dev.warsha.remoteble.protocol.DeviceHandle
import dev.warsha.remoteble.protocol.ErrorKind
import dev.warsha.remoteble.protocol.Op
import dev.warsha.remoteble.protocol.OpResult
import com.juul.kable.Peripheral
import com.juul.kable.State
import java.net.ServerSocket
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Phase 6 item 5 — error-path / chaos coverage through the *production* agent op handler
 * over a real WebSocket, with a stub radio injecting failures. Asserts the client surfaces
 * the right [ErrorKind], stays usable after a failed op, and reflects an agent-reported
 * disconnect in Kable's [State] machine.
 */
class ErrorPathTest {

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

    private suspend fun connectedSession(port: Int): AgentSession {
        val session = DefaultAgentSession(
            WebSocketAgentTransport("ws://localhost:$port/agent", scope, httpClient),
            CborProtocolCodec(),
            scope,
        )
        withTimeout(10.seconds) { session.transportState.first { it == TransportState.CONNECTED } }
        return session
    }

    @Test
    fun writeRejectionSurfacesAndSessionStaysUsable() = runBlocking {
        val port = freePort()
        val server = AgentWebSocketServer(port, backend = BleAgentBackend(StubBleBackend(failWrites = true)))
            .also { it.startAndAwaitReady(port) }
        try {
            val session = connectedSession(port)
            val peripheral = RemoteGattClient(DeviceHandle(StubBleBackend.DEVICE), session)
            peripheral.connect()

            // The radio rejects the write; the exact ErrorKind must reach the caller.
            val failure = assertFailsWith<AgentException> {
                withTimeout(10.seconds) { peripheral.write(char, byteArrayOf(1, 2, 3), withResponse = true) }
            }
            assertEquals(ErrorKind.WRITE_FAILED, failure.error.kind)

            // A failed op must not poison the session — the next read succeeds.
            assertEquals(listOf<Byte>(0x11, 0x22), withTimeout(10.seconds) { peripheral.read(char) }.toList())
        } finally {
            server.stop()
        }
        Unit
    }

    @Test
    fun readFailureMapsToErrorKind() = runBlocking {
        val port = freePort()
        val server = AgentWebSocketServer(port, backend = BleAgentBackend(StubBleBackend(failReads = true)))
            .also { it.startAndAwaitReady(port) }
        try {
            val session = connectedSession(port)
            val peripheral = RemoteGattClient(DeviceHandle(StubBleBackend.DEVICE), session)
            peripheral.connect()

            val failure = assertFailsWith<AgentException> {
                withTimeout(10.seconds) { peripheral.read(char) }
            }
            assertEquals(ErrorKind.READ_FAILED, failure.error.kind)
        } finally {
            server.stop()
        }
        Unit
    }

    @Test
    fun kablePeripheralReflectsAgentReportedDisconnect() = runBlocking {
        val port = freePort()
        val server = AgentWebSocketServer(port, backend = BleAgentBackend(StubBleBackend())).also { it.startAndAwaitReady(port) }
        try {
            val session = connectedSession(port)
            val handle = DeviceHandle(StubBleBackend.DEVICE)
            val peripheral: Peripheral = RemotePeripheral(handle, session)
            peripheral.connect()
            assertIs<State.Connected>(peripheral.state.value)

            // Simulate the device dropping mid-session: the agent reports DISCONNECTED
            // (here driven by an out-of-band Disconnect op on the same session). The
            // peripheral must reflect it via its State machine and clear discovered services.
            assertIs<OpResult.Ok>(session.request(Op.Disconnect(handle)))

            withTimeout(10.seconds) { peripheral.state.first { it is State.Disconnected } }
            assertEquals(null, peripheral.services.value)
            peripheral.close()
        } finally {
            server.stop()
        }
        Unit
    }
}
