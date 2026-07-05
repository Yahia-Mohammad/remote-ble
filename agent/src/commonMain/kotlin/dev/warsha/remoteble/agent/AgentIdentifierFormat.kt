package dev.warsha.remoteble.agent

import dev.warsha.remoteble.protocol.IdentifierFormat

/**
 * The [IdentifierFormat] this agent's platform mints device handles in — i.e. the format Kable's
 * radio backend produces here. Used by [HandleTranslator] to skip translation when a client already
 * speaks the agent's native format (a same-platform pairing needs no rewrite).
 *
 * - Android → [IdentifierFormat.STRING] (MAC-shaped, but any string).
 * - iOS → [IdentifierFormat.UUID] (CoreBluetooth peripheral UUIDs).
 * - JVM → resolved from the host OS at runtime (macOS→UUID, Windows→MAC, else→bluez JSON), matching
 *   Kable's btleplug backend.
 */
internal expect fun agentIdentifierFormat(): IdentifierFormat
