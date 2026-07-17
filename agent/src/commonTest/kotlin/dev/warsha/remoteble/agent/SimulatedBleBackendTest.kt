package dev.warsha.remoteble.agent

import dev.warsha.remoteble.protocol.AgentException
import dev.warsha.remoteble.protocol.CharRef
import dev.warsha.remoteble.protocol.DeviceHandle
import dev.warsha.remoteble.protocol.ErrorKind
import dev.warsha.remoteble.protocol.ScanFilter
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class SimulatedBleBackendTest {
    @Test
    fun profileDrivesScanGattReadWriteAndNotifications() = runTest {
        val backend = SimulatedBleBackend(SimulationProfile.decode(HRM_PROFILE))
        val device = DeviceHandle("hrm-1")
        val characteristic = CharRef("180d", "2a39")

        val advertisement = backend.scan(listOf(ScanFilter(name = "Sim HRM", service = "180d"))).first()
        assertEquals(device, advertisement.device)
        assertEquals(-50, advertisement.rssi)

        backend.connect(device)
        val services = backend.discover(device)
        assertEquals("0000180d-0000-1000-8000-00805f9b34fb", services.single().uuid)
        assertEquals(0x1a, services.single().characteristics.single().properties)
        assertContentEquals(byteArrayOf(0x0a, 0x0b), backend.read(device, characteristic))

        backend.write(device, characteristic, byteArrayOf(0x55), withResponse = true)
        assertContentEquals(byteArrayOf(0x55), backend.read(device, characteristic))
        assertEquals(listOf(listOf<Byte>(1), listOf<Byte>(2)), backend.observe(device, characteristic)
            .take(2).toList().map { it.toList() })
        assertEquals(-50, backend.readRssi(device))
    }

    @Test
    fun connectFailureBudgetIsDeterministic() = runTest {
        val profile = SimulationProfile.decode(HRM_PROFILE.replace("\"failFirst\": 0", "\"failFirst\": 1"))
        val backend = SimulatedBleBackend(profile)
        val device = DeviceHandle("hrm-1")

        val failure = assertFailsWith<AgentException> { backend.connect(device) }
        assertEquals(ErrorKind.CONNECTION_FAILED, failure.error.kind)
        backend.connect(device)
        assertTrue(backend.isConnected(device))
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun declaredDropAfterDisconnectsAndEmitsOneNativeDrop() = runTest {
        val profile = SimulationProfile.decode(HRM_PROFILE.replace("\"failFirst\": 0", "\"failFirst\": 0, \"dropAfterMs\": 50"))
        val backend = SimulatedBleBackend(profile, backgroundScope)
        val device = DeviceHandle("hrm-1")
        backend.connect(device)
        val drop = async { backend.connectionDrops().first() }
        runCurrent()

        advanceTimeBy(50)
        assertEquals(device, drop.await().device)
        assertTrue(!backend.isConnected(device))
    }

    @Test
    fun malformedProfilesFailBeforeAnyAgentStarts() {
        val invalid = listOf(
            "{}",
            HRM_PROFILE.replace("\"schemaVersion\": 1", "\"schemaVersion\": 2"),
            HRM_PROFILE.replace("\"id\": \"hrm-1\"", "\"id\": \"bad id with spaces\""),
            HRM_PROFILE.replace("\"static\": \"0a0b\"", "\"static\": \"0g\""),
            HRM_PROFILE.replace("\"properties\": [\"read\", \"write\", \"notify\"]", "\"properties\": [\"read\"]"),
        )

        invalid.forEach { raw -> assertFailsWith<IllegalArgumentException> { SimulationProfile.decode(raw) } }
    }

    private companion object {
        val HRM_PROFILE = """
            {
              "schemaVersion": 1,
              "peripherals": [{
                "id": "hrm-1",
                "advertisement": {
                  "name": "Sim HRM",
                  "serviceUuids": ["180d"],
                  "rssi": -50,
                  "intervalMs": 50
                },
                "connect": { "latencyMs": 0, "failFirst": 0 },
                "services": [{
                  "uuid": "180d",
                  "characteristics": [{
                    "uuid": "2a39",
                    "properties": ["read", "write", "notify"],
                    "read": { "static": "0a0b" },
                    "write": { "accept": true, "storesValue": true },
                    "notify": { "intervalMs": 50, "values": { "sequence": ["01", "02"] } }
                  }]
                }]
              }]
            }
        """.trimIndent()
    }
}
