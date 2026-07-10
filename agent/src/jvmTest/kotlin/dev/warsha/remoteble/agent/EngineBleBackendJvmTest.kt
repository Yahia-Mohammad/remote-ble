package dev.warsha.remoteble.agent

import com.juul.kable.Characteristic
import com.juul.kable.DiscoveredCharacteristic
import com.juul.kable.DiscoveredDescriptor
import com.juul.kable.ExperimentalApi
import dev.warsha.remoteble.protocol.Capabilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * JVM-specific guard for the platform-conditional `rssi` capability. Kable's JVM/btleplug backend
 * has no connected-RSSI read (its `rssi()` returns cached advertisement RSSI, not a live value), so
 * the agent must NOT advertise the `rssi` capability here — otherwise a client would issue
 * `Op.ReadRssi` and get a stale/garbage number. See RssiSupport.jvm.kt (`agentRssiSupported()=false`).
 *
 * Constructing [EngineBleBackend] touches no radio (its `capabilities` set is computed eagerly, the
 * peripheral map stays empty), so this is safe to run headless.
 */
@OptIn(ExperimentalUuidApi::class, ExperimentalApi::class)
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

    @Test
    fun jvmDoesNotAdvertiseConnParamsOrConnPriorityCapability() {
        // btleplug exposes no interval/priority control at all (see ConnParamsSupport.jvm.kt), so
        // neither the new conn.params surface nor its conn.priority alias should be advertised here.
        val capabilities = EngineBleBackend().capabilities
        assertFalse(Capabilities.CONN_PARAMS in capabilities)
        assertFalse(Capabilities.CONN_PRIORITY in capabilities)
    }

    /**
     * Regression test for the design-decisions boundary table's `CharNode.properties` row, which
     * claimed this was populated only on the macOS engine via a `propertiesOf` seam. That seam
     * doesn't exist: `toNode()` reads Kable's `properties.value` directly in commonMain, and
     * Kable's JVM/btleplug `DiscoveredCharacteristic` (verified: `BtleplugCharacteristic` reads the
     * real `btleplug-ffi` `CharacteristicPropertyFlags` bits, not a stub) already carries real
     * property bits — so this asserts the mapping preserves them rather than zeroing them out.
     */
    @Test
    fun toNodePreservesNonZeroPropertyBits() {
        val fakeChar = object : DiscoveredCharacteristic {
            override val serviceUuid: Uuid = Uuid.parse("0000180d-0000-1000-8000-00805f9b34fb")
            override val characteristicUuid: Uuid = Uuid.parse("00002a37-0000-1000-8000-00805f9b34fb")
            override val properties: Characteristic.Properties = Characteristic.Properties(0x12)
            override val descriptors: List<DiscoveredDescriptor> = emptyList()
        }

        val node = with(EngineBleBackend()) { fakeChar.toNode() }

        assertEquals(0x12, node.properties, "property bits must survive the DiscoveredCharacteristic -> CharNode mapping")
    }
}
