package dev.warsha.remoteble.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Opaque, AGENT-SCOPED device identifier. The agent mints these from its own
 * scan results (a MAC on Android/Linux, a CBPeripheral UUID on iOS — the client
 * must not care which). The client treats it as a token and never parses it.
 */
@Serializable
data class DeviceHandle(val value: String)

/**
 * A characteristic addressed by service + characteristic UUID (+ optional
 * instance index for the rare duplicate-UUID case). Resolved on the agent.
 */
@Serializable
data class CharRef(val service: String, val characteristic: String, val instance: Int = 0)

@Serializable
data class ScanFilter(val service: String? = null, val name: String? = null)

/** Requested link connection priority (latency vs power). Maps to the engine's own enum. */
@Serializable
enum class ConnPriority { LOW_POWER, BALANCED, HIGH }

/**
 * A descriptor addressed by service + characteristic + descriptor UUID (+ optional
 * instance index for the rare duplicate-UUID case). Resolved on the agent, like
 * [CharRef]. Gated behind the `descriptors` capability (see [Capabilities]).
 */
@Serializable
data class DescRef(
    val service: String,
    val characteristic: String,
    val descriptor: String,
    val instance: Int = 0,
)

/** The operation set: mirrors the GATT/Peripheral surface 1:1. */
@Serializable
sealed interface Op {
    @Serializable @SerialName("scan.start")
    data class ScanStart(val scanId: Long, val filters: List<ScanFilter> = emptyList()) : Op

    @Serializable @SerialName("scan.stop")
    data class ScanStop(val scanId: Long) : Op

    @Serializable @SerialName("connect")
    data class Connect(val device: DeviceHandle) : Op

    @Serializable @SerialName("disconnect")
    data class Disconnect(val device: DeviceHandle) : Op

    @Serializable @SerialName("discover")
    data class Discover(val device: DeviceHandle) : Op

    @Serializable @SerialName("read")
    data class Read(val device: DeviceHandle, val char: CharRef) : Op

    @Serializable @SerialName("write")
    class Write(
        val device: DeviceHandle,
        val char: CharRef,
        val value: ByteArray,
        val withResponse: Boolean,
    ) : Op {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Write) return false
            return device == other.device &&
                char == other.char &&
                value.contentEquals(other.value) &&
                withResponse == other.withResponse
        }

        override fun hashCode(): Int {
            var result = device.hashCode()
            result = 31 * result + char.hashCode()
            result = 31 * result + value.contentHashCode()
            result = 31 * result + withResponse.hashCode()
            return result
        }

        override fun toString(): String =
            "Write(device=$device, char=$char, value=${value.size} bytes, withResponse=$withResponse)"
    }

    @Serializable @SerialName("observe.start")
    data class ObserveStart(val subId: Long, val device: DeviceHandle, val char: CharRef) : Op

    @Serializable @SerialName("observe.stop")
    data class ObserveStop(val subId: Long) : Op

    @Serializable @SerialName("mtu")
    data class RequestMtu(val device: DeviceHandle, val mtu: Int) : Op

    // --- Descriptors (capability: "descriptors") ---

    @Serializable @SerialName("desc.read")
    data class ReadDescriptor(val device: DeviceHandle, val desc: DescRef) : Op

    @Serializable @SerialName("desc.write")
    class WriteDescriptor(
        val device: DeviceHandle,
        val desc: DescRef,
        val value: ByteArray,
    ) : Op {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is WriteDescriptor) return false
            return device == other.device && desc == other.desc && value.contentEquals(other.value)
        }

        override fun hashCode(): Int {
            var result = device.hashCode()
            result = 31 * result + desc.hashCode()
            result = 31 * result + value.contentHashCode()
            return result
        }

        override fun toString(): String =
            "WriteDescriptor(device=$device, desc=$desc, value=${value.size} bytes)"
    }

    // --- Pairing / bonding (capability: "pairing") ---

    @Serializable @SerialName("pair")
    data class Pair(val device: DeviceHandle) : Op

    @Serializable @SerialName("unpair")
    data class Unpair(val device: DeviceHandle) : Op

    // --- Connection priority (capability: "conn.priority") ---

    @Serializable @SerialName("conn.priority")
    data class RequestConnectionPriority(val device: DeviceHandle, val priority: ConnPriority) : Op

    // --- Connected RSSI (capability: "rssi") ---

    @Serializable @SerialName("rssi")
    data class ReadRssi(val device: DeviceHandle) : Op
}

/**
 * Returns a copy of this [Op] with its [DeviceHandle] replaced by [transform] applied to it. Ops
 * that carry no device (scan/observe control keyed by scanId/subId) are returned unchanged. Used by
 * the agent to reverse-translate a client-facing handle back to the real radio handle at the op
 * boundary, so the rest of op handling deals only in real handles.
 */
inline fun Op.mapDevice(transform: (DeviceHandle) -> DeviceHandle): Op = when (this) {
    is Op.Connect -> copy(device = transform(device))
    is Op.Disconnect -> copy(device = transform(device))
    is Op.Discover -> copy(device = transform(device))
    is Op.Read -> copy(device = transform(device))
    is Op.Write -> Op.Write(transform(device), char, value, withResponse)
    is Op.ObserveStart -> copy(device = transform(device))
    is Op.RequestMtu -> copy(device = transform(device))
    is Op.ReadDescriptor -> copy(device = transform(device))
    is Op.WriteDescriptor -> Op.WriteDescriptor(transform(device), desc, value)
    is Op.Pair -> copy(device = transform(device))
    is Op.Unpair -> copy(device = transform(device))
    is Op.RequestConnectionPriority -> copy(device = transform(device))
    is Op.ReadRssi -> copy(device = transform(device))
    is Op.ScanStart, is Op.ScanStop, is Op.ObserveStop -> this
}

/**
 * Whether re-issuing this op after a lost/uncertain reply is *safe* — i.e. a second execution has no
 * additional observable effect beyond the first. This is the operation half of the auto-retry
 * decision (the error half is [ErrorKind.transient]); a policy should auto-retry only when both hold.
 *
 * The hazard is a mutation that reached the radio and took effect, but whose reply was lost to a
 * [ErrorKind.TIMEOUT] / [ErrorKind.TRANSPORT_LOST]: a blind retry would apply it twice. So **all
 * writes and pairing are non-idempotent by default** — a characteristic [Op.Write] could be an
 * "increment"/"dispense", a [Op.WriteDescriptor] an arbitrary value, [Op.Pair] a bonding side
 * effect. Reads, discovery, connect/disconnect, scan/observe control, MTU and connection-priority
 * requests are convergent or effect-free, so repeating them is harmless.
 *
 * A caller who knows a specific write *is* safe to repeat can still opt in via `RetryPolicy`.
 */
val Op.isIdempotent: Boolean
    get() = when (this) {
        is Op.Write, is Op.WriteDescriptor, is Op.Pair -> false
        is Op.ScanStart, is Op.ScanStop, is Op.Connect, is Op.Disconnect, is Op.Discover,
        is Op.Read, is Op.ObserveStart, is Op.ObserveStop, is Op.RequestMtu, is Op.ReadDescriptor,
        is Op.Unpair, is Op.RequestConnectionPriority, is Op.ReadRssi -> true
    }
