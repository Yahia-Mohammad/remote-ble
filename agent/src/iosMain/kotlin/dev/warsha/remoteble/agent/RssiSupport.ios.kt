package dev.warsha.remoteble.agent

// Kable's Apple backend does a live connected read via CBPeripheral.readRSSI().
internal actual fun agentRssiSupported(): Boolean = true
