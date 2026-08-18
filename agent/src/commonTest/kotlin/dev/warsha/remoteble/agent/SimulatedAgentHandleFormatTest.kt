package dev.warsha.remoteble.agent

import dev.warsha.remoteble.protocol.AgentEvent
import dev.warsha.remoteble.protocol.Capabilities
import dev.warsha.remoteble.protocol.CborProtocolCodec
import dev.warsha.remoteble.protocol.ClientHello
import dev.warsha.remoteble.protocol.Command
import dev.warsha.remoteble.protocol.DeviceHandle
import dev.warsha.remoteble.protocol.Event
import dev.warsha.remoteble.protocol.Frame
import dev.warsha.remoteble.protocol.IdentifierFormat
import dev.warsha.remoteble.protocol.Op
import dev.warsha.remoteble.protocol.OpResult
import dev.warsha.remoteble.protocol.Reply
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest

/**
 * The simulated agent's handles are profile-declared strings ("sim-hrm-1"), not radio ids, so a
 * client whose platform `Identifier` can't hold an arbitrary string must be handed a translated
 * one. That only happens if the agent declares the format the *backend* mints in
 * ([BleBackend.handleFormat]) rather than the host radio's — otherwise a macOS-host simulated agent
 * claims UUID, [HandleTranslator] sees client==agent and skips the rewrite, and the client receives
 * "sim-hrm-1" as a UUID. The client SDK then throws `RemoteIdentifierUnavailableException` off any
 * access of `.identifier` — which `:client-ui` performs for every sighting on its scan screen
 * (`DiscoveredDevice.from`), so an Apple-format client sees it against a simulated agent.
 *
 * Host-independent by construction: the client format is supplied by the test, not read from the
 * machine, so this pins the behavior identically on a Linux CI runner and a macOS dev box.
 */
class SimulatedAgentHandleFormatTest {

    private val profileId = "sim-hrm-1"

    @Test
    fun simulatedBackendDeclaresStringHandles() {
        // Not the host radio's format: these ids come from the profile on every platform.
        assertEquals(IdentifierFormat.STRING, SimulatedBleBackend(SimulationProfile.decode(PROFILE)).handleFormat)
    }

    @Test
    fun simulatedAgentTranslatesHandlesForAUuidClient() = runTest {
        val h = Harness(backgroundScope, SimulatedBleBackend(SimulationProfile.decode(PROFILE), backgroundScope))
        h.hello(IdentifierFormat.UUID)

        h.send(1, Op.ScanStart(scanId = 7))
        assertIs<OpResult.Ok>(h.reply(1))

        val clientHandle = h.firstScanResult(7).advertisement.device.value
        assertNotEquals(profileId, clientHandle, "a UUID client must not receive the raw profile id")
        assertTrue(
            clientHandle.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")),
            "expected a parseable UUID handle, got '$clientHandle'",
        )

        // And it still routes: the reverse map takes the translated handle back to the profile id.
        h.send(2, Op.Connect(DeviceHandle(clientHandle)))
        assertEquals(OpResult.Ok(), h.reply(2))
    }

    @Test
    fun simulatedAgentLeavesHandlesReadableForAStringClient() = runTest {
        // Android (and any STRING-format client) holds the profile id as-is, so a readable handle
        // survives where it can — translation engages only where the client can't hold it.
        val h = Harness(backgroundScope, SimulatedBleBackend(SimulationProfile.decode(PROFILE), backgroundScope))
        h.hello(IdentifierFormat.STRING)

        h.send(1, Op.ScanStart(scanId = 7))
        assertIs<OpResult.Ok>(h.reply(1))
        assertEquals(profileId, h.firstScanResult(7).advertisement.device.value)
    }

    /**
     * Drives the *production* [BleAgentBackend] wiring (not [BleAgent] directly) so the agent
     * format really is the one that reaches the wire — the plumbing this regression is about.
     */
    private class Harness(scope: CoroutineScope, backend: BleBackend) {
        private val codec = CborProtocolCodec()
        private val toAgent = Channel<ByteArray>(Channel.UNLIMITED)
        private val fromAgent = Channel<ByteArray>(Channel.UNLIMITED)
        private val frames = MutableSharedFlow<Frame>(replay = 128, extraBufferCapacity = 128)

        init {
            scope.launch { fromAgent.receiveAsFlow().collect { frames.emit(codec.decode(it)) } }
            BleAgentBackend(backend = backend, lifecycleScope = scope).serve(
                incoming = toAgent.receiveAsFlow(),
                outgoing = { fromAgent.send(it) },
                scope = scope,
                connectionId = 0L,
                clientKey = "test-client",
                operatorScope = false,
            )
        }

        fun hello(format: IdentifierFormat) {
            toAgent.trySend(
                codec.encode(
                    ClientHello(capabilities = setOf(Capabilities.IDENTIFIER_TRANSLATION), identifierFormat = format),
                ),
            )
        }

        fun send(cid: Long, op: Op) {
            toAgent.trySend(codec.encode(Command(cid, op)))
        }

        suspend fun reply(cid: Long): OpResult =
            frames.filterIsInstance<Reply>().first { it.cid == cid }.result

        suspend fun firstScanResult(scanId: Long): AgentEvent.ScanResult =
            frames.filterIsInstance<Event>().map { it.event }
                .filterIsInstance<AgentEvent.ScanResult>()
                .first { it.scanId == scanId }
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
