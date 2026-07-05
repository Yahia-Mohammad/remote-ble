package dev.warsha.remoteble.client

import dev.warsha.remoteble.protocol.AdvertisementDto
import dev.warsha.remoteble.protocol.DeviceHandle
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The portable, platform-independent identity in remote mode is the opaque agent handle
 * ([DeviceHandle]) exposed as `.handle` — ops key off it, not the Kable `.identifier`. The
 * `.identifier` derived from the handle is platform/host-dependent (see
 * [deviceHandleToIdentifier]) and is covered per-target (e.g. `RemoteIdentifierJvmTest`), so it
 * is deliberately not asserted here where the test runs across hosts.
 */
class RemoteAdvertisementIdentifierTest {

    private val uuidHandle = "71B7A414-9541-EE17-0891-8D254512935A"

    @Test
    fun advertisement_exposes_agent_handle_as_portable_identity() {
        val adv = RemoteAdvertisement(
            AdvertisementDto(device = DeviceHandle(uuidHandle), name = "RBTestPeripheral", rssi = -50),
        )
        assertEquals(uuidHandle, adv.handle.value)
    }
}
