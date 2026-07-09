package dev.warsha.remoteble.agent

import dev.warsha.remoteble.protocol.Capabilities
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * JVM-specific guard for the platform-conditional `rssi` capability. Kable's JVM/btleplug backend
 * has no connected-RSSI read (its `rssi()` returns cached advertisement RSSI, not a live value), so
 * the agent must NOT advertise the `rssi` capability here — otherwise a client would issue
 * `Op.ReadRssi` and get a stale/garbage number. See RssiSupport.jvm.kt (`agentRssiSupported()=false`).
 *
 * Constructing [EngineBleBackend] touches no radio (its `capabilities` set is computed eagerly, the
 * peripheral map stays empty), so this is safe to run headless.
 */
class EngineBleBackendJvmTest {

    @Test
    fun jvmDoesNotAdvertiseRssiCapability() {
        assertFalse(
            Capabilities.RSSI in EngineBleBackend().capabilities,
            "JVM/btleplug backend must not advertise the rssi capability (no connected read)",
        )
    }

    @Test
    fun jvmStillAdvertisesDescriptors() {
        // Sanity: the platform-gating didn't accidentally drop the always-on descriptors capability.
        assertTrue(Capabilities.DESCRIPTORS in EngineBleBackend().capabilities)
    }
}
