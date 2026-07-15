package dev.warsha.remoteble.agent

import dev.warsha.remoteble.log.Logger
import dev.warsha.remoteble.protocol.AdvertisementDto
import dev.warsha.remoteble.protocol.AgentEvent
import dev.warsha.remoteble.protocol.DeviceHandle
import dev.warsha.remoteble.protocol.IdentifierFormat
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Per-connection translator for the `identifier.translate` capability (0.8.0). It rewrites every
 * outgoing real radio [DeviceHandle] into the client's declared [IdentifierFormat] so the client
 * can construct a native Kable `Identifier`, and reverse-maps incoming ops back to the real handle
 * so they still route to the radio.
 *
 * ### When it actually rewrites
 * Only when the client negotiated the capability, strict mode is off, and the client's format
 * genuinely can't hold the agent's native handle:
 * - [IdentifierFormat.STRING] (Android) holds any string → never rewritten.
 * - a client format equal to the agent's own → the handle already fits → never rewritten.
 * - [IdentifierFormat.BLUEZ_JSON] is registered but **stubbed** (emitting btleplug's internal bluez
 *   JSON would couple us to its serde shape) → passes through, so a Linux-host-JVM client falls back
 *   to `.handle` identity (the 0.7.0 behavior).
 * - otherwise ([IdentifierFormat.UUID] / [IdentifierFormat.MAC_ADDRESS] differing from the agent) →
 *   a deterministic synthesizer mints the client handle.
 *
 * When it doesn't rewrite, every method is an identity pass-through with no allocation or state.
 *
 * ### Strict mode
 * Read live via [strict] (an agent-wide dashboard toggle). When on, forward translation is
 * suppressed (handles pass through untranslated) so a cross-platform mismatch surfaces loudly on the
 * client. The reverse map is always consulted first, so handles minted before a mid-session toggle
 * keep routing.
 *
 * ### Reverse map
 * Synthesis (a hash) isn't invertible, so a `clientHandle → realHandle` table is the one piece of
 * required state. It's bounded ([MAX_ENTRIES], evicting the eldest) so a long scan of a busy area
 * can't grow it without limit; connected peripherals are re-inserted on rediscovery, so eviction of
 * a stale scan entry is harmless.
 */
