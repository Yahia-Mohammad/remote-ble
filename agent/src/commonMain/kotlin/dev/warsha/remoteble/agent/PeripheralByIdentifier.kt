package dev.warsha.remoteble.agent

import com.juul.kable.Identifier
import com.juul.kable.Peripheral

/**
 * Resolves a [Peripheral] from nothing but its stable [Identifier] — no live `Advertisement`
 * needed. Kable's only common (`expect`) factory is `Peripheral(Advertisement, ...)`; every
 * platform separately layers an identifier-only convenience on top of it (JVM: reconnect by
 * address via `btleplug`; Android: `BluetoothAdapter.getRemoteDevice`; iOS:
 * `CentralManager.retrievePeripheral`) as a plain, non-`expect` addition. This bridges that gap
 * for [EngineBleBackend], which must reconstruct a peripheral from nothing but the
 * [dev.warsha.remoteble.protocol.DeviceHandle] string a client sends over the wire — with no
 * guarantee this process ever scanned it itself.
 */
expect fun peripheralByIdentifier(identifier: Identifier): Peripheral
