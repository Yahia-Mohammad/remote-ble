package dev.warsha.remoteble.agent

import dev.warsha.remoteble.protocol.BleRadioState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import platform.CoreBluetooth.CBCentralManager
import platform.CoreBluetooth.CBCentralManagerDelegateProtocol
import platform.CoreBluetooth.CBManagerStatePoweredOff
import platform.CoreBluetooth.CBManagerStatePoweredOn
import platform.CoreBluetooth.CBManagerStateResetting
import platform.CoreBluetooth.CBManagerStateUnauthorized
import platform.CoreBluetooth.CBManagerStateUnknown
import platform.CoreBluetooth.CBManagerStateUnsupported
import platform.darwin.NSObject

/**
 * Apple's radio state, read from a dedicated [CBCentralManager]'s `state` via its delegate.
 *
 * A separate manager from Kable's is deliberate. Kable owns its central for scanning and
 * connecting and exposes no state hook we can attach to, and CoreBluetooth is explicitly fine with
 * several managers per process — they each observe the same radio. The alternative, reaching into
 * Kable's internals, would bind this to a private API of a library that has already deprecated its
 * public one.
 *
 * The manager is held by the returned flow's closure for the agent's lifetime. It is created with a
 * `nil` queue, so its delegate callbacks arrive on the main queue; the only thing they do is write
 * to a [MutableStateFlow], which is safe from any thread.
 */
internal actual fun agentRadioStateSource(scope: CoroutineScope): StateFlow<BleRadioState>? {
    val state = MutableStateFlow(BleRadioState.UNKNOWN)
    val delegate = RadioStateDelegate(state)
    // Retained by `delegate`, which the flow's closure retains: CBCentralManager holds its delegate
    // weakly, so dropping either reference would silently stop the updates.
    delegate.manager = CBCentralManager(delegate = delegate, queue = null)
    return state.asStateFlow()
}

private class RadioStateDelegate(
    private val state: MutableStateFlow<BleRadioState>,
) : NSObject(), CBCentralManagerDelegateProtocol {
    var manager: CBCentralManager? = null

    override fun centralManagerDidUpdateState(central: CBCentralManager) {
        state.value = when (central.state) {
            CBManagerStatePoweredOn -> BleRadioState.ON
            CBManagerStatePoweredOff -> BleRadioState.OFF
            CBManagerStateUnauthorized -> BleRadioState.UNAUTHORIZED
            CBManagerStateUnsupported -> BleRadioState.UNSUPPORTED
            // `resetting` is a transient restart of the system's BLE service, and `unknown` is the
            // pre-initialisation value. Neither is a usable radio and neither is a stable fact, so
            // both map to UNKNOWN rather than to OFF — a client should not be told the user
            // switched Bluetooth off when they did not.
            CBManagerStateResetting, CBManagerStateUnknown -> BleRadioState.UNKNOWN
            else -> BleRadioState.UNKNOWN
        }
    }
}
