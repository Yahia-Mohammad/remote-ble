package dev.warsha.remoteble.protocol

/** WebSocket close reason used for an invalid or incompatible protocol range. */
public const val INCOMPATIBLE_PROTOCOL_CLOSE_REASON: String = "REMOTE_BLE_INCOMPATIBLE_PROTOCOL"

/** The outcome of comparing a peer's advertised protocol range with one implementation version. */
public sealed class ProtocolVersionSelection {
    /** The highest version common to both peers. */
    public data class Selected(val version: Int) : ProtocolVersionSelection()

    /** The peer sent a malformed range where its lower bound exceeds its upper bound. */
    public object InvalidRange : ProtocolVersionSelection()

    /** The peer range is well-formed but has no common version with this implementation. */
    public object NoCompatibleVersion : ProtocolVersionSelection()
}

/**
 * Selects the highest version in `[minVersion, maxVersion]` that this implementation supports.
 *
 * Protocol v1 currently has one supported version, but keeping the selection rule here prevents
 * either agent from treating an incompatible hello as a successful v1 handshake when later ranges
 * are introduced.
 */
public fun selectProtocolVersion(
    minVersion: Int,
    maxVersion: Int,
    supportedVersion: Int = PROTOCOL_VERSION,
): ProtocolVersionSelection = when {
    minVersion > maxVersion -> ProtocolVersionSelection.InvalidRange
    supportedVersion in minVersion..maxVersion -> ProtocolVersionSelection.Selected(supportedVersion)
    else -> ProtocolVersionSelection.NoCompatibleVersion
}