internal class HandleTranslator(
    private val clientFormat: IdentifierFormat?,
    private val agentFormat: IdentifierFormat,
    private val capabilityNegotiated: Boolean,
    private val strict: () -> Boolean,
) {
    private val lock = Mutex()
    private val reverse = LinkedHashMap<String, String>()

    /** Whether *forward* synthesis is active right now (respects the live strict toggle). */
    private fun translating(): Boolean =
        capabilityNegotiated && !strict() && clientFormat != null && needsRewrite(clientFormat, agentFormat)

    /** Real radio handle → the handle the client sees. Records the reverse mapping for routing. */
    private suspend fun toClient(real: String): String {
        if (!translating()) return real
        val client = synthesize(clientFormat!!, real)
        if (client != real) {
            lock.withLock {
                reverse[client] = real
                if (reverse.size > MAX_ENTRIES) {
                    val eldest = reverse.keys.iterator().next()
                    reverse.remove(eldest)
                }
            }
        }
        return client
    }

    /**
     * Pre-populates the reverse map for [realHandles] by re-running the deterministic synthesis —
     * exactly the mapping [outgoing] would record when an event carries each handle. Called on the
     * handshake with the real handles this client's leases still hold (a reconnect within the
     * transport grace), so an op replayed with a previously-issued translated handle routes again
     * even though this fresh connection has emitted no event for it yet. No-op when not
     * translating (identity/strict/same-format clients replay real handles, which pass through).
     */
    suspend fun prime(realHandles: Collection<String>) {
        for (real in realHandles) toClient(real)
        if (realHandles.isNotEmpty()) Logger.debug(LogTags.ENGINE) { "translator primed ${realHandles.size} handle(s)" }
    }

    /** Client-facing handle → the real radio handle (identity when unmapped/untranslated). */
    suspend fun toReal(client: String): String =
        lock.withLock { reverse[client] } ?: client

    /** Drop the reverse entry for a real handle once its peripheral is fully released. */
    suspend fun evict(real: String) {
        lock.withLock { reverse.values.remove(real) }
    }

    /** Rewrite the real [DeviceHandle] carried in an outgoing event into the client's format. */
    suspend fun outgoing(event: AgentEvent): AgentEvent {
        if (!translating()) return event
        return when (event) {
            is AgentEvent.ScanResult ->
                AgentEvent.ScanResult(event.scanId, translateAd(event.advertisement))
            is AgentEvent.ScanResultBatch ->
                AgentEvent.ScanResultBatch(event.scanId, event.advertisements.map { translateAd(it) })
            is AgentEvent.ConnectionState ->
                event.copy(device = DeviceHandle(toClient(event.device.value)))
            is AgentEvent.BondState ->
                event.copy(device = DeviceHandle(toClient(event.device.value)))
            is AgentEvent.Notification, is AgentEvent.SlotState -> event // no handle to translate
        }
    }

    private suspend fun translateAd(ad: AdvertisementDto): AdvertisementDto =
        AdvertisementDto(
            device = DeviceHandle(toClient(ad.device.value)),
            name = ad.name,
            rssi = ad.rssi,
            serviceUuids = ad.serviceUuids,
            manufacturerData = ad.manufacturerData,
        )

    companion object {
        /** Reverse-map cap. Bounds memory for a client scanning a crowded area; eldest evicted. */
        const val MAX_ENTRIES: Int = 4096

        /**
         * True when a handle minted in [agent] format can't be held as-is by a client declaring
         * [client] format and so must be synthesized. See the class KDoc for the rationale of each
         * identity case.
         */
        fun needsRewrite(client: IdentifierFormat, agent: IdentifierFormat): Boolean = when (client) {
            IdentifierFormat.STRING -> false
            IdentifierFormat.BLUEZ_JSON -> false // stubbed synthesizer → pass through
            else -> client != agent
        }

        /**
         * Deterministically maps a real handle to a valid string of the target [format]. Uses a
         * non-cryptographic digest ([digest128]) — handles are opaque routing tokens, not secrets,
         * and the per-client cardinality is tiny, so collision resistance here is ample.
         */
        fun synthesize(format: IdentifierFormat, real: String): String = when (format) {
            IdentifierFormat.UUID -> toUuidString(digest128(real))
            IdentifierFormat.MAC_ADDRESS -> toMacString(digest128(real))
            // STRING / BLUEZ_JSON never reach here (needsRewrite == false), but stay identity-safe.
            IdentifierFormat.STRING, IdentifierFormat.BLUEZ_JSON -> real
        }

        /**
         * 16 bytes of deterministic digest over [input] via two independently-seeded 64-bit FNV-1a
         * passes. Not cryptographic; chosen for zero dependencies and cross-platform (JVM/Android/
         * iOS) reproducibility within one agent.
         */
        private fun digest128(input: String): ByteArray {
            val bytes = input.encodeToByteArray()
            val hi = fnv1a64(bytes, 0xcbf29ce484222325uL)
            val lo = fnv1a64(bytes, 0x9e3779b97f4a7c15uL)
            val out = ByteArray(16)
            for (i in 0 until 8) out[i] = (hi shr (56 - i * 8)).toByte()
            for (i in 0 until 8) out[i + 8] = (lo shr (56 - i * 8)).toByte()
            return out
        }

        private fun fnv1a64(data: ByteArray, seed: ULong): ULong {
            var h = seed
            for (b in data) {
                h = h xor (b.toULong() and 0xFFuL)
                h *= 0x100000001b3uL
            }
            return h
        }

        /** RFC-4122-shaped UUID (version 5 / variant bits set) from 16 digest bytes. */
        private fun toUuidString(d: ByteArray): String {
            d[6] = ((d[6].toInt() and 0x0F) or 0x50).toByte() // version 5
            d[8] = ((d[8].toInt() and 0x3F) or 0x80).toByte() // RFC-4122 variant
            val hex = d.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
            return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-" +
                "${hex.substring(16, 20)}-${hex.substring(20, 32)}"
        }

        /** Colon-separated MAC from the first 6 digest bytes, marked locally-administered unicast. */
        private fun toMacString(d: ByteArray): String {
            val first = (d[0].toInt() and 0xFE) or 0x02 // clear multicast bit, set locally-administered
            val octets = intArrayOf(first, d[1].toInt(), d[2].toInt(), d[3].toInt(), d[4].toInt(), d[5].toInt())
            return octets.joinToString(":") { (it and 0xFF).toString(16).padStart(2, '0').uppercase() }
        }
    }
}
