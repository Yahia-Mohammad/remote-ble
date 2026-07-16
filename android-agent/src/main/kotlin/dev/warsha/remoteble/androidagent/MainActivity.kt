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
 * (none of which `:android-client` needed — it never touches a local radio) and starts
 * [AgentService] (handing it the [AgentRunner] it should observe) whenever [AgentRunner.running]
 * flips true — [AgentService] is responsible for stopping itself in lockstep from there, so the
 * agent survives backgrounding without depending on this composition staying alive. It also holds
 * the screen on while running (see the `running` effect below): a slept screen throttles BLE scans.
 */
class MainActivity : ComponentActivity() {

    private val viewModel: AgentViewModel by viewModels()

    private val requestPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            viewModel.bluetoothPermissionsGranted.value = requiredBluetoothPermissions().all { grants[it] == true }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        initAndroidAgentContext(this)
        viewModel.bluetoothPermissionsGranted.value = hasBluetoothPermissions()
        requestPermissions.launch(requiredPermissions())
        setContent {
            val running by viewModel.runner.running.collectAsState()
            val bluetoothGranted by viewModel.bluetoothPermissionsGranted
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
                // before it will start this plaintext listener.
                config = AgentConfig(bindHost = "0.0.0.0"),
                addressLabel = { port ->
                    lanIPv4Address()?.let { "ws://$it:$port/agent" }
                        ?: "No Wi-Fi — connect to a network to reach this agent"
                },
                startEnabled = bluetoothGranted,
                permissionWarning = if (bluetoothGranted) {
                    null
                } else {
                    "Bluetooth permission is required to start the agent."
                },
                onRequestPermissionSettings = if (bluetoothGranted) null else ::openAppSettings,
            )
        }
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
    }.toTypedArray()
}

/** Thin [ViewModel] wrapper so [AgentRunner] survives rotation like [AgentApp]'s state would. */
class AgentViewModel : ViewModel() {
    val runner = AgentRunner()
    val bluetoothPermissionsGranted = mutableStateOf(false)

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
