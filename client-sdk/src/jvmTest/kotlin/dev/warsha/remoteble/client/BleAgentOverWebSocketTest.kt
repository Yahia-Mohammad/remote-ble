package dev.warsha.remoteble.client

import dev.warsha.remoteble.agent.AgentWebSocketServer
import dev.warsha.remoteble.agent.BleAgentBackend
import dev.warsha.remoteble.agent.BleBackend
import dev.warsha.remoteble.agent.ClientCredentials
import dev.warsha.remoteble.agent.SimulatedBleBackend
import dev.warsha.remoteble.agent.SimulationProfile
import dev.warsha.remoteble.protocol.AdvertisementDto
import dev.warsha.remoteble.protocol.AgentStatusDto
import dev.warsha.remoteble.protocol.CborProtocolCodec
import dev.warsha.remoteble.protocol.CharNode
import dev.warsha.remoteble.protocol.CharRef
import dev.warsha.remoteble.protocol.DeviceHandle
import dev.warsha.remoteble.protocol.ScanFilter
import dev.warsha.remoteble.protocol.ServiceNode
import java.net.ServerSocket
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
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
 * End-to-end through the *production* agent op handler ([dev.warsha.remoteble.agent.BleAgent])
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
        val server = AgentWebSocketServer(port, backend = BleAgentBackend(StubBleBackend())).also { it.startAndAwaitReady(port) }
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

    @Test
    fun simulatedProfileDrivesTheOrdinaryClientOverARealSocket() = runBlocking {
        val port = freePort()
        val simulated = SimulatedBleBackend(SimulationProfile.decode(SIMULATED_PROFILE))
        val server = AgentWebSocketServer(port, backend = BleAgentBackend(simulated)).also { it.startAndAwaitReady(port) }
        try {
            val session = DefaultAgentSession(
                WebSocketAgentTransport("ws://localhost:$port/agent", scope, httpClient),
                CborProtocolCodec(),
                scope,
            )
            withTimeout(10.seconds) { session.transportState.first { it == TransportState.CONNECTED } }

            val advertisement = withTimeout(10.seconds) { RemoteScanSource(session).advertisements().first() }
            // Not asserted to equal the profile's "sim-hrm-1": the simulated backend declares
            // IdentifierFormat.STRING, so a client whose own format can't hold an arbitrary string
            // (a UUID/MAC host) is handed a synthesized handle instead. What must hold on every
            // host is that whatever handle arrives routes ops back to the simulated peripheral —
            // which is what the rest of this test exercises.
            val peripheral = RemoteGattClient(advertisement.device, session)
            val heartRate = CharRef("180d", "2a37")
            val controlPoint = CharRef("180d", "2a39")
            val battery = CharRef("180f", "2a19")

            peripheral.connect()
            assertEquals(2, peripheral.discover().size)
            assertEquals(listOf<Byte>(0x64), peripheral.read(battery).toList())
            peripheral.write(controlPoint, byteArrayOf(0x01), withResponse = true)
            assertEquals(listOf(listOf<Byte>(0, 60), listOf<Byte>(0, 61)),
                withTimeout(10.seconds) { peripheral.observe(heartRate).take(2).toList() }.map { it.toList() })
            peripheral.disconnect()
        } finally {
            server.stop()
        }
        Unit
    }

    @Test
    fun agentStatusCarriesOperatorScopeOnlyForTheOperatorCredential() = runBlocking {
        val port = freePort()
        val server = AgentWebSocketServer(
            port,
            backend = BleAgentBackend(StubBleBackend()),
            credentials = ClientCredentials.of(mapOf("lab-a" to "client-secret")),
            operatorToken = "operator-secret",
        ).also { it.startAndAwaitReady(port) }
        try {
            suspend fun statusWith(operator: String?): AgentStatusDto {
                val session = DefaultAgentSession(
                    WebSocketAgentTransport(
                        "ws://localhost:$port/agent",
                        scope,
                        httpClient,
                        authToken = { "client-secret" },
                        operatorToken = { operator },
                    ),
                    CborProtocolCodec(),
                    scope,
                )
                withTimeout(10.seconds) { session.transportState.first { it == TransportState.CONNECTED } }
                return withTimeout(10.seconds) { requireNotNull(session.agentStatus()) }
            }

            // The client credential alone buys nothing: a normal bearer must not carry operator reach.
            assertFalse(statusWith(null).operatorScope)
            // Nor does a wrong operator secret — and, critically, it does not fail the connection
            // either, which is what lets a caller report "no operator credential" rather than
            // "agent unreachable".
            assertFalse(statusWith("not-the-operator-secret").operatorScope)
            assertTrue(statusWith("operator-secret").operatorScope)
        } finally {
            server.stop()
        }
        Unit
    }

    private companion object {
        val SIMULATED_PROFILE = """
            {
              "schemaVersion": 1,
              "peripherals": [{
                "id": "sim-hrm-1",
                "advertisement": { "name": "Sim HRM", "serviceUuids": ["180d", "180f"], "rssi": -50, "intervalMs": 50 },
                "services": [
                  { "uuid": "180d", "characteristics": [
                    { "uuid": "2a37", "properties": ["notify"], "notify": { "intervalMs": 50, "values": { "sequence": ["003c", "003d"] } } },
                    { "uuid": "2a39", "properties": ["write"], "write": { "accept": true } }
                  ] },
                  { "uuid": "180f", "characteristics": [
                    { "uuid": "2a19", "properties": ["read"], "read": { "static": "64" } }
                  ] }
                ]
              }]
            }
        """.trimIndent()
    }
}
