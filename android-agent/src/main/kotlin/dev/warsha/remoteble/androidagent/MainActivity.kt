package dev.warsha.remoteble.androidagent

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import dev.warsha.remoteble.agent.AgentRunner
import dev.warsha.remoteble.agent.AndroidKeystoreTls
import dev.warsha.remoteble.agent.AgentService
import dev.warsha.remoteble.agent.di.AgentConfig
import dev.warsha.remoteble.agent.initAndroidAgentContext
import dev.warsha.remoteble.agent.lanIPv4Address
import dev.warsha.remoteble.agent.runCatchingNonCancellation
import dev.warsha.remoteble.agent.ui.AgentApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Entry point for the on-device RemoteBLE agent. The BLE/server logic ([AgentRunner]), the
 * Compose UI ([AgentApp]), and the foreground service ([AgentService]) all live in `:agent`;
 * this Activity only requests the runtime Bluetooth permissions Android requires before a scan
 * (none of which `:android-client` needed — it never touches a local radio), plus local-network
 * access on API 37+ so LAN clients can reach the listener, and starts
 * [AgentService] (handing it the [AgentRunner] it should observe) whenever [AgentRunner.running]
 * flips true — [AgentService] is responsible for stopping itself in lockstep from there, so the
 * agent survives backgrounding without depending on this composition staying alive. It also holds
 * the screen on while running (see the `running` effect below): a slept screen throttles BLE scans.
 */
class MainActivity : ComponentActivity() {

    private val viewModel: AgentViewModel by viewModels()

    private val requestPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refreshGrants() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        initAndroidAgentContext(this)
        refreshGrants()
        requestPermissions.launch(requiredPermissions())
        setContent {
            val running by viewModel.runner.running.collectAsState()
            val bluetoothGranted by viewModel.bluetoothPermissionsGranted
            val localNetworkGranted by viewModel.localNetworkPermissionGranted
            LaunchedEffect(running) {
                if (running) {
                    AgentService.start(this@MainActivity, viewModel.runner)
                    // Keep the screen awake while serving: with the screen off Android throttles
                    // (and can effectively stop) BLE scans, so a locked phone silently stops
                    // discovering/holding peripherals. The foreground service keeps the *process*
                    // alive; this keeps the *radio* at full rate while the agent UI is foreground.
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
            AgentApp(
                runner = viewModel.runner,
                // Phone agents are intentionally LAN-facing; AgentApp requires an auth token
                // before it will start this listener, encrypted or not.
                config = AgentConfig(bindHost = "0.0.0.0"),
                addressLabel = { port, scheme ->
                    // A LAN address the platform will not let anyone reach is worse than none: a
                    // client pointed at it just times out, which reads as "agent not running".
                    if (!localNetworkGranted) {
                        "Not reachable from the network — local network access is denied"
                    } else {
                        lanIPv4Address()?.let { "$scheme://$it:$port/agent" }
                            ?: "No Wi-Fi — connect to a network to reach this agent"
                    }
                },
                startEnabled = bluetoothGranted,
                permissionWarning = if (bluetoothGranted) {
                    null
                } else {
                    "Bluetooth permission is required to start the agent."
                },
                onRequestPermissionSettings = if (bluetoothGranted) null else ::openAppSettings,
                // Not a Start gate: the agent still serves this device's loopback (and so
                // `adb forward`), exactly as it does with the Bluetooth adapter switched off.
                localNetworkWarning = if (localNetworkGranted) {
                    null
                } else {
                    "Local network permission is denied, so devices on this Wi-Fi cannot reach the " +
                        "agent. Allow \"Nearby devices\" / local network access for this app."
                },
                onRequestLocalNetworkSettings = if (localNetworkGranted) null else ::openAppSettings,
                tls = AndroidKeystoreTls,
            )
        }
    }

    override fun onResume() {
        super.onResume()
        // Returning from the settings page is the usual way a denied permission gets granted, and
        // no result callback fires for it.
        refreshGrants()
    }

    private fun refreshGrants() {
        viewModel.bluetoothPermissionsGranted.value = hasBluetoothPermissions()
        viewModel.localNetworkPermissionGranted.value = hasLocalNetworkPermission()
    }

    private fun openAppSettings() {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)),
        )
    }

    private fun hasBluetoothPermissions(): Boolean =
        requiredBluetoothPermissions().all {
            checkSelfPermission(it) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }

    private fun hasLocalNetworkPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.CINNAMON_BUN ||
            checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun requiredBluetoothPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    private fun requiredPermissions(): Array<String> = buildList {
        addAll(requiredBluetoothPermissions())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN) {
            add(Manifest.permission.ACCESS_LOCAL_NETWORK)
        }
    }.toTypedArray()
}

/** Thin [ViewModel] wrapper so [AgentRunner] survives rotation like [AgentApp]'s state would. */
class AgentViewModel : ViewModel() {
    val runner = AgentRunner()
    val bluetoothPermissionsGranted = mutableStateOf(false)
    val localNetworkPermissionGranted = mutableStateOf(true)

    override fun onCleared() {
        // A dedicated scope, not viewModelScope: by onCleared() that scope may already be
        // cancelling, and this best-effort radio/server teardown must still run. AgentService
        // observes runner.running itself (see AgentService KDoc) and stops in response, so no
        // separate service-stop call is needed here. Wrapped so a teardown throwable can't escape
        // uncaught on this fire-and-forget scope.
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            runCatchingNonCancellation { runner.stop() }
        }
        super.onCleared()
    }
}
