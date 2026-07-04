package dev.warsha.remoteble.client

import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

private const val BLUETOOTH_BASE_SUFFIX = "-0000-1000-8000-00805f9b34fb"

/**
 * Parses a service/characteristic UUID string from the wire into a Kotlin [Uuid],
 * expanding the 16-/32-bit Bluetooth SIG short forms to their 128-bit canonical form.
 */
@OptIn(ExperimentalUuidApi::class)
internal fun parseBleUuid(value: String): Uuid {
    val normalized = when (value.length) {
        4 -> "0000$value$BLUETOOTH_BASE_SUFFIX"
        8 -> "$value$BLUETOOTH_BASE_SUFFIX"
        else -> value
    }
    return Uuid.parse(normalized)
}
