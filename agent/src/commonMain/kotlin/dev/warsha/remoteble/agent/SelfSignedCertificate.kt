package dev.warsha.remoteble.agent

import dev.warsha.remoteble.protocol.AGENT_TLS_SERVER_NAME

/**
 * Builds the agent's self-signed X.509 v3 certificate as DER.
 *
 * Written here rather than borrowed because neither platform that needs it offers one: the JDK has
 * no public certificate-creation API (`keytool` uses internal classes), and iOS has none at all. The
 * certificate only has to carry the key, since clients pin the SPKI and ignore everything else
 * (`docs/proposals/agent-transport-encryption.md`). The extensions are the ones strict TLS stacks
 * insist on regardless of how trust is decided: a DNS name to verify ([AGENT_TLS_SERVER_NAME]),
 * an end-entity marker, and a server-auth usage.
 *
 * The key is ECDSA P-256 and the signature ECDSA with SHA-256; [sign] must return a DER
 * `ECDSA-Sig-Value`, which is what both the JDK's `SHA256withECDSA` and Apple's
 * `ecdsaSignatureMessageX962SHA256` produce.
 */
internal object SelfSignedCertificate {

    /** No well-defined expiry (RFC 5280 §4.1.2.5): clients pin the key and ignore validity. */
    private const val NO_EXPIRY = "99991231235959Z"

    fun build(
        spki: ByteArray,
        serial: ByteArray,
        notBeforeEpochSeconds: Long,
        sign: (tbs: ByteArray) -> ByteArray,
        commonName: String = "RemoteBLE Agent",
    ): ByteArray {
        val algorithm = Der.seq(Der.oid(ECDSA_WITH_SHA256))
        val name = Der.seq(Der.set(Der.seq(Der.oid(COMMON_NAME), Der.utf8(commonName))))
        val tbs = Der.seq(
            Der.explicit(0, Der.integer(byteArrayOf(2))),
            Der.integer(serial),
            algorithm,
            name,
            Der.seq(Der.time(notBeforeEpochSeconds), Der.generalizedTime(NO_EXPIRY)),
            name,
            spki,
            Der.explicit(
                3,
                Der.seq(
                    extension(SUBJECT_ALT_NAME, critical = false, Der.seq(Der.tagged(DNS_NAME_TAG, AGENT_TLS_SERVER_NAME.encodeToByteArray()))),
                    extension(BASIC_CONSTRAINTS, critical = true, Der.seq()),
                    // digitalSignature only: an ECDHE key exchange is authenticated by a signature.
                    extension(KEY_USAGE, critical = true, Der.bitString(byteArrayOf(0x80.toByte()), unusedBits = 7)),
                    extension(EXTENDED_KEY_USAGE, critical = false, Der.seq(Der.oid(SERVER_AUTH))),
                ),
            ),
        )
        return Der.seq(tbs, algorithm, Der.bitString(sign(tbs)))
    }

    private fun extension(oid: String, critical: Boolean, value: ByteArray): ByteArray =
        if (critical) {
            Der.seq(Der.oid(oid), Der.TRUE, Der.octetString(value))
        } else {
            Der.seq(Der.oid(oid), Der.octetString(value))
        }

    private const val ECDSA_WITH_SHA256 = "1.2.840.10045.4.3.2"
    private const val COMMON_NAME = "2.5.4.3"
    private const val SUBJECT_ALT_NAME = "2.5.29.17"
    private const val BASIC_CONSTRAINTS = "2.5.29.19"
    private const val KEY_USAGE = "2.5.29.15"
    private const val EXTENDED_KEY_USAGE = "2.5.29.37"
    private const val SERVER_AUTH = "1.3.6.1.5.5.7.3.1"

    /** `GeneralName.dNSName`: context-specific, primitive, tag 2 (an implicit IA5String). */
    private const val DNS_NAME_TAG = 0x82
}

/** The few DER encodings a certificate needs. Lengths use the definite form throughout. */
internal object Der {
    val TRUE: ByteArray = byteArrayOf(0x01, 0x01, 0xFF.toByte())

