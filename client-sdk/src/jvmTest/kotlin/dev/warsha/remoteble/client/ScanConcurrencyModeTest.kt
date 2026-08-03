package dev.warsha.remoteble.client

import dev.warsha.remoteble.protocol.Capabilities
import dev.warsha.remoteble.protocol.CborProtocolCodec
import dev.warsha.remoteble.protocol.ClientHello
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

class ScanConcurrencyModeTest {

    @Test
    fun aManuallyConstructedSessionStillOffersEveryScanConcurrencyCapability() = runTest {
        // The documented construction path (getting-started.md, flows.md, client-sdk.md) passes no
        // clientCapabilities. It must still negotiate the scan-concurrency trio: a client that
        // offers none reads a new agent as LEGACY_OR_UNKNOWN and is downgraded to AGENT_BUSY
        // instead of SCAN_UNAVAILABLE, which is exactly the host-dependence this feature removes.
        val link = InMemoryTransport()
        val codec = CborProtocolCodec()
        val session = DefaultAgentSession(link.client, codec, backgroundScope)

        val frame = assertIs<ClientHello>(codec.decode(link.agentIncoming.first()))
        assertTrue(Capabilities.SCAN_CONCURRENCY_MULTIPLEXED in frame.capabilities)
        assertTrue(Capabilities.SCAN_CONCURRENCY_SINGLE in frame.capabilities)
        assertTrue(Capabilities.SCAN_CONCURRENCY_UNCONTROLLED in frame.capabilities)
        // The pre-existing automatic capability must not have been displaced.
        assertTrue(Capabilities.IDENTIFIER_TRANSLATION in frame.capabilities)
        session.close()
    }

    @Test
    fun resolvesExactlyOneAdvertisedMode() {
        assertEquals(
            ScanConcurrencyMode.MULTIPLEXED,
            ScanConcurrencyMode.fromCapabilities(setOf(Capabilities.SCAN_CONCURRENCY_MULTIPLEXED)),
        )
        assertEquals(
            ScanConcurrencyMode.SINGLE,
            ScanConcurrencyMode.fromCapabilities(setOf(Capabilities.SCAN_CONCURRENCY_SINGLE)),
        )
        assertEquals(
            ScanConcurrencyMode.UNCONTROLLED,
            ScanConcurrencyMode.fromCapabilities(setOf(Capabilities.SCAN_CONCURRENCY_UNCONTROLLED)),
        )
    }

    @Test
    fun missingOrContradictoryCapabilitiesAreLegacyOrUnknown() {
        assertEquals(ScanConcurrencyMode.LEGACY_OR_UNKNOWN, ScanConcurrencyMode.fromCapabilities(null))
        assertEquals(
            ScanConcurrencyMode.LEGACY_OR_UNKNOWN,
            ScanConcurrencyMode.fromCapabilities(
                setOf(
                    Capabilities.SCAN_CONCURRENCY_MULTIPLEXED,
                    Capabilities.SCAN_CONCURRENCY_SINGLE,
                ),
            ),
        )
    }
}
