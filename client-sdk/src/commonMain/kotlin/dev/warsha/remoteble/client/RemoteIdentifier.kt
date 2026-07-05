package dev.warsha.remoteble.client

import com.juul.kable.Identifier

/**
 * Converts an agent-minted device handle into the platform [Identifier].
 *
 * Kable's [Identifier] is a *local-platform* concept: Android is a `String`, Apple is a `Uuid`,
 * and the JVM wraps the host radio's native id (UUID on macOS, MAC on Windows, a bluez id on
 * Linux). A remote handle, though, is minted by the *agent's* platform, so it can only become an
 * [Identifier] when the client's platform can hold that format:
 * - Android always can (any `String`) — and this skips the MAC-format check Kable's Android
 *   `toIdentifier()` would otherwise apply, which rejects the agent's UUID handles.
 * - Apple / the JVM can when the handle matches the local format (e.g. a macOS agent's UUID
 *   handle on an Apple client, or on a macOS-host JVM client).
 * - Otherwise there is no representable [Identifier], and this throws
 *   [RemoteIdentifierUnavailableException].
 *
 * Since 0.8.0, an agent that supports the `identifier.translate` capability (see
 * [currentIdentifierFormat]) mints handles already in this client's native format, so `.identifier`
 * normally succeeds cross-platform and this throws only when translation is off (a pre-0.8.0 agent,
 * strict mode, or the still-stubbed Linux-host-JVM `BLUEZ_JSON` format). In remote mode the portable
 * identity remains the opaque [dev.warsha.remoteble.protocol.DeviceHandle] exposed as `.handle` (ops
 * key off it, not this value), so mixed-platform consumers should still prefer `.handle`.
 */
internal expect fun deviceHandleToIdentifier(value: String): Identifier

/**
 * Thrown lazily — on first access of a remote peripheral's or advertisement's `.identifier` —
 * when the agent-minted device [handle] can't be represented as a Kable [Identifier] on the
 * current client platform/host (e.g. a macOS agent's UUID handle read by a Linux-host JVM
 * client). Use `.handle` ([dev.warsha.remoteble.protocol.DeviceHandle]) as the portable
 * cross-platform identity. Removed in a future release by agent-side handle translation.
 */
public class RemoteIdentifierUnavailableException internal constructor(
    public val handle: String,
    cause: Throwable? = null,
) : IllegalStateException(
    "Remote device handle '$handle' can't be represented as a Kable Identifier on this " +
        "platform/host; use the peripheral's DeviceHandle (.handle) for portable identity.",
    cause,
)
