package dev.warsha.remoteble.protocol

import kotlinx.serialization.Serializable

/**
 * The agent's answer to [Op.AgentStatus] — what it is, how it is configured, and who holds what.
 *
 * This exists because the only status surface before it was the operator dashboard's `/api/state`,
 * which no remote client can use: it is plaintext HTTP refused for non-loopback callers, its JSON is
 * an internal dashboard feed rather than a compatibility surface, and a Rust agent serves no HTTP at
 * all. This DTO travels the same authenticated (and, behind a proxy, encrypted) WebSocket session as
 * every other op, so one status command works against every reference agent.
 *
 * ### Disclosure is scoped to the caller
 * A lease's holder is another tenant's identity, so what a caller may see depends on who is asking —
 * the same question [Op] answers for `PERIPHERAL_BUSY`, decided by the same policy:
 *
 *  - **Normal caller** — [leases] carries only the leases this caller's own session key holds. Every
 *    other lease is reducible to a number ([otherLeases]) and the aggregate [slots] occupancy, which
 *    together answer "can I connect?" without naming anyone.
 *  - **Operator scope** — a caller that presented the operator credential on the upgrade
 *    (`OPERATOR_HEADER`) receives every lease, each with its [LeaseStatusDto.holder] named. This is
 *    the same plane the dashboard exposes, reached over the session instead of over HTTP.
 *
 * A caller can always tell which view it got from [operatorScope], so a client asking for
 * operator-only fields without the credential gets a legible answer rather than a connection
 * failure.
 */
@Serializable
data class AgentStatusDto(
    /** Human-readable engine/platform label — the same string [ServerHello.agentInfo] carries. */
    val agentInfo: String? = null,
    val protocolVersion: Int = PROTOCOL_VERSION,
    /** Milliseconds since this agent process began serving. */
    val uptimeMs: Long,
    val settings: StatusSettingsDto,
    val slots: StatusSlotsDto,
    /** How many client sessions are connected right now, across every principal. */
    val connectedClients: Int,
    /** The leases this caller is entitled to see in full — see the disclosure note above. */
    val leases: List<LeaseStatusDto> = emptyList(),
    /** Leases held by someone else and therefore not in [leases]. Always 0 under operator scope. */
    val otherLeases: Int = 0,
    /** Whether this caller presented valid operator scope on the upgrade. */
    val operatorScope: Boolean = false,
)

/**
 * The agent's effective ownership configuration — the values this process is actually running with,
 * not its defaults. A process-per-command client needs [transportGraceMs] in particular: it is the
 * window inside which its next invocation resumes rather than reconnects.
 */
@Serializable
data class StatusSettingsDto(
    val leaseGraceMs: Long,
    val transportGraceMs: Long,
    val exclusiveByDefault: Boolean,
    /** The negotiated scan-isolation policy, as its `ScanConcurrencyMode` name (lowercased). */
    val scanConcurrency: String,
    /** Identifier strict mode: agent-wide suppression of handle translation (§6.1). */
    val strictIdentifiers: Boolean,
    /**
     * Whether this agent has a per-principal write policy *configured*, as opposed to running
     * permissive. Distinct from the [Capabilities.WRITE_POLICY] capability, which every conforming
     * agent advertises unconditionally because it describes the mechanism: the capability says
     * "this agent can enforce and can send `POLICY_DENIED`", this field says "an operator has
     * actually configured rules". A client that wants to know whether its writes are subject to an
     * allowlist needs this one.
     */
    val writePolicyEnforced: Boolean = false,
)

/**
 * Host slot occupancy, agent-global and lease-aware — the same accounting the `slots` capability's
 * [AgentEvent.SlotState] reports. Deliberately a separate type rather than that event: an event's
 * `@SerialName` is wire identity for a different frame kind, and one shape should not be able to
 * break the other.
 */
@Serializable
data class StatusSlotsDto(val free: Int, val total: Int)

/**
 * One peripheral's ownership, as this caller is entitled to see it.
 *
 * [handle] is in the caller's own identifier format — translated on the way out exactly like a scan
 * result's handle, so a handle read here can be used in the next op without a round-trip through a
 * scan.
 */
@Serializable
data class LeaseStatusDto(
    val handle: String,
    /** Last-seen advertised name, when the agent has scanned this peripheral. */
    val name: String? = null,
    /**
     * The holder, rendered under the disclosure policy: `principal` alone, or `principal/clientId`
     * where the caller is entitled to the client id.
     *
     * In practice a lease this caller can see always has a holder — a normal caller sees only its
     * own leases, and an operator sees every holder — so this is nullable for wire tolerance, not
     * because any current agent omits it. The structured counterpart on a refused op is
     * [AgentError.holder], which splits the same decision into fields.
     *
     * Both halves are text the *holder* chose, so this arrives length-bounded and
     * control-character escaped — it is rendered by whatever received it, which may be a terminal or
     * a coding agent's context.
     */
    val holder: String? = null,
    /** Whether this caller holds the lease. */
    val mine: Boolean = false,
    /** Whether the radio link is currently up. */
    val connected: Boolean,
    /** Whether a release timer is running — the lease is held, but its owner is (temporarily) gone. */
    val inGrace: Boolean,
    /** Milliseconds until the release timer fires, or null when none is running. */
    val remainingGraceMs: Long? = null,
)
