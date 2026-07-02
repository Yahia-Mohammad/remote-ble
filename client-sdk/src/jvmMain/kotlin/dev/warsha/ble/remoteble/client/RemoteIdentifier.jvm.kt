package dev.warsha.ble.remoteble.client

import com.juul.kable.Identifier
import com.juul.kable.toIdentifier

// JVM's Identifier wraps an opaque string (PeripheralId) with no format validation,
// so the agent's UUID handle passes through unchanged.
internal actual fun deviceHandleToIdentifier(value: String): Identifier = value.toIdentifier()
