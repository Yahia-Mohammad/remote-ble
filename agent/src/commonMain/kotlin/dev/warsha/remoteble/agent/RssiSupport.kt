package dev.warsha.remoteble.agent

/**
 * Whether this agent's platform can service a *connected* RSSI read — i.e. whether Kable's radio
 * backend here implements `Peripheral.rssi()` as a live read rather than a cached advertisement value.
 * [EngineBleBackend] uses this to advertise the `rssi` capability only where it's real.
 *
 * - Android → `true` (`BluetoothGatt.readRemoteRssi()`).
 * - iOS → `true` (`CBPeripheral.readRSSI()`).
 * - JVM → `false` (Kable's btleplug backend returns cached advertisement RSSI, not a connected read).
 */
internal expect fun agentRssiSupported(): Boolean
