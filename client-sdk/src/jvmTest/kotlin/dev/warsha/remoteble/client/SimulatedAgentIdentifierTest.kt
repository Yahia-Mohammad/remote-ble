@file:OptIn(ExperimentalApi::class)

package dev.warsha.remoteble.client

import com.juul.kable.ExperimentalApi
import dev.warsha.remoteble.agent.AgentWebSocketServer
import dev.warsha.remoteble.agent.BleAgentBackend
import dev.warsha.remoteble.agent.SimulatedBleBackend
import dev.warsha.remoteble.agent.SimulationProfile
import dev.warsha.remoteble.protocol.CborProtocolCodec
import dev.warsha.remoteble.protocol.IdentifierFormat
import java.net.ServerSocket
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * The radio-less guard for the Kable-facing `.identifier` path, which the rest of the simulated
 * coverage misses: [BleAgentOverWebSocketTest] drives `RemoteScanSource`/`RemoteGattClient` by
 * [dev.warsha.remoteble.protocol.DeviceHandle] and so never converts a handle into a platform
 * `Identifier`. Real callers do: `:client-ui` builds every scan-screen row through
 * `DiscoveredDevice.from`, which reads `RemoteAdvertisement.identifier`.
 *
 * `:e2e-runner:scanRun` used to read it too and died against the canonical profile, but `5005439`
 * switched it to `.handle` — that removed the visible symptom while leaving the cause in place,
 * which is why this gate asserts the conversion rather than trusting any one caller to keep
 * exercising it.
 *
 * Runs the production wiring — real WebSocket server, real [BleAgentBackend], real
 * [SimulatedBleBackend] — with no Bluetooth hardware.
 *
 * Host-conditional, like [RemoteIdentifierJvmTest]: on the JVM a Kable `Identifier` is the *host*
 * radio's native id, so what a handle can become depends on the machine this test runs on.
 */
class SimulatedAgentIdentifierTest {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val httpClient = defaultWebSocketHttpClient()

    @AfterTest
    fun tearDown() {
        httpClient.close()
        scope.cancel()
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    @Test
    fun simulatedAdvertisementYieldsAUsableIdentifierOnTranslatableHosts() = runBlocking {
        val port = freePort()
        val backend = SimulatedBleBackend(SimulationProfile.decode(PROFILE))
        val server = AgentWebSocketServer(port, backend = BleAgentBackend(backend)).also { it.startAndAwaitReady(port) }
        try {
            val session = DefaultAgentSession(
                WebSocketAgentTransport("ws://localhost:$port/agent", scope, httpClient),
                CborProtocolCodec(),
                scope,
            )
            withTimeout(10.seconds) { session.transportState.first { it == TransportState.CONNECTED } }

            // The scan every client makes; `:client-ui` then reads `.identifier` off each result.
            val advertisement = withTimeout(10.seconds) { RemoteScanner(session).advertisements.first() }
            assertEquals("Sim HRM", advertisement.name)

            when (currentIdentifierFormat()) {
                // The translator synthesizes a handle in the host's format, so the conversion
                // succeeds. Before the fix this threw on the profile id. `identifier` is
                // non-nullable, so assert the rewrite actually happened rather than only that
                // evaluating the lazy property did not throw.
                IdentifierFormat.UUID, IdentifierFormat.MAC_ADDRESS -> {
                    assertNotEquals("sim-hrm-1", advertisement.handle.value)
                    assertEquals(
                        advertisement.handle.value.lowercase(),
                        advertisement.identifier.toString().lowercase(),
                        "the synthesized handle must be exactly what the client parses as its Identifier",
                    )
                }
                // BLUEZ_JSON synthesis is still stubbed (see HandleTranslator), so the raw profile
                // id passes through and `.identifier` keeps the documented `.handle` fallback.
                // This asserts a known gap: implementing bluez synthesis should flip it.
                IdentifierFormat.BLUEZ_JSON -> {
                    assertEquals("sim-hrm-1", advertisement.handle.value)
                    assertFailsWith<RemoteIdentifierUnavailableException> { advertisement.identifier }
                }
                // The JVM client never declares STRING.
                IdentifierFormat.STRING -> error("unexpected JVM identifier format")
            }
        } finally {
            server.stop()
        }
        Unit
    }

    private companion object {
        val PROFILE = """
            {
              "schemaVersion": 1,
              "peripherals": [{
                "id": "sim-hrm-1",
                "advertisement": { "name": "Sim HRM", "serviceUuids": ["180d"], "rssi": -50, "intervalMs": 50 },
                "services": [
                  { "uuid": "180d", "characteristics": [
                    { "uuid": "2a19", "properties": ["read"], "read": { "static": "64" } }
                  ] }
                ]
              }]
            }
        """.trimIndent()
    }
}
