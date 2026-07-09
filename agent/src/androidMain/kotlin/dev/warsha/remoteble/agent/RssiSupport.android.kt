package dev.warsha.remoteble.agent

// Kable's Android backend does a live connected read via BluetoothGatt.readRemoteRssi().
internal actual fun agentRssiSupported(): Boolean = true
