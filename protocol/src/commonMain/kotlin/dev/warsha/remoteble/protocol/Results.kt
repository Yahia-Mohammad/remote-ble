package dev.warsha.remoteble.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The reply to a [Command]: success (optionally carrying a payload) or an error. */
@Serializable
sealed interface OpResult {
    @Serializable @SerialName("ok")
    data class Ok(val payload: ResultPayload? = null) : OpResult

    @Serializable @SerialName("err")
    data class Err(val error: AgentError) : OpResult
}

@Serializable
sealed interface ResultPayload {
    /** Read result. */
    @Serializable @SerialName("bytes")
    class Bytes(val value: ByteArray) : ResultPayload {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Bytes) return false
            return value.contentEquals(other.value)
        }

        override fun hashCode(): Int = value.contentHashCode()

        override fun toString(): String = "Bytes(${value.size} bytes)"
    }

    /** Discover result. */
    @Serializable @SerialName("services")
    data class Services(val services: List<ServiceNode>) : ResultPayload

    /** Connect / RequestMtu result. */
    @Serializable @SerialName("mtu")
    data class Mtu(val mtu: Int) : ResultPayload

    /** ReadRssi result — the connected link's RSSI in dBm (negative; capability `rssi`). */
    @Serializable @SerialName("rssi")
    data class Rssi(val rssi: Int) : ResultPayload

    /** Pair result — the resulting bond state. */
    @Serializable @SerialName("bond")
    data class Bond(val state: BleBondState) : ResultPayload

    /** AgentStatus result — the caller-scoped agent snapshot (capability `agent.status`). */
    @Serializable @SerialName("status")
    data class Status(val status: AgentStatusDto) : ResultPayload
}

@Serializable
data class ServiceNode(val uuid: String, val characteristics: List<CharNode>)

@Serializable
data class CharNode(
    val uuid: String,
    val properties: Int,
    val descriptors: List<String> = emptyList(),
)
