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
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.warsha.remoteble.agent.AgentMonitor
import dev.warsha.remoteble.agent.AgentRadio
import dev.warsha.remoteble.agent.AgentRunner
import dev.warsha.remoteble.agent.AgentStartResult
import dev.warsha.remoteble.agent.di.AgentConfig
import dev.warsha.remoteble.agent.loadPersistedToken
import dev.warsha.remoteble.agent.persistToken
import dev.warsha.remoteble.protocol.BleRadioState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * A native mirror of the desktop agent's HTML status dashboard (see `Dashboard.kt`): header
 * with a start/stop control and the WebSocket address, connected-clients panel,
 * peripheral-ownership panel (exclusive-only in 0.9.0), and a scrolling activity log.
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
 * Mobile agents intentionally bind to all interfaces so their LAN address is reachable by a
 * companion client. A non-blank token is required before starting: the UI makes the unencrypted
 * LAN exposure explicit without rendering the bearer credential itself.
 */
@Composable
fun AgentApp(
    runner: AgentRunner,
    config: AgentConfig = AgentConfig(bindHost = MOBILE_LAN_BIND_HOST),
    addressLabel: (Int) -> String = { port -> "ws://<this device>:$port/agent" },
    keepScreenOnNotice: String? = null,
    startEnabled: Boolean = true,
    permissionWarning: String? = null,
    onRequestPermissionSettings: (() -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    val running by runner.running.collectAsState()
    // Observed independently of [runner]: the radio can be off while the agent is stopped, which is
    // exactly when the user is about to press Start. Null on platforms that cannot report it.
    val radioState by remember { AgentRadio.source() ?: unobservableRadio }.collectAsState()
    var snapshot by remember { mutableStateOf<AgentMonitor.Snapshot?>(null) }
    var token by remember { mutableStateOf<String?>(null) }
    var tokenEdited by remember { mutableStateOf(false) }
    // Why the last Start attempt failed, or null if it did not. Survives until the next attempt.
    var startFailure by remember { mutableStateOf<String?>(null) }

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

    // Start the LAN-exposed agent with its required credential.
    val startWith: (String?) -> Unit = { chosen ->
        val effectiveToken = chosen?.takeIf { it.isNotBlank() }
        token = effectiveToken
        // Persist on Start, not per keystroke: only records what the agent actually ran with.
        scope.launch { persistToken(effectiveToken) }
        scope.launch {
            // The result was previously discarded, which meant a failed Start was indistinguishable
            // from a Start that did nothing: the button simply stayed on "Start". Now that a bind
            // failure is reportable at all (it used to kill the process), it has to be reported.
            startFailure = null
            startFailure = (runner.start(config.copy(authToken = effectiveToken)) as? AgentStartResult.Failed)?.message
        }
    }
    val onStart: () -> Unit = {
        if (!token.isNullOrBlank()) startWith(token)
    }
    val onStop: () -> Unit = {
        startFailure = null
        scope.launch { runner.stop() }
    }

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
                        startEnabled = startEnabled && !token.isNullOrBlank(),
                        address = if (running) addressLabel(config.port) else "Stopped",
                        keepScreenOnNotice = keepScreenOnNotice,
                        token = token,
                        onTokenChange = { tokenEdited = true; token = it },
                        onStart = onStart,
                        onStop = onStop,
                        permissionWarning = permissionWarning,
                        onRequestPermissionSettings = onRequestPermissionSettings,
                        radioNotice = radioNoticeFor(radioState),
                        startFailure = startFailure,
                    )
                }

                val s = snapshot
                if (s == null) {
                    item { Text("No activity yet.", style = MaterialTheme.typography.bodySmall) }
                } else {
                    clientsSection(s.clients)
                    leasesSection(s.leases)
                    logsSection(s.logs)
                }
            }
        }

    }
}

/** Mobile's explicit LAN-serving default; desktop/headless defaults stay loopback-only. */
private const val MOBILE_LAN_BIND_HOST = "0.0.0.0"

