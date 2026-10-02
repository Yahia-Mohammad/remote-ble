package dev.warsha.remoteble.agent.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import dev.warsha.remoteble.agent.AgentMonitor
import dev.warsha.remoteble.agent.AgentRadio
import dev.warsha.remoteble.agent.AgentRunner
import dev.warsha.remoteble.agent.AgentStartResult
import dev.warsha.remoteble.agent.AgentTls
import dev.warsha.remoteble.agent.AgentTlsProvider
import dev.warsha.remoteble.agent.LogTags
import dev.warsha.remoteble.agent.di.AgentConfig
import dev.warsha.remoteble.agent.AgentSecret
import dev.warsha.remoteble.agent.loadPersistedToken
import dev.warsha.remoteble.agent.lanIPv4Address
import dev.warsha.remoteble.agent.loadEncryptPreference
import dev.warsha.remoteble.agent.persistEncryptPreference
import dev.warsha.remoteble.agent.persistToken
import dev.warsha.remoteble.log.Logger
import dev.warsha.remoteble.protocol.AgentPairing
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
 * [addressLabel] is how to reach this agent on a port with a scheme, `ws` or `wss` (e.g.
 * `"wss://192.168.1.23:8080/agent"`) — resolving the device's LAN IP is platform-specific, so the
 * caller supplies it rather than this shared composable owning networking APIs. [tls], if non-null,
 * is the platform's identity store, and offers the encrypted mode. [keepScreenOnNotice], if non-null, is shown whenever the
 * agent is running (iOS: reminds the user the agent stops the moment the app backgrounds/locks).
 * [startEnabled] gates the Start button (e.g. on required runtime permissions); when `false`,
 * [permissionWarning] explains why and [onRequestPermissionSettings], if supplied, renders a
 * button routing to the app's settings page. [localNetworkWarning], if non-null, says the platform
 * blocks LAN clients; it does not gate Start, since the agent still serves this device's loopback,
 * and [onRequestLocalNetworkSettings] routes to the fix.
 *
 * Mobile agents intentionally bind to all interfaces so their LAN address is reachable by a
 * companion client. A non-blank token is required before starting: the UI makes the LAN exposure
 * explicit, says whether it is encrypted, and keeps the bearer credential masked unless the user
 * asks to see it.
 */
