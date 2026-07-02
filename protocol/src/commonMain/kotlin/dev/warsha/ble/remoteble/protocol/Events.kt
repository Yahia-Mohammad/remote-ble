package dev.warsha.ble.remoteble.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed interface AgentEvent {
    @Serializable @SerialName("scan.result")
    data class ScanResult(val scanId: Long, val advertisement: AdvertisementDto) : AgentEvent

    /**
     * A coalesced burst of scan results for one scan, sent instead of N individual
     * [ScanResult] events to cut frame overhead in dense RF. Gated behind the
     * `scan.batch` capability; a client that didn't negotiate it only ever sees
     * [ScanResult].
     */
    @Serializable @SerialName("scan.batch")
    data class ScanResultBatch(val scanId: Long, val advertisements: List<AdvertisementDto>) : AgentEvent

    @Serializable @SerialName("notification")
    class Notification(val subId: Long, val value: ByteArray) : AgentEvent {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Notification) return false
            return subId == other.subId && value.contentEquals(other.value)
        }

        override fun hashCode(): Int = 31 * subId.hashCode() + value.contentHashCode()

        override fun toString(): String = "Notification(subId=$subId, value=${value.size} bytes)"
    }

    /**
     * The PHYSICAL BLE link's state, as seen by the agent. Distinct from the
     * IP transport's state — see the reconnection note in docs/design-decisions.md.
     */
    @Serializable @SerialName("conn.state")
    data class ConnectionState(
        val device: DeviceHandle,
        val state: BleConnState,
        val reason: AgentError? = null,
    ) : AgentEvent

    /**
     * The peripheral's bond/pairing state, as seen by the agent. Emitted on
     * pair/unpair and on unsolicited transitions (e.g. an OS-initiated unbond).
     * Gated behind the `pairing` capability.
     */
    @Serializable @SerialName("bond.state")
    data class BondState(
        val device: DeviceHandle,
        val state: BleBondState,
        val reason: AgentError? = null,
    ) : AgentEvent

    /**
     * Free/total connection slots for this client's session, emitted whenever the count
     * changes (connect, disconnect, transport-drop release). Lets a client schedule its
     * connects instead of retry-storming on [ErrorKind.NO_CONNECTION_SLOT]. Gated behind
     * the `slots` capability.
     */
    @Serializable @SerialName("conn.slots")
    data class SlotState(val free: Int, val total: Int) : AgentEvent
}

@Serializable
enum class BleConnState { CONNECTING, CONNECTED, DISCONNECTING, DISCONNECTED }

@Serializable
enum class BleBondState { NONE, BONDING, BONDED }

@Serializable
class AdvertisementDto(
    val device: DeviceHandle, // the handle to use for Connect()
    val name: String? = null,
    val rssi: Int,
    val serviceUuids: List<String> = emptyList(),
    val manufacturerData: Map<Int, ByteArray> = emptyMap(),
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AdvertisementDto) return false
        return device == other.device &&
            name == other.name &&
            rssi == other.rssi &&
            serviceUuids == other.serviceUuids &&
            manufacturerData.keys == other.manufacturerData.keys &&
            manufacturerData.all { (k, v) -> v.contentEquals(other.manufacturerData[k]) }
    }

    override fun hashCode(): Int {
        var result = device.hashCode()
        result = 31 * result + (name?.hashCode() ?: 0)
        result = 31 * result + rssi
        result = 31 * result + serviceUuids.hashCode()
        result = 31 * result + manufacturerData.entries.sumOf { (k, v) -> k * 31 + v.contentHashCode() }
        return result
    }

    override fun toString(): String =
        "AdvertisementDto(device=$device, name=$name, rssi=$rssi, " +
            "serviceUuids=$serviceUuids, manufacturerData=${manufacturerData.keys})"
}
