package dev.warsha.remoteble.androidclient.model

import dev.warsha.remoteble.protocol.DeviceHandle
import kotlin.test.Test
import kotlin.test.assertEquals

class DiscoveredDeviceTest {

    private fun sighting(name: String?, rssi: Int, handle: String = "h") =
        DiscoveredDevice(identifier = "id", handle = DeviceHandle(handle), name = name, rssi = rssi)

    @Test
    fun `retains last-known name when a later sighting has none`() {
        // The core fix: a bare RSSI-only update must not blank out a name we already learned.
        val merged = sighting("Pixel 8", -50).mergedWith(sighting(name = null, rssi = -55))
        assertEquals("Pixel 8", merged.name)
        assertEquals(-55, merged.rssi)
    }

    @Test
    fun `adopts a name as soon as one arrives`() {
        val merged = sighting(name = null, rssi = -60).mergedWith(sighting("Pixel 8", -58))
        assertEquals("Pixel 8", merged.name)
    }

    @Test
    fun `retains last-known rssi when a later sighting carries none`() {
        val merged = sighting("X", -50).mergedWith(sighting("X", rssi = Int.MIN_VALUE))
        assertEquals(-50, merged.rssi)
    }

    @Test
    fun `updates rssi and handle from the newer sighting`() {
        val merged = sighting("X", -50, handle = "old").mergedWith(sighting("X", -70, handle = "new"))
        assertEquals(-70, merged.rssi)
        assertEquals(DeviceHandle("new"), merged.handle)
    }
}