    fun seq(vararg parts: ByteArray): ByteArray = tlv(0x30, concat(parts))
    fun set(vararg parts: ByteArray): ByteArray = tlv(0x31, concat(parts))
    fun explicit(tag: Int, content: ByteArray): ByteArray = tlv(0xA0 or tag, content)
    fun tagged(tag: Int, content: ByteArray): ByteArray = tlv(tag, content)
    fun octetString(content: ByteArray): ByteArray = tlv(0x04, content)
    fun utf8(text: String): ByteArray = tlv(0x0C, text.encodeToByteArray())

    fun bitString(content: ByteArray, unusedBits: Int = 0): ByteArray =
        tlv(0x03, byteArrayOf(unusedBits.toByte()) + content)

    /** A non-negative INTEGER from big-endian magnitude bytes, minimally encoded. */
    fun integer(magnitude: ByteArray): ByteArray {
        var start = 0
        while (start < magnitude.size - 1 && magnitude[start] == 0.toByte()) start++
        val trimmed = if (magnitude.isEmpty()) byteArrayOf(0) else magnitude.copyOfRange(start, magnitude.size)
        // A set top bit would read as negative; a leading zero keeps the value positive.
        val content = if (trimmed[0].toInt() and 0x80 != 0) byteArrayOf(0) + trimmed else trimmed
        return tlv(0x02, content)
    }

    fun oid(dotted: String): ByteArray {
        val arcs = dotted.split('.').map { it.toLong() }
        val body = mutableListOf<Byte>()
        body += (arcs[0] * 40 + arcs[1]).toByte()
        for (arc in arcs.drop(2)) {
            val groups = mutableListOf<Int>()
            var value = arc
            do {
                groups += (value and 0x7F).toInt()
                value = value shr 7
            } while (value > 0)
            groups.reversed().forEachIndexed { index, group ->
                body += (if (index < groups.size - 1) group or 0x80 else group).toByte()
            }
        }
        return tlv(0x06, body.toByteArray())
    }

    /** RFC 5280 §4.1.2.5: UTCTime through 2049, GeneralizedTime from 2050. */
    fun time(epochSeconds: Long): ByteArray {
        val (year, month, day) = civilFromDays(floorDiv(epochSeconds, SECONDS_PER_DAY))
        val secondOfDay = epochSeconds - floorDiv(epochSeconds, SECONDS_PER_DAY) * SECONDS_PER_DAY
        val clock = pad2(secondOfDay / 3600) + pad2(secondOfDay / 60 % 60) + pad2(secondOfDay % 60) + "Z"
        val date = pad2(month.toLong()) + pad2(day.toLong())
        return if (year in 1950..2049) {
            tlv(0x17, (pad2((year % 100).toLong()) + date + clock).encodeToByteArray())
        } else {
            generalizedTime(year.toString().padStart(4, '0') + date + clock)
        }
    }

    fun generalizedTime(text: String): ByteArray = tlv(0x18, text.encodeToByteArray())

    private fun tlv(tag: Int, content: ByteArray): ByteArray = byteArrayOf(tag.toByte()) + length(content.size) + content

    private fun length(size: Int): ByteArray {
        if (size < 0x80) return byteArrayOf(size.toByte())
        val bytes = mutableListOf<Byte>()
        var remaining = size
        while (remaining > 0) {
            bytes.add(0, (remaining and 0xFF).toByte())
            remaining = remaining shr 8
        }
        return byteArrayOf((0x80 or bytes.size).toByte()) + bytes.toByteArray()
    }

    private fun concat(parts: Array<out ByteArray>): ByteArray {
        val out = ByteArray(parts.sumOf { it.size })
        var offset = 0
        for (part in parts) {
            part.copyInto(out, offset)
            offset += part.size
        }
        return out
    }

    private fun pad2(value: Long): String = value.toString().padStart(2, '0')

    private fun floorDiv(a: Long, b: Long): Long = if (a >= 0) a / b else -((-a + b - 1) / b)

    /** Days since 1970-01-01 → (year, month, day), proleptic Gregorian (H. Hinnant's algorithm). */
    private fun civilFromDays(days: Long): Triple<Int, Int, Int> {
        val z = days + 719468
        val era = floorDiv(z, 146097)
        val doe = z - era * 146097
        val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val day = (doy - (153 * mp + 2) / 5 + 1).toInt()
        val month = (if (mp < 10) mp + 3 else mp - 9).toInt()
        val year = (yoe + era * 400 + if (month <= 2) 1 else 0).toInt()
        return Triple(year, month, day)
    }

    private const val SECONDS_PER_DAY = 86_400L
}
