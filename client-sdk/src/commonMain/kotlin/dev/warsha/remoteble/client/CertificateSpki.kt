package dev.warsha.remoteble.client

/**
 * The `SubjectPublicKeyInfo` of a DER X.509 certificate, as its complete DER element: the bytes an
 * [dev.warsha.remoteble.protocol.AgentFingerprint] hashes. `null` if [der] is not a certificate this
 * walk can read.
 *
 * For platforms with no API that hands over a certificate's SPKI: Apple's `SecCertificateCopyKey`
 * exports only the key itself, so the pin would depend on rebuilding the SPKI per key type. Walking
 * to the element instead hashes exactly what every other client hashes.
 */
internal fun certificateSpki(der: ByteArray): ByteArray? {
    val certificate = DerReader(der).element(SEQUENCE) ?: return null
    val tbs = DerReader(certificate).element(SEQUENCE) ?: return null
    val fields = DerReader(tbs)
    // tbsCertificate: [0] version (optional), serialNumber, signature, issuer, validity, subject,
    // subjectPublicKeyInfo.
    if (fields.peek() == VERSION_TAG) fields.skip() ?: return null
    repeat(5) { fields.skip() ?: return null }
    return fields.rawElement(SEQUENCE)
}

private const val SEQUENCE = 0x30
private const val VERSION_TAG = 0xA0

/** A bounds-checked walk over consecutive DER elements, with definite lengths only. */
private class DerReader(private val bytes: ByteArray) {
    private var at = 0

    fun peek(): Int? = if (at < bytes.size) bytes[at].toInt() and 0xFF else null

    /** The content of the next element if it has [tag]; advances past it. */
    fun element(tag: Int): ByteArray? = next(tag)?.let { (_, contentStart, end) -> bytes.copyOfRange(contentStart, end).also { at = end } }

    /** The next element, tag and length included, if it has [tag]; advances past it. */
    fun rawElement(tag: Int): ByteArray? = next(tag)?.let { (start, _, end) -> bytes.copyOfRange(start, end).also { at = end } }

    fun skip(): Unit? = next(null)?.let { (_, _, end) -> at = end }

    private fun next(tag: Int?): Triple<Int, Int, Int>? {
        val start = at
        if (start + 2 > bytes.size) return null
        if (tag != null && (bytes[start].toInt() and 0xFF) != tag) return null
        var pos = start + 1
        val first = bytes[pos++].toInt() and 0xFF
        val length = if (first < 0x80) {
            first
        } else {
            val count = first and 0x7F
            if (count == 0 || count > 4 || pos + count > bytes.size) return null
            var value = 0L
            repeat(count) { value = (value shl 8) or (bytes[pos++].toLong() and 0xFF) }
            if (value > Int.MAX_VALUE) return null
            value.toInt()
        }
        // In Long, so a length near Int.MAX_VALUE cannot wrap past the bounds check.
        if (pos.toLong() + length > bytes.size) return null
        return Triple(start, pos, pos + length)
    }
}
