package dev.warsha.remoteble.client

import dev.warsha.remoteble.protocol.Capabilities
import kotlin.test.Test
import kotlin.test.assertEquals

class ScanConcurrencyModeTest {

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