@Composable
fun AgentApp(
    runner: AgentRunner,
    config: AgentConfig = AgentConfig(bindHost = MOBILE_LAN_BIND_HOST),
    addressLabel: (port: Int, scheme: String) -> String = { port, scheme -> "$scheme://<this device>:$port/agent" },
    keepScreenOnNotice: String? = null,
    startEnabled: Boolean = true,
    permissionWarning: String? = null,
    onRequestPermissionSettings: (() -> Unit)? = null,
    localNetworkWarning: String? = null,
    onRequestLocalNetworkSettings: (() -> Unit)? = null,
    tls: AgentTlsProvider? = null,
) {
    val scope = rememberCoroutineScope()
    val running by runner.running.collectAsState()
    // Observed independently of [runner]: the radio can be off while the agent is stopped, which is
    // exactly when the user is about to press Start. Null on platforms that cannot report it.
    val radioState by remember { AgentRadio.source() ?: unobservableRadio }.collectAsState()
    var snapshot by remember { mutableStateOf<AgentMonitor.Snapshot?>(null) }
    var token by remember { mutableStateOf<String?>(null) }
    var tokenEdited by remember { mutableStateOf(false) }
    var operatorToken by remember { mutableStateOf<String?>(null) }
    var operatorTokenEdited by remember { mutableStateOf(false) }
    // Off by default, and deliberately not persisted: opening a cleartext high-privilege plane to the
    // network should be a decision made per run, not one inherited silently from a previous session.
    var allowRemoteDashboard by remember { mutableStateOf(false) }
    // Why the last Start attempt failed, or null if it did not. Survives until the next attempt.
    var startFailure by remember { mutableStateOf<String?>(null) }
    // On by default (docs/proposals/agent-transport-encryption.md, phase 5): every client can pin,
    // and pairing hands it the fingerprint. Turning it off is remembered, and visible on screen.
    var encrypt by remember { mutableStateOf(tls != null) }
    var encryptEdited by remember { mutableStateOf(false) }
    // Loaded when encryption is switched on, so the fingerprint can be read before Start.
    val identities = remember(tls) { AgentIdentityLoader(tls) }
    // The scheme the running agent was started with, which a later toggle must not misreport.
    var servingTls by remember { mutableStateOf(false) }

    val loadIdentity: suspend (Boolean) -> AgentTls? = identities::load
    LaunchedEffect(encrypt) {
        if (encrypt && identities.identity == null) loadIdentity(false)
    }

    LaunchedEffect(Unit) {
        val persisted = loadPersistedToken()
        val persistedOperator = loadPersistedToken(AgentSecret.OPERATOR_TOKEN)
        // Don't clobber a value the user may have started typing while this async load was still
        // in flight — only seed the field from persistence if it's still untouched and empty.
        if (!tokenEdited && token.isNullOrBlank()) token = persisted
        if (!operatorTokenEdited && operatorToken.isNullOrBlank()) operatorToken = persistedOperator
        if (tls != null && !encryptEdited) loadEncryptPreference()?.let { encrypt = it }
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
        val effectiveOperator = operatorToken?.takeIf { it.isNotBlank() }
        token = effectiveToken
        // Persist on Start, not per keystroke: only records what the agent actually ran with.
        scope.launch { persistToken(effectiveToken) }
        scope.launch { persistToken(effectiveOperator, AgentSecret.OPERATOR_TOKEN) }
        scope.launch {
            // The result was previously discarded, which meant a failed Start was indistinguishable
            // from a Start that did nothing: the button simply stayed on "Start". Now that a bind
            // failure is reportable at all (it used to kill the process), it has to be reported.
            startFailure = null
            // Never falls back to cleartext: an agent the user asked to encrypt does not start bare.
            val front = if (encrypt) {
                (identities.identity ?: loadIdentity(false))?.front ?: run {
                    startFailure = identities.failure
                    return@launch
                }
            } else {
                null
            }
            servingTls = front != null
            startFailure = (
                runner.start(
                    config.copy(
                        authToken = effectiveToken,
                        operatorToken = effectiveOperator,
                        allowRemoteDashboard = effectiveOperator != null && allowRemoteDashboard,
                        tlsFront = front,
                    ),
                ) as? AgentStartResult.Failed
                )?.message
        }
    }
    val onStart: () -> Unit = {
        // Checked here rather than left to `AgentWebSocketServer.init`'s `require`. That require does
        // fire — `AgentRunner.start` catches Throwable from graph construction, so it cannot crash the
        // app — but it is reported through the deliberately non-specific failure path, which would tell
        // the user only that starting failed. This says which field to change.
        val clash = !operatorToken.isNullOrBlank() && operatorToken == token
        if (clash) {
            startFailure = "The operator token must differ from the auth token — they are separate " +
                "credentials with different privileges."
        } else if (!token.isNullOrBlank()) {
            startWith(token)
        }
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
            LazyColumn(
                modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item {
                    AgentHeader(
                        running = running,
                        startEnabled = startEnabled && !token.isNullOrBlank(),
                        address = if (running) addressLabel(config.port, if (servingTls) "wss" else "ws") else "Stopped",
                        // While stopped, what the next Start will serve; while running, what it does.
                        encrypted = if (running) servingTls else encrypt,
                        keepScreenOnNotice = keepScreenOnNotice,
                        token = token,
                        onTokenChange = { tokenEdited = true; token = it },
                        operatorToken = operatorToken,
                        onOperatorTokenChange = { operatorTokenEdited = true; operatorToken = it },
                        allowRemoteDashboard = allowRemoteDashboard,
                        onAllowRemoteDashboardChange = { allowRemoteDashboard = it },
                        onStart = onStart,
                        onStop = onStop,
                        permissionWarning = permissionWarning,
                        onRequestPermissionSettings = onRequestPermissionSettings,
                        localNetworkWarning = localNetworkWarning,
                        onRequestLocalNetworkSettings = onRequestLocalNetworkSettings,
                        radioNotice = radioNoticeFor(radioState),
                        startFailure = startFailure,
                    )
                }
                if (tls != null) {
                    item {
                        EncryptionPanel(
                            running = running,
                            encrypt = encrypt,
                            onEncryptChange = { choice ->
                                encryptEdited = true
                                encrypt = choice
                                scope.launch { persistEncryptPreference(choice) }
                            },
                            fingerprint = identities.identity?.fingerprint?.toString(),
                            certificateSha256 = identities.identity?.certificateSha256,
                            failure = identities.failure,
                            onReset = { scope.launch { loadIdentity(true) } },
                        )
                    }
                }

                if (running) {
                    item {
                        val host = lanIPv4Address()
                        val runningToken = token
                        PairingPanel(
                            pairing = if (host != null && runningToken != null) {
                                AgentPairing(
                                    host = host,
                                    port = config.port,
                                    token = runningToken,
                                    fingerprint = identities.identity?.fingerprint?.takeIf { servingTls },
                                )
                            } else {
                                null
                            },
                            unavailable = if (host == null) "No Wi-Fi or LAN address to pair over." else null,
                        )
                    }
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
    encrypted: Boolean,
    keepScreenOnNotice: String?,
    token: String?,
    onTokenChange: (String) -> Unit,
    operatorToken: String?,
    onOperatorTokenChange: (String) -> Unit,
    allowRemoteDashboard: Boolean,
    onAllowRemoteDashboardChange: (Boolean) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    permissionWarning: String?,
    onRequestPermissionSettings: (() -> Unit)?,
    localNetworkWarning: String?,
    onRequestLocalNetworkSettings: (() -> Unit)?,
    radioNotice: String?,
    startFailure: String?,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
        SecretField(
            value = token.orEmpty(),
            onValueChange = onTokenChange,
            label = "Auth token",
            supportingText = "Required for LAN access",
            enabled = !running,
        )
        if (running) {
            Text(
                if (encrypted) {
                    "LAN exposure over encrypted wss://. Clients need the bearer credential and this " +
                        "agent's fingerprint."
                } else {
                    "LAN exposure over unencrypted ws://. Clients need the configured bearer credential."
                },
                style = MaterialTheme.typography.bodySmall,
            )
            // Every client must present this token character for character, and retyping it is where
            // the typos come from; pasting it removes the chance.
            if (!token.isNullOrBlank()) CopyTokenButton(token)
        }
        // Optional second secret, and deliberately a separate field rather than a reuse of the one above.
        // The dashboard exposes every client's address, every lease and the activity log — the
        // cross-client information the op plane refuses to give a client — so sharing one token would
        // make every client an observer of all the others. `AgentWebSocketServer.init` requires them to
        // differ. Left blank (the default) nothing changes: no operator credential, no dashboard routes,
        // and `/` keeps answering 404, which is the pre-0.10.0 behaviour.
        SecretField(
            value = operatorToken.orEmpty(),
            onValueChange = onOperatorTokenChange,
            label = "Operator token (optional)",
            supportingText = "Enables the status dashboard",
            enabled = !running,
        )
        // Shown only once an operator token is present, because the choice is meaningless without one.
        // Default off: the dashboard is the high-privilege plane, unencrypted unless the agent is, so it
        // answers only this device unless the operator explicitly opens it up — the same posture
        // `Main.kt` takes for a non-loopback bind. Reach it from the phone's own browser, or tunnel over USB.
        if (!operatorToken.isNullOrBlank()) {
            SwitchRow("Allow dashboard from other devices", allowRemoteDashboard, onAllowRemoteDashboardChange, enabled = !running)
            Text(
                when {
                    !allowRemoteDashboard ->
                        "Dashboard is limited to this device. Reach it from this phone's browser, or tunnel: " +
                            "adb forward tcp:8080 tcp:8080 (Android) / iproxy (iOS)."
                    // Browsers cannot pin, so the first visit warns about the self-signed certificate,
                    // and accepting it is the operator's call.
                    encrypted ->
                        "Dashboard reachable from the network over https://. Browsers warn about this " +
                            "agent's self-signed certificate on the first visit."
                    else ->
                        "Dashboard reachable from the network over unencrypted http:// — anyone on it can " +
                            "capture the operator token and read every client, lease and log line."
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (allowRemoteDashboard && !encrypted) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
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
        // Shown whether or not the agent is running: the listener starts fine without this permission,
        // so the only symptom a LAN client would otherwise see is a connect timeout.
        if (localNetworkWarning != null) {
            Text(
                localNetworkWarning,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            if (onRequestLocalNetworkSettings != null) {
                OutlinedButton(onClick = onRequestLocalNetworkSettings) {
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
}

/**
 * The phone agent's identity as the UI shows it: the one loaded, or why loading failed. Loading may
 * create the key, and both block on the key store, so it runs off the UI thread inside the provider.
 * A failure is shown rather than thrown: the agent can still run without encryption.
 */
internal class AgentIdentityLoader(private val provider: AgentTlsProvider?) {
    var identity: AgentTls? by mutableStateOf(null)
        private set
    var failure: String? by mutableStateOf(null)
        private set

    suspend fun load(reset: Boolean): AgentTls? {
        if (provider == null) return null
        return try {
            provider.load(reset).also { identity = it; failure = null }
        } catch (e: Exception) {
            Logger.error(LogTags.AGENT, e) { "could not load the agent identity" }
            failure = "Could not load this agent's identity; check the local log."
            // A reset may have deleted the old key before failing, so the identity on screen could
            // be one that no longer exists; Start loads afresh instead.
            if (reset) identity = null
            null
        }
    }
}

/** A setting and its switch, centred on one line; the label takes the width the switch leaves. */
@Composable
private fun SwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit, enabled: Boolean) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(end = 12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

/**
 * The encryption switch, this agent's fingerprint, and the control that replaces its identity.
 *
 * The fingerprint is selectable rather than behind a Copy button: it is not a secret, and a client
 * needs it exactly, so pasting beats retyping 64 hex digits. A reset is confirmed first, because it
 * breaks every client already pinned to this agent until each is given the new fingerprint.
 */
@Composable
private fun EncryptionPanel(
    running: Boolean,
    encrypt: Boolean,
    onEncryptChange: (Boolean) -> Unit,
    fingerprint: String?,
    certificateSha256: String?,
    failure: String?,
    onReset: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        var confirmReset by remember { mutableStateOf(false) }
        SwitchRow("Encrypt connections (wss://)", encrypt, onEncryptChange, enabled = !running)
        if (!encrypt) {
            Text(
                "Off: clients connect over cleartext ws://, which anyone on this network can read, token " +
                    "included. Turn it on unless a tunnel or proxy encrypts the connection instead.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            return
        }
        failure?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        if (fingerprint != null) {
            Text("Agent fingerprint — clients pin this:", style = MaterialTheme.typography.bodySmall)
            SelectionContainer {
                Text(fingerprint, style = MaterialTheme.typography.bodySmall)
            }
            if (certificateSha256 != null) {
                // Only the dashboard needs it: a browser cannot pin, and its warning names this instead.
                Text("Certificate SHA-256, as a browser shows it for the dashboard:", style = MaterialTheme.typography.bodySmall)
                SelectionContainer {
                    Text(certificateSha256, style = MaterialTheme.typography.bodySmall)
                }
            }
            if (!running) {
                TextButton(onClick = { confirmReset = true }) { Text("New identity") }
            }
        }
        if (confirmReset) {
            AlertDialog(
                onDismissRequest = { confirmReset = false },
                title = { Text("Replace this agent's identity?") },
                text = {
                    Text(
                        "Every client paired with this agent will refuse to connect until it is given " +
                            "the new fingerprint.",
                    )
                },
                confirmButton = {
                    TextButton(onClick = { confirmReset = false; onReset() }) { Text("Replace") }
                },
                dismissButton = {
                    TextButton(onClick = { confirmReset = false }) { Text("Cancel") }
                },
            )
        }
    }
}

/**
 * A credential field, masked by default with a Show/Hide control. A token has to match on every
 * client character for character, and a typo behind a mask is invisible until a client is refused
 * with a 401 the user cannot explain. The control stays usable while the field is locked by a
 * running agent, so the value in force can still be read back.
 */
@Composable
private fun SecretField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    enabled: Boolean,
    supportingText: String? = null,
) {
    var revealed by rememberSaveable { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        // One line: a floating label that wraps is drawn across the field above it.
        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingText = supportingText?.let { { Text(it) } },
        visualTransformation = if (revealed) VisualTransformation.None else PasswordVisualTransformation(),
        // Password keyboards neither autocorrect nor learn the value, which matters more once it
        // can be shown in the clear.
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        singleLine = true,
        trailingIcon = {
            TextButton(onClick = { revealed = !revealed }) {
                Text(if (revealed) "Hide" else "Show")
            }
        },
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun CopyTokenButton(token: String) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(2_000)
            copied = false
        }
    }
    TextButton(onClick = {
        scope.launch {
            clipboard.setClipEntry(secretClipEntry(token))
            copied = true
        }
    }) {
        Text(if (copied) "Copied" else "Copy token")
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
