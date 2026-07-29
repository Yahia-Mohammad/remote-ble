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
    delegate.manager = CBCentralManager(delegate = delegate, queue = null)
    // MUST be retained here. CBCentralManager holds its delegate **weakly**, and the returned
    // `asStateFlow()` captures only the MutableStateFlow — nothing in it references the delegate.
    // Without this line the delegate is deallocated as soon as this function returns, which fails
    // in the most misleading way available: the *initial* state still arrives (the callback fires
    // while the local reference is alive), so everything looks wired up, and then no transition
    // ever arrives again.
    //
    // Measured on an iPhone 14 before this was added: a client saw `radio -> ON` at handshake and
    // then nothing at all across a real Bluetooth off/on cycle, while 30 consecutive scans were
    // accepted against a radio that was actually off.
    //
    // Process-lifetime, matching [AgentRadio], which caches the flow for the process and is the
    // only caller. Written under AgentRadio's lock.
    retainedRadioDelegate = delegate
    return state.asStateFlow()
}

private var retainedRadioDelegate: RadioStateDelegate? = null

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
