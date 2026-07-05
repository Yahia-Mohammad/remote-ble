package dev.warsha.remoteble.client

import dev.warsha.remoteble.protocol.IdentifierFormat

/**
 * The [IdentifierFormat] this client's local Kable `Identifier` can hold, declared to the agent in
 * the handshake so an agent that supports `identifier.translate` mints device handles this client
 * can turn into a native `Identifier` (see [deviceHandleToIdentifier]).
 *
 * - Android → [IdentifierFormat.STRING] (`typealias Identifier = String`).
 * - iOS → [IdentifierFormat.UUID] (`Uuid`).
 * - JVM → resolved from the host OS (macOS→UUID, Windows→MAC, else→bluez JSON), matching the format
 *   Kable's per-host native parser accepts.
 */
internal expect fun currentIdentifierFormat(): IdentifierFormat
