package dev.warsha.remoteble.androidclient.ble

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HexCodecTest {

    @Test
    fun `parses 0x-prefixed hex`() {
        assertTrue(byteArrayOf(0xDE.toByte(), 0xAD.toByte()).contentEquals(HexCodec.parseInput("0xDEAD")))
    }

    @Test
    fun `parses hex case-insensitively and ignores whitespace`() {
        assertTrue(byteArrayOf(0x0A, 0xBC.toByte()).contentEquals(HexCodec.parseInput("0x0a bc")))
    }

    @Test
    fun `drops a dangling nibble rather than throwing`() {
        // "0xABC" → "AB" kept, trailing "C" dropped
        assertTrue(byteArrayOf(0xAB.toByte()).contentEquals(HexCodec.parseInput("0xABC")))
    }

    @Test
    fun `treats non-hex input as UTF-8`() {
        assertTrue("Hi".encodeToByteArray().contentEquals(HexCodec.parseInput("Hi")))
    }

    @Test
    fun `malformed hex falls back to UTF-8 of the original`() {
        val parsed = HexCodec.parseInput("0xZZ")
        assertTrue("0xZZ".encodeToByteArray().contentEquals(parsed))
    }

    @Test
    fun `describe renders hex and ascii`() {
        val described = HexCodec.describe(byteArrayOf(0x41, 0x42))
        assertTrue(described.contains("0x41 42"))
        assertTrue(described.contains("\"AB\""))
    }

    @Test
    fun `describe handles empty and null`() {
        assertEquals("No value read yet.", HexCodec.describe(null))
        assertEquals("No value read yet.", HexCodec.describe(byteArrayOf()))
    }
}
