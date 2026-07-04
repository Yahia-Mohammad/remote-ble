package dev.warsha.remoteble.agent

import androidx.compose.ui.window.ComposeUIViewController
import dev.warsha.remoteble.agent.ui.AgentApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import platform.UIKit.UIApplication
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

    /** Builds the Compose UI hosting [AgentRunner]. There's no background server on iOS (see
     * the class doc on [AgentApp]): while [AgentRunner.running] is true the idle timer stays
     * disabled so the screen can't auto-lock and kill the in-process WebSocket server, and
     * [AgentApp] shows a matching on-screen reminder. */
    fun makeViewController(): UIViewController = ComposeUIViewController {
        AgentApp(
            runner = runner,
            addressLabel = { port ->
                lanIPv4Address()?.let { "ws://$it:$port/agent" }
                    ?: "No Wi-Fi — connect to a network to reach this agent"
            },
            keepScreenOnNotice = "Keep this screen open — the agent stops in the background.",
        )
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
