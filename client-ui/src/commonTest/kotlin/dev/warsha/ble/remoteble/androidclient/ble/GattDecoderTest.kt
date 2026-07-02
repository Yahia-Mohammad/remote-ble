package dev.warsha.ble.remoteble.androidclient.ble

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GattDecoderTest {

    @Test
    fun `heart rate decodes UINT8 format`() {
        // flags bit0 = 0 → UINT8 value in byte 1
        assertEquals(72, GattDecoder.decodeHeartRate(byteArrayOf(0x00, 72)))
    }

    @Test
    fun `heart rate decodes little-endian UINT16 format`() {
        // flags bit0 = 1 → UINT16 (LE) in bytes 1..2: 0x0140 = 320
        assertEquals(320, GattDecoder.decodeHeartRate(byteArrayOf(0x01, 0x40, 0x01)))
    }

    @Test
    fun `heart rate handles values above signed-byte range`() {
        assertEquals(200, GattDecoder.decodeHeartRate(byteArrayOf(0x00, 200.toByte())))
    }

    @Test
    fun `heart rate returns zero on truncated buffers`() {
        assertEquals(0, GattDecoder.decodeHeartRate(byteArrayOf()))
        assertEquals(0, GattDecoder.decodeHeartRate(byteArrayOf(0x00)))
        assertEquals(0, GattDecoder.decodeHeartRate(byteArrayOf(0x01, 0x40)))
    }

    @Test
    fun `battery level clamps and reads unsigned`() {
        assertEquals(88, GattDecoder.decodeBatteryLevel(byteArrayOf(88)))
        assertEquals(100, GattDecoder.decodeBatteryLevel(byteArrayOf(200.toByte())))
        assertNull(GattDecoder.decodeBatteryLevel(byteArrayOf()))
    }

    @Test
    fun `body sensor location maps known and unknown codes`() {
        assertEquals("Wrist", GattDecoder.decodeBodySensorLocation(byteArrayOf(2)))
        assertEquals("Unknown", GattDecoder.decodeBodySensorLocation(byteArrayOf(99)))
        assertEquals("Unknown", GattDecoder.decodeBodySensorLocation(byteArrayOf()))
    }

    @Test
    fun `utf8 string trims trailing nulls and whitespace`() {
        assertEquals("ACME", GattDecoder.decodeUtf8String("ACME ".encodeToByteArray()))
    }
}
