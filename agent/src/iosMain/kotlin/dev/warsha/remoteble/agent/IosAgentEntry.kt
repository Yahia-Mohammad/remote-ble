package dev.warsha.remoteble.agent

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.window.ComposeUIViewController
import dev.warsha.remoteble.agent.ui.AgentApp
import dev.warsha.remoteble.agent.ui.bluetoothPermissionDenied
import dev.warsha.remoteble.agent.ui.unobservableRadio
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationOpenSettingsURLString
import platform.UIKit.UIViewController

/**
 * Owns one [AgentRunner] and the scope observing it, for exactly one hosting
 * `UIViewController`. [IosAgentEntry] creates this; `ios-agent`'s `ComposeView.swift` is
 * expected to call [dispose] when that view controller is deallocated (via a `Coordinator`'s
 * `deinit`) so repeated `makeUIViewController` calls don't accumulate scopes/runners.
 */
class IosAgentSession internal constructor() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val runner = AgentRunner()

    init {
        scope.launch {
            runner.running.collect { running ->
                UIApplication.sharedApplication.idleTimerDisabled = running
            }
        }
    }

    /**
     * Builds the Compose UI hosting [AgentRunner]. While [AgentRunner.running] is true the idle
     * timer stays disabled so the screen can't auto-lock, and [AgentApp] shows a matching on-screen
     * reminder.
     *
     * **What backgrounding actually does** (measured, Rig B case 3 — `docs/pr8-rig-b-evidence.md`;
     * this used to claim flatly that "the agent stops in the background", which the hardware
     * disproved). `Info.plist` declares `UIBackgroundModes: bluetooth-central`, which keeps the
     * process alive *while it holds an active CoreBluetooth connection* — and a live process keeps
     * running its Ktor accept loop. So a backgrounded agent with a client mid-session stays fully
     * reachable, new inbound connections included: 92/92 GATT reads and 38/38 fresh connections
     * served across 91 s backgrounded. With **no** BLE link the app is suspended ~8 s after
     * backgrounding and new connections hang until it is foregrounded again.
     *
     * The reminder therefore stays — an idle agent really does stop answering, and relying on a
     * radio link to stay scheduled is not something to design around — but it no longer overstates
     * the restriction.
     */
    fun makeViewController(): UIViewController = ComposeUIViewController {
        // Apple's analogue of Android's runtime-permission gate. `MainActivity` passes all three of
        // these and this entry point passed none, so on iOS Start was always enabled, there was no
        // warning surface, and there was no route to the app's settings page even when CoreBluetooth
        // was reporting the permission denied outright. There is no permission *request* API to call
        // here — iOS prompts implicitly on first CoreBluetooth use — so the authorization state is
        // read from the same `CBCentralManager.state` source the rest of the agent uses, and the
        // remedy offered is the Settings deep link rather than a prompt.
        val radio by remember { AgentRadio.source() ?: unobservableRadio }.collectAsState()
        val denied = bluetoothPermissionDenied(radio)
        AgentApp(
            runner = runner,
            addressLabel = { port ->
                lanIPv4Address()?.let { "ws://$it:$port/agent" }
                    ?: "No Wi-Fi — connect to a network to reach this agent"
            },
            keepScreenOnNotice = "Keep this screen open — a backgrounded agent stops accepting " +
                "new connections once its last Bluetooth link closes.",
            startEnabled = !denied,
            permissionWarning = if (denied) "Bluetooth permission is required to start the agent." else null,
            onRequestPermissionSettings = if (denied) ::openAppSettings else null,
        )
    }

    /**
     * Opens this app's page in Settings, the only place an iOS user can restore a denied Bluetooth
     * permission — there is no in-app prompt to re-raise once it has been refused.
     */
    private fun openAppSettings() {
        val url = NSURL(string = UIApplicationOpenSettingsURLString)
        UIApplication.sharedApplication.openURL(url, options = emptyMap<Any?, Any>(), completionHandler = null)
    }

    /** Cancels the observing scope and best-effort stops the runner/radio/server. */
    fun dispose() {
        scope.cancel()
        // A dedicated scope, not `scope`: it was just cancelled above, and this best-effort
        // teardown must still run — same idea as AgentViewModel.onCleared() on Android.
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch { runner.stop() }
    }
}

/**
 * Entry point the `ios-agent` launcher shell calls into (`IosAgentEntryKt.IosAgentEntry()` from
 * Swift), mirroring `:client-ui`'s `MainViewController()`.
 */
fun IosAgentEntry(): IosAgentSession = IosAgentSession()
