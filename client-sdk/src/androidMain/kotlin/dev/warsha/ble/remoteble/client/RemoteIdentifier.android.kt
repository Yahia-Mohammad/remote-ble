package dev.warsha.ble.remoteble.client

import com.juul.kable.Identifier

// Android's Identifier is a `typealias Identifier = String`, but its `toIdentifier()`
// enforces MAC-address format (BluetoothAdapter.checkBluetoothAddress), which rejects
// the agent's CoreBluetooth UUID handles and crashes the app. The value is already a
// valid Identifier (a String); return it directly without the MAC check.
internal actual fun deviceHandleToIdentifier(value: String): Identifier = value
