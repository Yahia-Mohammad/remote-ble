package dev.warsha.remoteble.log

public fun bytesPreview(bytes: ByteArray, maxHex: Int = 16): String {
    val len = bytes.size
    val head = bytes.take(maxHex).joinToString("") { byteToHex(it) }
    return "bytes(n=$len, head=$head)"
}

private val HEX_DIGITS = "0123456789abcdef".toCharArray()

private fun byteToHex(b: Byte): String {
    val i = b.toInt() and 0xFF
    return "${HEX_DIGITS[i shr 4]}${HEX_DIGITS[i and 0x0F]}"
}
