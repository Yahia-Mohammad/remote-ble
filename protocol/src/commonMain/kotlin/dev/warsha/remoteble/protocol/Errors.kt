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

/**
 * [transient] answers "could an identical retry, later, plausibly succeed?" — it is about the
 * *error*, independent of the *operation*. A transient kind reflects a passing condition (a busy
 * radio, a dropped link, a full slot table); a non-transient one reflects a stable fact that a
 * retry cannot change (an unknown device, an unsupported op, a missing characteristic). It is the
 * error half of the retry decision; the operation half is [Op.isIdempotent] — a caller should
 * auto-retry only when *both* say yes (see `RetryPolicy` in the client SDK). Wire form is unchanged:
 * the enum serializes by name, so [transient] is a pure client-side annotation.
 */
@Serializable
enum class ErrorKind(val transient: Boolean) {
    // Reached the radio and the radio said no:
    CONNECTION_FAILED(transient = true),   // link setup can succeed on a later attempt
    DISCONNECTED(transient = true),        // the device can be reconnected
    GATT_ERROR(transient = false),         // a GATT-layer protocol/permission error won't change
    READ_FAILED(transient = true),         // a read can fail momentarily and succeed on retry
    WRITE_FAILED(transient = true),        // the radio rejected the write; a retry may take (but see isIdempotent)
    CHARACTERISTIC_NOT_FOUND(transient = false), // the GATT table won't grow on retry
    NOT_CONNECTED(transient = true),       // reconnect, then the op can proceed

    // Never reached the radio (agent- or transport-level):
    UNKNOWN_DEVICE(transient = false),     // the agent has never seen this handle
    NO_CONNECTION_SLOT(transient = true),  // a slot may free up
    PERIPHERAL_BUSY(transient = true),     // the peripheral may become free
    AGENT_BUSY(transient = true),          // the agent may become free
    SCAN_UNAVAILABLE(transient = true),    // `single` mode is held by another logical scan
    INVALID_REQUEST(transient = false),    // request is malformed or exceeds a published limit
    UNSUPPORTED(transient = false),        // capability absent — permanently so for this agent
    TIMEOUT(transient = true),             // the agent may answer a later attempt
    TRANSPORT_LOST(transient = true),      // the IP link may reconnect
    INCOMPATIBLE_PROTOCOL(transient = false), // the peer has no mutually supported wire version
    RADIO_OFF(transient = true),           // the agent host's radio is off; it can be switched back on
    ;

    companion object {
        /** The kinds for which a later retry could plausibly succeed. */
        val transientKinds: Set<ErrorKind> = entries.filter { it.transient }.toSet()
    }
}

class AgentException(val error: AgentError) : Exception(error.message ?: error.kind.name)

fun AgentError.toException(): AgentException = AgentException(this)

/** Returns the success payload (possibly null), or throws [AgentException] on [OpResult.Err]. */
fun OpResult.orThrow(): ResultPayload? = when (this) {
    is OpResult.Ok -> payload
    is OpResult.Err -> throw error.toException()
}
