package dev.warsha.remoteble.agent

// Kable's JVM (btleplug) backend has no connected-RSSI read: its rssi() returns the last
// advertisement RSSI (or Int.MIN_VALUE), which is stale once connected — so don't advertise it.
internal actual fun agentRssiSupported(): Boolean = false
