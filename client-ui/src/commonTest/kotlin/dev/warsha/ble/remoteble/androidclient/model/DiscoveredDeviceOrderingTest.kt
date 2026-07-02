package dev.warsha.ble.remoteble.androidclient.model

import dev.warsha.ble.remoteble.protocol.DeviceHandle
import kotlin.test.Test
import kotlin.test.assertEquals

class DiscoveredDeviceOrderingTest {

    private fun device(id: String, name: String?) =
        DiscoveredDevice(identifier = id, handle = DeviceHandle(id), name = name, rssi = -50)

    @Test
    fun `named devices sort before unnamed, each group in first-seen order`() {
        val devices = listOf(
            device("1", name = null),
            device("2", name = "Pixel 8"),
            device("3", name = null),
            device("4", name = "Galaxy S24"),
        )

        val sorted = devices.sortedNamedFirst(hideUnnamed = false)

        assertEquals(listOf("2", "4", "1", "3"), sorted.map { it.identifier })
    }

    @Test
    fun `blank name is treated as unnamed`() {
        val devices = listOf(device("1", name = ""), device("2", name = "Named"))

        assertEquals(listOf("2", "1"), devices.sortedNamedFirst(hideUnnamed = false).map { it.identifier })
    }

    @Test
    fun `hideUnnamed drops unnamed devices instead of trailing them`() {
        val devices = listOf(device("1", name = null), device("2", name = "Pixel 8"))

        assertEquals(listOf("2"), devices.sortedNamedFirst(hideUnnamed = true).map { it.identifier })
    }
}
