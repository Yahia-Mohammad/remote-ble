package dev.warsha.remoteble.protocol

/**
 * The current wire protocol version. Carried in the [ClientHello]/[ServerHello]
 * handshake so the two ends can detect an incompatible peer at connect time rather
 * than mid-stream. Bump this on any breaking change to frame/op *semantics* (the
 * `@SerialName` discriminators remain the structural wire identity).
 */
const val PROTOCOL_VERSION: Int = 1

/**
 * Optional features negotiated additively on top of the v1 baseline. The wire form
 * is a `Set<String>` (not an enum) on purpose: an unknown capability string decodes
 * harmlessly to "not understood" instead of failing CBOR, so an old peer and a new
 * peer always agree on the *intersection* of what they both name here.
 *
 * The negotiated set is `clientWanted ∩ agentSupported`, computed by the agent and
 * returned in [ServerHello.capabilities]. A capability absent from that set means the
 * corresponding ops will be answered with [ErrorKind.UNSUPPORTED] — clients should
 * gate on it before issuing.
 */
object Capabilities {
    /** Descriptor read/write ops (`Op.ReadDescriptor` / `Op.WriteDescriptor`). Backend-level. */
    const val DESCRIPTORS: String = "descriptors"

    /** Pairing / bonding ops (`Op.Pair` / `Op.Unpair`) and bond-state events. Backend-level. */
    const val PAIRING: String = "pairing"

    /**
     * Unsolicited `AgentEvent.SlotState` events reporting free/total connection slots.
     * Agent-level (radio-independent): the agent tracks slots itself.
     *
     * A client that negotiates this receives one event immediately, carrying the state at handshake
     * time, and one on every later change. The count is **agent-global and lease-aware**: it spans
     * every client, and a peripheral still leased inside its grace window counts as occupied,
     * because that is the capacity the next `connect` will actually meet.
     */
    const val CONNECTION_SLOTS: String = "slots"

    /** Connection-priority op (`Op.RequestConnectionPriority`). Backend-level (Android-only). */
    const val CONN_PRIORITY: String = "conn.priority"

    /**
     * Connected-RSSI read op (`Op.ReadRssi`). Backend-level. A live connected read only on Kable's
     * Android/Apple backends; the JVM/btleplug backend has no connected-RSSI API (only cached
     * advertisement RSSI) and so neither implements the op nor advertises this.
     */
    const val RSSI: String = "rssi"

    /**
     * Coalesced `AgentEvent.ScanResultBatch` events instead of per-advertisement
     * `ScanResult`. Agent-level (the agent does the coalescing).
     */
    const val SCAN_BATCH: String = "scan.batch"

    /**
     * Connection-parameters op (`Op.SetConnParams`). Supersedes `conn.priority`: an agent
     * advertising this implies the coarse profile behavior `conn.priority` always aimed for, plus
     * a reserved (currently unused) fine-grained `hint` slot. Backend-level (Android-only today).
     */
    const val CONN_PARAMS: String = "conn.params"

    /**
     * Agent-side device-handle translation: the agent rewrites every outgoing [DeviceHandle] into
     * the client's declared [IdentifierFormat] (see [ClientHello.identifierFormat]) and reverse-maps
     * incoming ops back to the real radio handle, so a remote peripheral's Kable `Identifier` works
     * on every client platform regardless of the agent's platform. Agent-level (pure computation +
     * a per-client reverse map). When absent, handles pass through unchanged (the pre-0.8.0
     * behavior — a format mismatch surfaces on the client as an unavailable `.identifier`).
     */
    const val IDENTIFIER_TRANSLATION: String = "identifier.translate"

    /**
     * Unsolicited [AgentEvent.RadioState] events, plus [ErrorKind.RADIO_OFF] on ops attempted
     * while the radio is off. Backend-level: only a backend that can actually observe its host's
     * radio advertises it (Android's `BluetoothAdapter` and Apple's `CBCentralManager.state` can;
     * the JVM/btleplug backend exposes no equivalent, so it does not).
     *
     * [ErrorKind.RADIO_OFF] is gated on this capability rather than being sent unconditionally,
     * because an unknown enum name would fail a v1 client's decode — the same reasoning that keeps
     * the gated *events* gated. A client without this capability keeps the pre-0.10.0 behaviour: a
     * scan with the radio off completes normally and yields nothing.
     */
    const val RADIO_STATE: String = "radio.state"

    /**
     * The `agent.status` op ([Op.AgentStatus] → [ResultPayload.Status]): a caller-scoped snapshot of
     * the agent's identity, uptime, effective ownership settings, slot occupancy and leases.
     * Agent-level (radio-independent): every field comes from bookkeeping the agent already keeps.
     *
     * Disclosure is scoped to the caller — see [AgentStatusDto]. A caller that presented operator
     * scope on the upgrade (`OPERATOR_HEADER`) sees every lease and its holder; every other caller
     * sees its own leases plus aggregate counts.
     */
    const val AGENT_STATUS: String = "agent.status"

    /**
     * A per-principal write allowlist is enforced (`Op.Write` / `Op.WriteDescriptor` / `Op.Pair` /
     * `Op.Unpair`). Agent-level (bookkeeping, not radio behaviour): every conforming agent
     * implements the mechanism and advertises this unconditionally, whether or not an operator has
     * actually configured a restrictive policy — [AgentStatusDto.settings]'s `writePolicyEnforced`
     * reports that separately.
     *
     * Exists so [ErrorKind.POLICY_DENIED] can be sent at all: an unknown enum name would fail a v1
     * client's decode (the same reason [RADIO_STATE] is gated), so a client that has not negotiated
     * this receives [ErrorKind.INVALID_REQUEST] for a policy-denied write instead.
     */
    const val WRITE_POLICY: String = "write.policy"

    /**
     * Structured holder details on [ErrorKind.PERIPHERAL_BUSY]: [AgentError.holder] names the
     * principal, and the client id where the caller is entitled to see it. Agent-level (the
     * registry already knows the owner), so every conforming agent advertises it.
     *
     * Gated for two reasons, and the first is the binding one. [AgentError] is decoded with
     * `Cbor.Default`, which does **not** ignore unknown keys, so an added field is not a
     * backward-compatible addition the way a new optional field is under a lenient codec: a v1
     * client that never heard of `holder` fails to decode the whole error frame. That is the same
     * hazard that keeps [RADIO_OFF] and [WRITE_POLICY] gated, reached through an unknown *key*
     * rather than an unknown enum name — `ProtocolCodecTest.anUngatedHolderFieldBreaksAV1Decode`
     * pins it. A client without this capability keeps the pre-0.11.0 behaviour: the holder is named
     * in [AgentError.message] only.
     *
     * Second, it makes disclosure opt-in. A caller that never asked for another tenant's identity
     * does not receive it in a machine-readable field it might log or forward.
     */
    const val LEASE_HOLDER: String = "lease.holder"

    /** The agent multiplexes all logical scans through one physical scan. */
    const val SCAN_CONCURRENCY_MULTIPLEXED: String = "scan.concurrency.multiplexed"

    /** The agent admits one logical scan globally and refuses competing scan keys. */
    const val SCAN_CONCURRENCY_SINGLE: String = "scan.concurrency.single"

    /** The agent leaves every scan on the backend's independent path. */
    const val SCAN_CONCURRENCY_UNCONTROLLED: String = "scan.concurrency.uncontrolled"
}
