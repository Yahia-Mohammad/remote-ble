package dev.warsha.remoteble.androidclient

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dev.warsha.remoteble.androidclient.ui.DeviceScreen
import dev.warsha.remoteble.androidclient.ui.PairingDialog
import dev.warsha.remoteble.androidclient.ui.RemoteBleTheme
import dev.warsha.remoteble.androidclient.ui.ScanScreen

/**
 * Picks the screen from state: a connected device shows the explorer, otherwise the scanner.
 * Shared by the Android `MainActivity` and the iOS `MainViewController` — each just wraps this in
 * its own entry point over a [RemoteBleController]. [notice] is a platform condition the shared code
 * cannot see, such as a denied permission, shown on the scanner with [onNoticeAction] beside it.
 */
@Composable
fun RemoteBleApp(controller: RemoteBleController, notice: String? = null, onNoticeAction: (() -> Unit)? = null) {
    RemoteBleTheme {
        val state by controller.uiState.collectAsState()

        val device = state.device
        if (device == null) {
            ScanScreen(
                state = state,
                onStartScan = controller::startScan,
                onStopScan = controller::stopScan,
                onUrlChanged = controller::updateUrl,
                onTokenChanged = controller::updateToken,
                onPairingOffered = controller::offerPairing,
                onConnectDevice = { adv -> controller.connectDevice(adv.handle, adv.name) },
                onHideUnnamedChanged = controller::setHideUnnamed,
                notice = notice,
                onNoticeAction = onNoticeAction,
            )
        } else {
            DeviceScreen(
                device = device,
                agentState = state.agentState,
                onDisconnect = controller::disconnectDevice,
                onReadChar = controller::readCharacteristic,
                onWriteChar = controller::writeCharacteristic,
                onToggleSub = controller::toggleSubscription,
            )
        }
        // Above whichever screen is showing: a pairing link can open the app on either.
        state.pendingPairing?.let { offer ->
            PairingDialog(offer, onConfirm = controller::confirmPairing, onDismiss = controller::dismissPairing)
        }
    }
}
