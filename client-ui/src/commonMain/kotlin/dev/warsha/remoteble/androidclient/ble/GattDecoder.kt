package dev.warsha.remoteble.androidclient.ble

/**
 * Pure decoders for the well-known GATT characteristic values this demo surfaces.
 *
 * Kept free of Android and Kable types so the bit-twiddling can be unit-tested directly
 * (see `GattDecoderTest`).
 */
object GattDecoder {

    /**
     * Decodes a Heart Rate Measurement value (UUID 2A37).
     *
     * Byte 0 is a flags field whose bit 0 selects the value format:
     * `0` → the rate is a UINT8 in byte 1, `1` → a little-endian UINT16 in bytes 1–2.
     * Returns `0` when the buffer is too short to hold the advertised format.
     */
    fun decodeHeartRate(bytes: ByteArray): Int {
        if (bytes.isEmpty()) return 0
        val isUint16 = (bytes[0].toInt() and 0x01) != 0
        return when {
            isUint16 && bytes.size >= 3 ->
                ((bytes[2].toInt() and 0xFF) shl 8) or (bytes[1].toInt() and 0xFF)
            !isUint16 && bytes.size >= 2 -> bytes[1].toInt() and 0xFF
            else -> 0
        }
    }

    /** Decodes a Body Sensor Location value (UUID 2A38) to its SIG-defined label. */
    fun decodeBodySensorLocation(bytes: ByteArray): String = when (bytes.firstOrNull()?.toInt()) {
        0 -> "Other"
        1 -> "Chest"
        2 -> "Wrist"
        3 -> "Finger"
        4 -> "Hand"
        5 -> "Ankle"
        6 -> "Foot"
        else -> "Unknown"
    }

    /** Decodes a Battery Level percentage (UUID 2A19), clamped to 0–100, or `null` if empty. */
    fun decodeBatteryLevel(bytes: ByteArray): Int? =
        bytes.firstOrNull()?.let { (it.toInt() and 0xFF).coerceIn(0, 100) }

    /** Decodes a UTF-8 text characteristic (e.g. Manufacturer Name, Model Number). */
    fun decodeUtf8String(bytes: ByteArray): String = bytes.decodeToString().trim()
}
