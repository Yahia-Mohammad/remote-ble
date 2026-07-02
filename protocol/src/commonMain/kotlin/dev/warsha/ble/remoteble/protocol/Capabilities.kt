package dev.warsha.ble.remoteble.protocol

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
     */
    const val CONNECTION_SLOTS: String = "slots"

    /** Connection-priority op (`Op.RequestConnectionPriority`). Backend-level (Android-only). */
    const val CONN_PRIORITY: String = "conn.priority"

    /**
     * Coalesced `AgentEvent.ScanResultBatch` events instead of per-advertisement
     * `ScanResult`. Agent-level (the agent does the coalescing).
     */
    const val SCAN_BATCH: String = "scan.batch"
}
