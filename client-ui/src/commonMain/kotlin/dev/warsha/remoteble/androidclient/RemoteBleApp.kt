package dev.warsha.remoteble.androidclient

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dev.warsha.remoteble.androidclient.ui.DeviceScreen
import dev.warsha.remoteble.androidclient.ui.RemoteBleTheme
import dev.warsha.remoteble.androidclient.ui.ScanScreen

/**
 * Picks the screen from state: a connected device shows the explorer, otherwise the scanner.
 * Shared by the Android `MainActivity` and the iOS `MainViewController` — each just wraps this in
 * its own entry point over a [RemoteBleController].
 */
@Composable
fun RemoteBleApp(controller: RemoteBleController) {
    RemoteBleTheme {
        val state by controller.uiState.collectAsState()

        val device = state.device
        if (device == null) {
            ScanScreen(
                state = state,
                onStartScan = controller::startScan,
                onStopScan = controller::stopScan,
                onUrlChanged = controller::updateUrl,
                onConnectDevice = { adv -> controller.connectDevice(adv.handle, adv.name) },
                onHideUnnamedChanged = controller::setHideUnnamed,
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
    }
}
