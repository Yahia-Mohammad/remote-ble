package dev.warsha.ble.remoteble.client

import com.juul.kable.Identifier

/**
 * Converts an agent-minted device handle into the platform [Identifier], skipping the
 * validation that Kable's `String.toIdentifier()` applies.
 *
 * The agent (macOS CoreBluetooth) mints handles as UUID strings. Kable's Android
 * `Identifier` is a MAC address and its `toIdentifier()` rejects non-MAC strings, so
 * the validating path crashes Android clients the moment they read an advertisement's
 * (or peripheral's) `identifier`. In remote mode the handle is opaque — actual ops key
 * off [dev.warsha.ble.remoteble.protocol.DeviceHandle], not this value — so we expose the
 * handle as the platform [Identifier] without demanding a specific local format.
 */
internal expect fun deviceHandleToIdentifier(value: String): Identifier
