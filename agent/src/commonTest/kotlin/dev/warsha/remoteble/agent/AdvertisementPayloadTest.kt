package dev.warsha.remoteble.agent

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class AdvertisementPayloadTest {

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    @Test
    fun everyServiceDataWidthAndEveryManufacturerEntryIsRead() {
        val record = bytes(
            0x02, 0x01, 0x06, // flags, ignored
            0x06, 0x16, 0xAA, 0xFE, 0x10, 0xF4, 0x00, // 16-bit service data: Eddystone 0xFEAA
            0x06, 0x20, 0x78, 0x56, 0x34, 0x12, 0x01, // 32-bit service data: 0x12345678
            0x12, 0x21, // 128-bit service data, UUID little-endian, then one byte
            0xFB, 0x34, 0x9B, 0x5F, 0x80, 0x00, 0x00, 0x80, 0x00, 0x10, 0x00, 0x00, 0x0D, 0x18, 0x00, 0x00, 0x2A,
            0x05, 0xFF, 0x4C, 0x00, 0x02, 0x15, // Apple
            0x04, 0xFF, 0x06, 0x00, 0x01, // Microsoft
            0x00, 0x00, 0x00, // the zero padding Android leaves at the end
        )

        val payload = assertNotNull(parseAdvertisingRecord(record))

        assertEquals(
            listOf(
                "0000feaa-0000-1000-8000-00805f9b34fb",
                "12345678-0000-1000-8000-00805f9b34fb",
                "0000180d-0000-1000-8000-00805f9b34fb",
            ),
            payload.serviceData.keys.toList(),
        )
        assertContentEquals(bytes(0x10, 0xF4, 0x00), payload.serviceData["0000feaa-0000-1000-8000-00805f9b34fb"])
        assertContentEquals(bytes(0x01), payload.serviceData["12345678-0000-1000-8000-00805f9b34fb"])
        assertContentEquals(bytes(0x2A), payload.serviceData["0000180d-0000-1000-8000-00805f9b34fb"])
        assertEquals(setOf(0x004C, 0x0006), payload.manufacturerData.keys)
        assertContentEquals(bytes(0x02, 0x15), payload.manufacturerData[0x004C])
    }

    @Test
    fun aTruncatedStructureEndsTheParseAndKeepsWhatCameBefore() {
        val record = bytes(0x04, 0x16, 0x0F, 0x18, 0x64, 0x09, 0xFF, 0x4C)
        val payload = assertNotNull(parseAdvertisingRecord(record))
        assertContentEquals(bytes(0x64), payload.serviceData["0000180f-0000-1000-8000-00805f9b34fb"])
        assertEquals(emptyMap(), payload.manufacturerData)
    }

    @Test
    fun aRecordWithNoPayloadReadsAsNone() {
        assertNull(parseAdvertisingRecord(bytes(0x02, 0x01, 0x06, 0x05, 0x09, 0x41, 0x42, 0x43, 0x44)))
        assertNull(parseAdvertisingRecord(byteArrayOf()))
        // Too short to carry the UUID they name.
        assertNull(parseAdvertisingRecord(bytes(0x02, 0x16, 0xAA, 0x02, 0xFF, 0x4C)))
    }
}
