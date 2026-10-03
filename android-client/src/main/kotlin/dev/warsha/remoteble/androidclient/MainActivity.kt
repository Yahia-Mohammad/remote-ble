package dev.warsha.remoteble.androidclient

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope

/**
 * Entry point for the RemoteBLE central client. The whole UI ([RemoteBleApp]) and the
 * orchestration logic ([RemoteBleController]) are shared `commonMain`; this Activity only wires a
 * platform [ViewModel] around the controller (so an in-flight scan/connection survives rotation,
 * as before), hosts the Compose tree, and asks for local-network access on API 37+, without which
 * an agent on the Wi-Fi cannot be reached at all.
 */
class MainActivity : ComponentActivity() {

    // Retained across configuration changes so an in-flight scan/connection (and its agent
    // socket) survives rotation instead of being torn down and rebuilt.
    private val viewModel: AndroidRemoteBleViewModel by viewModels()

    private val requestLocalNetwork =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { refreshGrant() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        refreshGrant()
        // Only on a fresh start: after rotation the same intent would offer the pairing again, and
        // the permission dialog would show again.
        if (savedInstanceState == null) {
            if (!viewModel.localNetworkGranted.value) requestLocalNetwork.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
            offerPairingFrom(intent)
        }
        setContent {
            val localNetworkGranted by viewModel.localNetworkGranted
            RemoteBleApp(
                viewModel.controller,
                // Not a gate: the emulator's 10.0.2.2 and an adb-forwarded loopback still work.
                notice = if (localNetworkGranted) {
                    null
                } else {
                    "Local network access is denied, so an agent on this Wi-Fi cannot be reached. " +
                        "Allow \"Nearby devices\" for this app."
                },
                onNoticeAction = if (localNetworkGranted) null else ::openAppSettings,
            )
        }
    }

    override fun onResume() {
        super.onResume()
        // Returning from the settings page is the usual way a denied permission gets granted, and
        // no result callback fires for it.
        refreshGrant()
    }

    private fun refreshGrant() {
        viewModel.localNetworkGranted.value = Build.VERSION.SDK_INT < Build.VERSION_CODES.CINNAMON_BUN ||
            checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) == PackageManager.PERMISSION_GRANTED
    }

    private fun openAppSettings() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        offerPairingFrom(intent)
    }

    /** A `remoteble://` link the app was opened with, held for the user's confirmation. */
    private fun offerPairingFrom(intent: Intent?) {
        intent?.takeIf { it.action == Intent.ACTION_VIEW }?.dataString?.let(viewModel.controller::offerPairing)
    }
}

/** Thin [ViewModel] wrapper so [RemoteBleController] stays platform-agnostic (see its doc). */
class AndroidRemoteBleViewModel : ViewModel() {
    val controller = RemoteBleController(viewModelScope)
    val localNetworkGranted = mutableStateOf(true)

    override fun onCleared() {
        controller.close()
        super.onCleared()
    }
}