/**
 * Stand-in for a platform that cannot observe its radio, so the composable collects one flow either
 * way. `null` (rather than [BleRadioState.UNKNOWN]) because the two mean different things and only
 * this one must stay silent forever: `UNKNOWN` is a state the platform reported.
 */
// internal (not private): the iOS entry point derives its permission gate from the same source, and
// needs the same "this platform cannot tell" stand-in when there is nothing to observe.
internal val unobservableRadio: StateFlow<BleRadioState?> = MutableStateFlow(null)

/**
 * Header panel: the title, Start/Stop control, agent address, masked auth-token field, and
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
    radioNotice: String?,
    startFailure: String?,
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
        label = { Text("Auth token (required for LAN access)") },
        visualTransformation = PasswordVisualTransformation(),
        enabled = !running,
        modifier = Modifier.fillMaxWidth(),
    )
    if (running) {
        Text(
            "LAN exposure over unencrypted ws://. Clients need the configured bearer credential.",
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
    // Shown whether or not the agent is running, and independently of the permission warning above:
    // an adapter that is switched off and a permission that was never granted are different
    // failures with different fixes, and treating them as one is what hid this on Android (Rig B
    // case 6). A scan with the radio off succeeds and finds nothing, so without this line the UI
    // is as silent as the wire was.
    radioNotice?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
    // Shown only while stopped: once a later Start succeeds this is cleared, and a stale reason
    // next to a running agent would be worse than no reason at all.
    if (!running) {
        startFailure?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

/**
 * The user-facing sentence for a radio state, or `null` when there is nothing to say — the radio
 * is fine, or this platform cannot tell (in which case claiming anything would be a guess).
 *
 * [BleRadioState.UNKNOWN] is deliberately silent too: on Apple it is the normal pre-initialisation
 * value for the first moments after launch, and flashing "Bluetooth unavailable" during startup
 * would train users to ignore the line that matters.
 */
internal fun radioNoticeFor(state: BleRadioState?): String? = when (state) {
    BleRadioState.OFF -> "Bluetooth is off. Scans will find nothing until it is switched on."
    BleRadioState.UNAUTHORIZED -> "Bluetooth permission is denied for this app."
    BleRadioState.UNSUPPORTED -> "This device has no Bluetooth Low Energy radio."
    BleRadioState.ON, BleRadioState.UNKNOWN, null -> null
}

/**
 * Whether [state] is evidence that this app's Bluetooth **permission** is denied — the one radio
 * condition that should gate the Start button, and the Apple analogue of Android's runtime-permission
 * check in `MainActivity`.
 *
 * Deliberately narrow, and the exclusions are the point:
 * - [BleRadioState.OFF] does **not** gate. Android does not gate Start on the adapter being off
 *   either, and it should not: the user can switch Bluetooth on without leaving the app, the agent
 *   is still a working server in the meantime, and since 0.10.0 a client asking it to scan gets a
 *   typed `RADIO_OFF` rather than silence. [radioNoticeFor] says so on screen; that is the right
 *   weight of response.
 * - [BleRadioState.UNKNOWN] and `null` do not gate, because absence of evidence is not denial. On
 *   Apple, `UNKNOWN` is the normal value for the first moments after launch, so gating on it would
 *   disable Start on every cold start until the delegate fires.
 * - [BleRadioState.UNSUPPORTED] does not gate: nothing the user can do in Settings fixes a device
 *   with no BLE radio, so offering them a route there would be a dead end. The notice covers it.
 */
internal fun bluetoothPermissionDenied(state: BleRadioState?): Boolean =
    state == BleRadioState.UNAUTHORIZED

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

private fun LazyListScope.leasesSection(leases: List<AgentMonitor.LeaseDto>) {
    sectionHeader("Peripheral ownership (${leases.size})")
    if (leases.isEmpty()) {
        item { Text("No peripherals owned. Clients can still scan.") }
    } else {
        items(leases, key = { "lease-${it.handle}" }) { lease ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("${lease.name ?: "(unnamed)"} · ${lease.handle}${if (lease.inGrace) " · releasing…" else ""} · exclusive")
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
