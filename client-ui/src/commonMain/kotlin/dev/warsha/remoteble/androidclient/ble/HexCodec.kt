package dev.warsha.remoteble.androidclient.ble

/**
 * Conversions between the raw bytes of a characteristic and the human-facing text the
 * GATT explorer reads and writes. Pure and Android-free so the parsing rules are
 * unit-testable (see `HexCodecTest`).
 */
object HexCodec {

    /**
     * Parses user input into bytes to write. A `0x`-prefixed string is read as hex
     * (whitespace ignored, odd trailing nibbles dropped); anything else is sent as UTF-8.
     * Malformed hex falls back to UTF-8 so a stray keystroke never throws into the UI.
     */
    fun parseInput(input: String): ByteArray {
        val trimmed = input.trim()
        if (!trimmed.startsWith("0x", ignoreCase = true)) {
            return trimmed.encodeToByteArray()
        }
        val hex = trimmed.substring(2).filterNot { it.isWhitespace() }
        return runCatching {
            hex.chunked(2)
                .filter { it.length == 2 }
                .map { it.toInt(16).toByte() }
                .toByteArray()
        }.getOrElse { trimmed.encodeToByteArray() }
    }

    /** Renders a characteristic value as both hex and printable ASCII for display. */
    fun describe(bytes: ByteArray?): String {
        if (bytes == null || bytes.isEmpty()) return "No value read yet."
        val hex = bytes.joinToString(" ") { (it.toInt() and 0xFF).toString(16).uppercase().padStart(2, '0') }
        val ascii = bytes.map { if (it in PRINTABLE) it.toInt().toChar() else '.' }.joinToString("")
        return "Hex   0x$hex\nASCII \"$ascii\""
    }

    // Printable ASCII range (space through tilde).
    private val PRINTABLE = 32.toByte()..126.toByte()
}
