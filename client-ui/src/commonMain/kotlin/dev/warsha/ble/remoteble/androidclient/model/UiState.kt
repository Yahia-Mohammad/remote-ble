package dev.warsha.ble.remoteble.androidclient.model

import dev.warsha.ble.remoteble.androidclient.ble.BleUuids
import dev.warsha.ble.remoteble.androidclient.ble.GattDecoder
import dev.warsha.ble.remoteble.client.RemoteAdvertisement
import dev.warsha.ble.remoteble.client.TransportState
import dev.warsha.ble.remoteble.protocol.DeviceHandle
import com.juul.kable.DiscoveredCharacteristic
import com.juul.kable.DiscoveredService
import com.juul.kable.State
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * The complete, immutable state the UI renders. The presence of [device] decides the
 * screen: `null` shows the scanner, non-null shows the device explorer.
 */
@OptIn(ExperimentalUuidApi::class)
data class UiState(
    val agentUrl: String = DEFAULT_AGENT_URL,
    val agentState: TransportState = TransportState.DISCONNECTED,
    val isScanning: Boolean = false,
    val status: String = "Idle.",
    val discovered: List<DiscoveredDevice> = emptyList(),
    val hideUnnamed: Boolean = true,
    val device: DeviceState? = null,
) {
    companion object {
        const val DEFAULT_AGENT_URL: String = "ws://10.0.2.2:8080/agent"
    }
}

/**
 * A device seen while scanning, accumulated across its advertisements.
 *
 * BLE advertisements arrive repeatedly and individual packets routinely omit fields — a
 * name-less or RSSI-less update is normal (e.g. CoreBluetooth forwarding a bare RSSI refresh).
 * Keeping the last *known* name and RSSI rather than overwriting with whatever the latest packet
 * happened to carry stops a named device from flickering to "Unnamed" in a noisy environment.
 */
data class DiscoveredDevice(
    val identifier: String,
    val handle: DeviceHandle,
    val name: String?,
    val rssi: Int,
) {
    /** Folds in a newer sighting of the same device, preserving last-known name/RSSI when absent. */
    fun mergedWith(sighting: DiscoveredDevice): DiscoveredDevice = copy(
        handle = sighting.handle,
        name = sighting.name ?: name,
        rssi = sighting.rssi.takeIf { it != Int.MIN_VALUE } ?: rssi,
    )

    companion object {
        fun from(advertisement: RemoteAdvertisement): DiscoveredDevice = DiscoveredDevice(
            // Identifier is expect/actual (String on Android, Uuid on Apple) — normalize to a
            // stable String for display/keying regardless of platform.
            identifier = advertisement.identifier.toString(),
            handle = advertisement.handle,
            name = advertisement.name,
            rssi = advertisement.rssi,
        )
    }
}

/**
 * Named devices first, then unnamed, each group in first-seen order: [sortedBy] is a stable
 * sort, so it only ever moves a device between the two groups (e.g. once its name arrives)
 * instead of reshuffling within a group. When [hideUnnamed] is set, unnamed devices are dropped
 * instead of grouped last.
 */
fun List<DiscoveredDevice>.sortedNamedFirst(hideUnnamed: Boolean): List<DiscoveredDevice> =
    filter { !hideUnnamed || !it.name.isNullOrBlank() }.sortedBy { it.name.isNullOrBlank() }

/**
 * A connected (or connecting) peripheral. Raw characteristic bytes are the source of
 * truth; the well-known profile fields below are *derived* from them on read, so there is
 * no second copy of state to keep in sync.
 */
@OptIn(ExperimentalUuidApi::class)
data class DeviceState(
    val handle: DeviceHandle,
    val name: String?,
    val connectionState: State,
    val services: List<DiscoveredService>? = null,
    val values: Map<Uuid, ByteArray> = emptyMap(),
    val subscribed: Set<Uuid> = emptySet(),
    /** True once the link has reached [State.Connected] at least once — lets the UI tell
     *  "still connecting" apart from "dropped after connecting". */
    val everConnected: Boolean = false,
) {
    val isConnected: Boolean get() = connectionState is State.Connected

    val heartRateBpm: Int? get() = values[BleUuids.HEART_RATE_MEASUREMENT]
        ?.let(GattDecoder::decodeHeartRate)?.takeIf { it > 0 }
    val heartRateLocation: String? get() = values[BleUuids.BODY_SENSOR_LOCATION]
        ?.let(GattDecoder::decodeBodySensorLocation)
    val batteryLevel: Int? get() = values[BleUuids.BATTERY_LEVEL]?.let(GattDecoder::decodeBatteryLevel)
    val manufacturer: String? get() = values[BleUuids.MANUFACTURER_NAME]?.let(GattDecoder::decodeUtf8String)
    val model: String? get() = values[BleUuids.MODEL_NUMBER]?.let(GattDecoder::decodeUtf8String)

    val isHeartRateSubscribed: Boolean get() = BleUuids.HEART_RATE_MEASUREMENT in subscribed
    val isBatterySubscribed: Boolean get() = BleUuids.BATTERY_LEVEL in subscribed

    fun valueOf(characteristic: DiscoveredCharacteristic): ByteArray? = values[characteristic.characteristicUuid]
    fun isSubscribed(characteristic: DiscoveredCharacteristic): Boolean =
        characteristic.characteristicUuid in subscribed

    /** Finds a characteristic by UUID across all discovered services, or `null`. */
    fun characteristic(uuid: Uuid): DiscoveredCharacteristic? =
        services?.firstNotNullOfOrNull { service ->
            service.characteristics.firstOrNull { it.characteristicUuid == uuid }
        }
}
