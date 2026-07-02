package dev.warsha.ble.remoteble.client

import dev.warsha.ble.remoteble.agent.AgentWebSocketServer
import dev.warsha.ble.remoteble.agent.BleAgentBackend
import dev.warsha.ble.remoteble.agent.BleBackend
import dev.warsha.ble.remoteble.protocol.AdvertisementDto
import dev.warsha.ble.remoteble.protocol.CborProtocolCodec
import dev.warsha.ble.remoteble.protocol.CharNode
import dev.warsha.ble.remoteble.protocol.CharRef
import dev.warsha.ble.remoteble.protocol.DeviceHandle
import dev.warsha.ble.remoteble.protocol.ScanFilter
import dev.warsha.ble.remoteble.protocol.ServiceNode
import java.net.ServerSocket
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * End-to-end through the *production* agent op handler ([dev.warsha.ble.remoteble.agent.BleAgent])
 * behind the real WebSocket server, driven by the unchanged client session — with a stub
 * [BleBackend] standing in for the radio. Proves the whole remote path wires up; the live
 * Blue-Falcon engine is exercised separately on hardware (Phase 4/7).
 */
class BleAgentOverWebSocketTest {

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

    @Test
    fun productionAgentHandlerOverWebSocket() = runBlocking {
        val port = freePort()
        val server = AgentWebSocketServer(port, backend = BleAgentBackend(StubBleBackend())).also { it.start() }
        try {
            val session = DefaultAgentSession(
                WebSocketAgentTransport("ws://localhost:$port/agent", scope, httpClient),
                CborProtocolCodec(),
                scope,
            )
            withTimeout(10.seconds) { session.transportState.first { it == TransportState.CONNECTED } }

            val peripheral = RemoteGattClient(DeviceHandle("STUB-0"), session)
            peripheral.connect()
            assertEquals(listOf<Byte>(0x11, 0x22), peripheral.read(char).toList())
            assertEquals(1, peripheral.discover().size)

            val notifications = withTimeout(10.seconds) { peripheral.observe(char).take(2).toList() }
            assertEquals(2, notifications.size)

            val advertisements = withTimeout(10.seconds) { RemoteScanSource(session).advertisements().take(2).toList() }
            assertEquals(2, advertisements.size)
        } finally {
            server.stop()
        }
        Unit
    }
}
