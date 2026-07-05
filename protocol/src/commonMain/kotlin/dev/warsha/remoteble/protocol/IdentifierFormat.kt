package dev.warsha.remoteble.protocol

import kotlinx.serialization.Serializable

/**
 * The concrete format a client's Kable `Identifier` can hold on its local platform/host. Declared
 * by the client in [ClientHello.identifierFormat] so an agent that negotiated
 * [Capabilities.IDENTIFIER_TRANSLATION] can mint device handles the client can turn into a native
 * `Identifier` — regardless of which platform the agent runs on.
 *
 * Kable's `Identifier` is a *local-platform* concept (authoritative:
 * `kable-btleplug-ffi/src/peripheral_id.rs`):
 * - [STRING] — Android: `typealias Identifier = String`, accepts any string.
 * - [UUID] — Apple and the macOS-host JVM.
 * - [MAC_ADDRESS] — the Windows-host JVM.
 * - [BLUEZ_JSON] — the Linux-host JVM (btleplug's internal bluez `PeripheralId` JSON).
 *
 * The wire form is the entry name (SCREAMING_SNAKE), matching the rest of the protocol's enums; an
 * unknown value therefore fails decode loudly rather than silently mis-routing — acceptable because
 * this is negotiated behind a capability both peers must name.
 */
@Serializable
enum class IdentifierFormat {
    STRING,
    UUID,
    MAC_ADDRESS,
    BLUEZ_JSON,
}
