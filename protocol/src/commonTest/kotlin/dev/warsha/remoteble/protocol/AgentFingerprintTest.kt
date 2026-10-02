package dev.warsha.remoteble.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentFingerprintTest {
    private val digest = ByteArray(32) { it.toByte() }
    private val text = "sha256:000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"

    @Test
    fun printsAsLowercaseHexWithThePrefix() {
        assertEquals(text, AgentFingerprint.ofSpkiSha256(digest).toString())
    }

    @Test
    fun parsesWhatItPrintsAndUpperCasePastes() {
        assertEquals(AgentFingerprint.ofSpkiSha256(digest), AgentFingerprint.parse(text))
        assertEquals(AgentFingerprint.ofSpkiSha256(digest), AgentFingerprint.parse("  " + text.uppercase() + "\n"))
    }

    @Test
    fun matchesOnlyItsOwnDigest() {
        val fingerprint = AgentFingerprint.parse(text)
        assertTrue(fingerprint.matches(digest))
        assertFalse(fingerprint.matches(digest.copyOf().also { it[31] = 0 }))
    }

    @Test
    fun rejectsMalformedInput() {
        assertFailsWith<IllegalArgumentException> { AgentFingerprint.parse(text.removePrefix("sha256:")) }
        assertFailsWith<IllegalArgumentException> { AgentFingerprint.parse(text.dropLast(2)) }
        assertFailsWith<IllegalArgumentException> { AgentFingerprint.parse(text.dropLast(1) + "g") }
        // Digits of other scripts and fullwidth letters are not hex here, though Kotlin's digit
        // parsing would take them: Arabic-Indic one, fullwidth capital A.
        assertFailsWith<IllegalArgumentException> { AgentFingerprint.parse(text.dropLast(1) + "\u0661") }
        assertFailsWith<IllegalArgumentException> { AgentFingerprint.parse(text.dropLast(1) + "\uFF21") }
        assertFailsWith<IllegalArgumentException> { AgentFingerprint.ofSpkiSha256(ByteArray(31)) }
        assertNull(AgentFingerprint.parseOrNull("sha1:00"))
    }

    @Test
    fun theDigestCannotBeMutatedThroughTheAccessor() {
        val fingerprint = AgentFingerprint.parse(text)
        fingerprint.bytes[0] = 99
        assertEquals(text, fingerprint.toString())
    }
}
