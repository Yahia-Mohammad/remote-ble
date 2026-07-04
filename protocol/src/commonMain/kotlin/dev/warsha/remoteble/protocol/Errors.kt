package dev.warsha.remoteble.protocol

import kotlinx.serialization.Serializable

/**
 * An error from the agent. The key distinction is WHERE it failed: [ErrorKind]
 * separates "reached the radio and the radio said no" from "never reached the
 * radio (agent- or transport-level)". [gattStatus] carries the raw status from
 * the agent's BLE stack when the radio actually answered.
 */
@Serializable
data class AgentError(
    val kind: ErrorKind,
    val gattStatus: Int? = null,
    val message: String? = null,
)

@Serializable
enum class ErrorKind {
    // Reached the radio and the radio said no:
    CONNECTION_FAILED,
    DISCONNECTED,
    GATT_ERROR,
    READ_FAILED,
    WRITE_FAILED,
    CHARACTERISTIC_NOT_FOUND,
    NOT_CONNECTED,

    // Never reached the radio (agent- or transport-level):
    UNKNOWN_DEVICE,
    NO_CONNECTION_SLOT,
    PERIPHERAL_BUSY,
    AGENT_BUSY,
    UNSUPPORTED,
    TIMEOUT,
    TRANSPORT_LOST,
}

class AgentException(val error: AgentError) : Exception(error.message ?: error.kind.name)

fun AgentError.toException(): AgentException = AgentException(this)

/** Returns the success payload (possibly null), or throws [AgentException] on [OpResult.Err]. */
fun OpResult.orThrow(): ResultPayload? = when (this) {
    is OpResult.Ok -> payload
    is OpResult.Err -> throw error.toException()
}
