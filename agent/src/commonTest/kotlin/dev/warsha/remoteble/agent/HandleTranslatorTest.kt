package dev.warsha.remoteble.agent

import dev.warsha.remoteble.protocol.AdvertisementDto
import dev.warsha.remoteble.protocol.AgentEvent
import dev.warsha.remoteble.protocol.DeviceHandle
import dev.warsha.remoteble.protocol.IdentifierFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class HandleTranslatorTest {

    private val real = "some-native-radio-handle-1234"

    private fun translator(
        client: IdentifierFormat?,
        agent: IdentifierFormat = IdentifierFormat.BLUEZ_JSON,
        negotiated: Boolean = true,
        strict: Boolean = false,
    ) = HandleTranslator(
        clientFormat = client,
        agentFormat = agent,
        capabilityNegotiated = negotiated,
        strict = { strict },
    )

    private fun scanResult(handle: String) =
        AgentEvent.ScanResult(scanId = 1, advertisement = AdvertisementDto(device = DeviceHandle(handle), rssi = -50))

    private fun deviceOf(event: AgentEvent): String =
        (event as AgentEvent.ScanResult).advertisement.device.value

    // ---- needsRewrite ----

    @Test
    fun needsRewrite_identityCases() {
        // Android holds any string; a same-format client and the stubbed bluez format never rewrite.
        assertFalse(HandleTranslator.needsRewrite(IdentifierFormat.STRING, IdentifierFormat.UUID))
        assertFalse(HandleTranslator.needsRewrite(IdentifierFormat.BLUEZ_JSON, IdentifierFormat.UUID))
        assertFalse(HandleTranslator.needsRewrite(IdentifierFormat.UUID, IdentifierFormat.UUID))
    }

    @Test
    fun needsRewrite_crossPlatform() {
        assertTrue(HandleTranslator.needsRewrite(IdentifierFormat.UUID, IdentifierFormat.BLUEZ_JSON))
        assertTrue(HandleTranslator.needsRewrite(IdentifierFormat.MAC_ADDRESS, IdentifierFormat.UUID))
    }

    // ---- synthesize ----

    @Test
    fun synthesize_uuidIsWellFormedAndDeterministic() {
        val a = HandleTranslator.synthesize(IdentifierFormat.UUID, real)
        val b = HandleTranslator.synthesize(IdentifierFormat.UUID, real)
        assertEquals(a, b, "synthesis must be deterministic")
        // 8-4-4-4-12 hex with RFC-4122 version 5 / variant bits.
        val re = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-5[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
        assertTrue(re.matches(a), "not a v5 UUID: $a")
        assertNotEquals(HandleTranslator.synthesize(IdentifierFormat.UUID, "other"), a)
    }

    @Test
    fun synthesize_macIsWellFormed() {
        val mac = HandleTranslator.synthesize(IdentifierFormat.MAC_ADDRESS, real)
        assertTrue(Regex("^([0-9A-F]{2}:){5}[0-9A-F]{2}$").matches(mac), "not a MAC: $mac")
        // Locally-administered bit set, multicast bit clear on the first octet.
        val first = mac.substringBefore(':').toInt(16)
        assertEquals(0x02, first and 0x02)
        assertEquals(0x00, first and 0x01)
    }

    @Test
    fun synthesize_identityFormatsPassThrough() {
        assertEquals(real, HandleTranslator.synthesize(IdentifierFormat.STRING, real))
        assertEquals(real, HandleTranslator.synthesize(IdentifierFormat.BLUEZ_JSON, real))
    }

    // ---- forward + reverse round-trip ----

    @Test
    fun activeTranslator_rewritesOutgoingAndReverseMaps() = runTest {
        val t = translator(client = IdentifierFormat.UUID)
        val emitted = deviceOf(t.outgoing(scanResult(real)))
        assertNotEquals(real, emitted, "handle should be translated to the client's format")
        assertTrue(emitted.contains("-"), "expected a UUID-shaped handle: $emitted")
        // An op keyed on the client handle resolves back to the real radio handle.
        assertEquals(real, t.toReal(emitted))
        // An unmapped handle passes through unchanged.
        assertEquals("unknown", t.toReal("unknown"))
    }

    @Test
    fun inactiveWhenCapabilityNotNegotiated() = runTest {
        val t = translator(client = IdentifierFormat.UUID, negotiated = false)
        assertEquals(real, deviceOf(t.outgoing(scanResult(real))))
        assertEquals(real, t.toReal(real))
    }

    @Test
    fun strictModePassesThrough() = runTest {
        val t = translator(client = IdentifierFormat.UUID, strict = true)
        assertEquals(real, deviceOf(t.outgoing(scanResult(real))))
    }

    @Test
    fun androidClientNeverRewrites() = runTest {
        val t = translator(client = IdentifierFormat.STRING, agent = IdentifierFormat.UUID)
        assertEquals(real, deviceOf(t.outgoing(scanResult(real))))
    }

    @Test
    fun evictDropsReverseMapping() = runTest {
        val t = translator(client = IdentifierFormat.UUID)
        val emitted = deviceOf(t.outgoing(scanResult(real)))
        assertEquals(real, t.toReal(emitted))
        t.evict(real)
        assertEquals(emitted, t.toReal(emitted), "evicted mapping should fall back to identity")
    }
}
