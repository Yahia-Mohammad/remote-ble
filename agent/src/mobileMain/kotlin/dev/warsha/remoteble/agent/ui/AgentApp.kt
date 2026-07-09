package dev.warsha.remoteble.agent.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.warsha.remoteble.agent.AgentMonitor
import dev.warsha.remoteble.agent.AgentRunner
import dev.warsha.remoteble.agent.di.AgentConfig
import dev.warsha.remoteble.agent.loadPersistedToken
import dev.warsha.remoteble.agent.persistToken
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * A native mirror of the desktop agent's HTML status dashboard (see `Dashboard.kt`): header
 * with a start/stop control and the WebSocket address, connected-clients panel,
 * peripheral-ownership panel with an exclusive/shared toggle, and a scrolling activity log.
 * Polls [AgentRunner]'s in-process [AgentMonitor]/`PeripheralRegistry` every second, the same
 * cadence the HTML dashboard's own `poll()` uses — there's no HTTP round-trip since the UI and
 * server share one process here.
 *
 * [addressLabel] is how to reach this agent (e.g. `"ws://192.168.1.23:8080/agent"`) — resolving
 * the device's LAN IP is platform-specific, so the caller supplies it rather than this shared
 * composable owning networking APIs. [keepScreenOnNotice], if non-null, is shown whenever the
 * agent is running (iOS: reminds the user the agent stops the moment the app backgrounds/locks).
 * [startEnabled] gates the Start button (e.g. on required runtime permissions); when `false`,
 * [permissionWarning] explains why and [onRequestPermissionSettings], if supplied, renders a
 * button routing to the app's settings page.
 *
 * Auth token: an editable field, enabled only while
 * stopped, backed by [loadPersistedToken]/[persistToken] so a token set in a previous session
 * survives relaunch. If the field is left blank, pressing Start prompts for confirmation and then
 * runs the agent token-free (no auth — any client on the network can connect); see
 * [dev.warsha.remoteble.agent.AgentWebSocketServer], which drops the Authorization gate when the
 * token is null.
 */
@Composable
fun AgentApp(
    runner: AgentRunner,
    config: AgentConfig = AgentConfig(),
    addressLabel: (Int) -> String = { port -> "ws://<this device>:$port/agent" },
    keepScreenOnNotice: String? = null,
    startEnabled: Boolean = true,
    permissionWarning: String? = null,
    onRequestPermissionSettings: (() -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    val running by runner.running.collectAsState()
    var snapshot by remember { mutableStateOf<AgentMonitor.Snapshot?>(null) }
    var token by remember { mutableStateOf<String?>(null) }
    var tokenEdited by remember { mutableStateOf(false) }
    var showNoTokenConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val persisted = loadPersistedToken()
        // Don't clobber a value the user may have started typing while this async load was still
        // in flight — only seed the field from persistence if it's still untouched and empty.
        if (!tokenEdited && token.isNullOrBlank()) token = persisted
    }

    LaunchedEffect(running) {
        if (!running) {
            snapshot = null
            return@LaunchedEffect
        }
        while (isActive) {
            val monitor = runner.monitor
            val registry = runner.registry
            if (monitor != null) {
                snapshot = monitor.snapshot(registry?.snapshot().orEmpty(), registry?.settings())
            }
            delay(1_000)
        }
    }

    // Start the agent with [chosen]; a blank/null token runs token-free (no auth gate).
    val startWith: (String?) -> Unit = { chosen ->
        val effectiveToken = chosen?.takeIf { it.isNotBlank() }
        token = effectiveToken
        // Persist on Start, not per keystroke: only records what the agent actually ran with.
        // A null token clears any stored value so a relaunch also comes up token-free.
        scope.launch { persistToken(effectiveToken) }
        runner.start(config.copy(authToken = effectiveToken))
    }
    val onStart: () -> Unit = {
        // A blank field is a deliberate, security-relevant choice (anyone on the LAN can then
        // connect), so confirm before running without auth rather than silently minting a token.
        if (token.isNullOrBlank()) showNoTokenConfirm = true else startWith(token)
    }
    val onStop: () -> Unit = { scope.launch { runner.stop() } }

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            // A single LazyColumn (rather than a Column with a bounded/unbounded LazyColumn per
            // panel) so the header, and every section, share one scroll container — nesting
            // LazyColumns inside a non-scrolling Column let an unbounded panel balloon and push
            // the others off-screen. safeDrawingPadding keeps the header below the status
            // bar/notch under edge-to-edge.
            LazyColumn(modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp)) {
                item {
                    AgentHeader(
                        running = running,
                        startEnabled = startEnabled,
                        address = if (running) addressLabel(config.port) else "Stopped",
                        keepScreenOnNotice = keepScreenOnNotice,
                        token = token,
                        onTokenChange = { tokenEdited = true; token = it },
                        onStart = onStart,
                        onStop = onStop,
                        permissionWarning = permissionWarning,
                        onRequestPermissionSettings = onRequestPermissionSettings,
                    )
                }

                val s = snapshot
                if (s == null) {
                    item { Text("No activity yet.", style = MaterialTheme.typography.bodySmall) }
                } else {
                    clientsSection(s.clients)
                    leasesSection(s.leases) { handle, exclusive ->
                        scope.launch { runner.registry?.setExclusive(handle, exclusive) }
                    }
                    logsSection(s.logs)
                }
            }
        }

        if (showNoTokenConfirm) {
            AlertDialog(
                onDismissRequest = { showNoTokenConfirm = false },
                title = { Text("Start without an auth token?") },
                text = {
                    Text(
                        "The agent will accept any client on the network with no authentication. " +
                            "Only do this on a trusted network. To require a token, cancel and " +
                            "type one into the field first.",
                    )
                },
                confirmButton = {
                    TextButton(onClick = { showNoTokenConfirm = false; startWith(null) }) {
                        Text("Start without token")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showNoTokenConfirm = false }) { Text("Cancel") }
                },
            )
        }
    }
}

