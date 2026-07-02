package dev.warsha.ble.remoteble.androidclient.ble

import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * The Bluetooth SIG assigned numbers this demo understands, plus the well-known
 * profiles built from them.
 *
 * Centralising these here is deliberate: the UI and the connection layer both need to
 * recognise the same characteristics, and a single typed source removes the fragile
 * `startsWith("0000180d")` string matching that otherwise tends to drift between layers.
 */
@OptIn(ExperimentalUuidApi::class)
object BleUuids {

    val HEART_RATE_SERVICE: Uuid = assignedNumber(0x180D)
    val HEART_RATE_MEASUREMENT: Uuid = assignedNumber(0x2A37)
    val BODY_SENSOR_LOCATION: Uuid = assignedNumber(0x2A38)

    val BATTERY_SERVICE: Uuid = assignedNumber(0x180F)
    val BATTERY_LEVEL: Uuid = assignedNumber(0x2A19)

    val DEVICE_INFORMATION_SERVICE: Uuid = assignedNumber(0x180A)
    val MANUFACTURER_NAME: Uuid = assignedNumber(0x2A29)
    val MODEL_NUMBER: Uuid = assignedNumber(0x2A24)

    /** Expands a 16-bit SIG-assigned number into its full 128-bit base UUID. */
    private fun assignedNumber(short: Int): Uuid =
        Uuid.parse("0000${short.toString(16).padStart(4, '0')}-0000-1000-8000-00805f9b34fb")
}
