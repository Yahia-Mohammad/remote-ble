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
    /**
     * Who holds the peripheral, on [ErrorKind.PERIPHERAL_BUSY]. Populated only for a client that
     * negotiated [Capabilities.LEASE_HOLDER] — see that capability for why the field is gated
     * rather than always sent. Null on every other kind, and for a client without it, which keeps
     * reading [message].
     */
    val holder: LeaseHolder? = null,
)

/**
 * A lease holder's identity, split into the two halves a caller can act on separately.
 *
 * [message] already names the holder in prose, but prose is not a contract: a client that wants to
 * say "retry when lab-a is done" or attribute contention in structured output would have to parse
 * a human sentence back apart. This is the same information addressed as fields.
 *
 * Disclosure is scoped to the caller, so [clientId] is null where the caller is not entitled to it
 * — another principal's client id can carry a hostname or username it never meant to publish. Both
 * halves are text the *holder* chose, and they are rendered by whatever client was refused (a
 * terminal, a log, a coding agent's context), so they arrive length-bounded and
 * control-character escaped, exactly as the prose message does.
 */
@Serializable
data class LeaseHolder(val principal: String, val clientId: String? = null)

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
    POLICY_DENIED(transient = false),      // the per-principal write allowlist refused this op
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