/**
 * Header panel: the title, Start/Stop control, agent address, editable auth-token field, and
 * (when starting is gated) the permission warning. Pure rendering — token state and the
 * start/stop actions are hoisted to the caller.
 */
@Composable
private fun AgentHeader(
    running: Boolean,
    startEnabled: Boolean,
    address: String,
    keepScreenOnNotice: String?,
    token: String?,
    onTokenChange: (String) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    permissionWarning: String?,
    onRequestPermissionSettings: (() -> Unit)?,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text("RemoteBLE Agent", style = MaterialTheme.typography.titleLarge)
        Button(
            enabled = running || startEnabled,
            onClick = { if (running) onStop() else onStart() },
        ) {
            Text(if (running) "Stop" else "Start")
        }
    }
    Text(address, style = MaterialTheme.typography.bodyMedium)
    if (running && keepScreenOnNotice != null) {
        Text(keepScreenOnNotice, style = MaterialTheme.typography.bodySmall)
    }
    OutlinedTextField(
        value = token.orEmpty(),
        onValueChange = onTokenChange,
        label = { Text("Auth token (blank = none)") },
        enabled = !running,
        modifier = Modifier.fillMaxWidth(),
    )
    if (running) {
        // Surface what the agent is actually running with: the token for client operators to
        // copy, or a clear warning when it was started token-free.
        Text(
            if (token.isNullOrBlank()) "No auth token — any client can connect" else "Token: $token",
            style = MaterialTheme.typography.bodySmall,
        )
    }
    if (!startEnabled && permissionWarning != null) {
        Text(
            permissionWarning,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        if (onRequestPermissionSettings != null) {
            OutlinedButton(onClick = onRequestPermissionSettings) {
                Text("Open settings")
            }
        }
    }
}

// NOTE on keys: all three sections below feed the *same* LazyColumn (see AgentApp), so their
// item keys share one namespace. A raw client id and a raw log id are both small ints that
// collide the instant a client connects (client #1 vs log #1) — Compose throws
// "Key 1 was already used" and the UI crashes. Prefix every key with its section so they can
// never collide across sections.
private fun LazyListScope.clientsSection(clients: List<AgentMonitor.ClientDto>) {
    sectionHeader("Connected clients (${clients.size})")
    if (clients.isEmpty()) {
        item { Text("No clients connected.") }
    } else {
        items(clients, key = { "client-${it.id}" }) { c -> Text("#${c.id} · ${c.address}") }
    }
}

private fun LazyListScope.leasesSection(
    leases: List<AgentMonitor.LeaseDto>,
    onToggleExclusive: (handle: String, exclusive: Boolean) -> Unit,
) {
    sectionHeader("Peripheral ownership (${leases.size})")
    if (leases.isEmpty()) {
        item { Text("No peripherals owned. Clients can still scan.") }
    } else {
        items(leases, key = { "lease-${it.handle}" }) { lease ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("${lease.name ?: "(unnamed)"} · ${lease.handle}${if (lease.inGrace) " · releasing…" else ""}")
                OutlinedButton(onClick = { onToggleExclusive(lease.handle, !lease.exclusive) }) {
                    Text(if (lease.exclusive) "make shared" else "make exclusive")
                }
            }
        }
    }
}

private fun LazyListScope.logsSection(logs: List<AgentMonitor.LogEntry>) {
    sectionHeader("Activity log (${logs.size})")
    if (logs.isEmpty()) {
        item { Text("No activity yet.") }
    } else {
        items(logs.asReversed(), key = { "log-${it.id}" }) { log ->
            Text(log.message, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun LazyListScope.sectionHeader(title: String) {
    item {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
        )
        HorizontalDivider()
    }
}
