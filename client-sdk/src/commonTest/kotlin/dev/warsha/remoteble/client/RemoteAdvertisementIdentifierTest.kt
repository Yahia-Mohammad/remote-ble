package dev.warsha.remoteble.client

import dev.warsha.remoteble.protocol.AdvertisementDto
import dev.warsha.remoteble.protocol.DeviceHandle
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Regression guard for the agent's UUID-style device handles. Kable's Android
 * `toIdentifier()` validates MAC format and threw "MAC Address has invalid format" for
 * a CoreBluetooth UUID, crashing Android clients the moment they read `identifier`.
 * Remote-mode identity must derive from the handle without that validation.
 *
 * Runs on the JVM here (Android host tests aren't enabled in this module); the Android
 * and Apple actuals of [deviceHandleToIdentifier] are exercised when those targets build.
 */
class RemoteAdvertisementIdentifierTest {

    private val uuidHandle = "71B7A414-9541-EE17-0891-8D254512935A"

    @Test
    fun advertisement_identifier_does_not_throw_for_uuid_handle() {
        val adv = RemoteAdvertisement(
            AdvertisementDto(device = DeviceHandle(uuidHandle), name = "RBTestPeripheral", rssi = -50),
        )
        // Must not throw, and must round-trip the handle (case-insensitive: Apple's Uuid
        // canonicalises to lowercase).
        assertEquals(uuidHandle.uppercase(), adv.identifier.toString().uppercase())
        assertEquals(uuidHandle, adv.handle.value)
    }

    @Test
    fun deviceHandleToIdentifier_round_trips_the_handle() {
        assertEquals(uuidHandle.uppercase(), deviceHandleToIdentifier(uuidHandle).toString().uppercase())
    }
}
