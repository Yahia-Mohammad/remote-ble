@file:OptIn(ExperimentalApi::class, ExperimentalUuidApi::class)

package dev.warsha.remoteble.e2e

import com.juul.kable.ExperimentalApi
import com.juul.kable.Peripheral
import com.juul.kable.read
import com.juul.kable.State
import dev.warsha.remoteble.client.BleMode
import dev.warsha.remoteble.client.DefaultAgentSession
import dev.warsha.remoteble.client.RemoteScanner
import dev.warsha.remoteble.client.TransportState
import dev.warsha.remoteble.client.WebSocketAgentTransport
import dev.warsha.remoteble.client.defaultWebSocketHttpClient
import dev.warsha.remoteble.client.peripheralFor
import dev.warsha.remoteble.protocol.AgentEvent
import dev.warsha.remoteble.protocol.BleConnState
import dev.warsha.remoteble.protocol.CborProtocolCodec
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlin.uuid.ExperimentalUuidApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

private const val DEFAULT_URL = "ws://localhost:8080/agent"
private const val DEFAULT_ADVERTISED_NAME = "RBTestPeripheral"

/** How long the link must stay Connected before the operator is asked to force the drop. */
private val STABILITY_HOLD = 8.seconds

/**
 * How long to watch for the drop after prompting; override with arg 4 (seconds).
 *
 * 60s is enough for a backend that reports the peer's disconnect promptly. On btleplug/macOS it is
 * not: neither the native `DeviceDisconnected` nor the agent's active liveness probe notices within
 * a minute (Rig A, 2026-07-28), so a longer window is needed to tell "arrives late" from "never
 * arrives until we tear the link down ourselves".
 */
private val DEFAULT_OBSERVE_WINDOW = 60.seconds

/**
 * Rig A case 2, client half (validation-plan.md): watches [Peripheral.state] across an
 * unsolicited BLE-level drop. The agent half (a real `DeviceDisconnected` event on "Force
 * disconnect all") is already confirmed (rig-a-evidence.md case 2); what's unverified is
 * whether that propagates all the way to the client reaching [State.Disconnected] — the available
 * runners before this one only watched *transport* (WebSocket) state, which a BLE-level drop
 * correctly leaves untouched.
 *
 *   ./gradlew :e2e-runner:peripheralStateRun --args "ws://localhost:8080/agent"
 *
 * args: [ws-url] [token] [advertised-name] (token also read from REMOTE_BLE_TOKEN).
 *
 * **Why this reports three outcomes, not two.** Attempt 2 on 2026-07-28 "passed" in ~10s without a
 * genuine drop: the *agent's own* liveness probe had timed the connection out and declared an
 * unsolicited disconnect, which reaches the client as the same `ConnectionState(DISCONNECTED)`.
 * `Peripheral.state` alone cannot tell the two apart — [com.juul.kable.State.Disconnected] carries
 * no cause on this path. The wire event can: the agent's native drop stream forwards the radio's
 * own reason, while the polling fallback passes `null` (`PeripheralRegistry.onUnsolicitedDisconnect`
 * — "fast, carries a reason ... or from its cached-state / active-liveness polling (fallback)").
 * So this watches `session.events()` alongside `Peripheral.state` and reports INCONCLUSIVE rather
 * than PASS when the cause is absent, instead of banking a result the rig didn't actually produce.
 *
 * **The reason-based check only discriminates against the Kotlin agent.** `agent-rs` hardcodes
 * `reason: Some("peer disconnected")` inside `report_unsolicited_disconnect`, which is the single
 * path used by *both* its native event handler and its liveness prober — so a non-null reason there
 * proves nothing about which detector fired. To attribute a PASS on `agent-rs`, read its log at
 * `--log-level debug` and look for `btleplug event received: DeviceDisconnected(...)`. Confirmed
 * twice on Rig A, 2026-07-28.
 *
 * **Expect ~15-20s, not instant.** Android's `cancelConnection()` does not send a clean link-layer
 * terminate for a connection the central established, so macOS notices via supervision timeout
 * rather than a terminate indication. Keep the observation window well above that.
 */
fun main(args: Array<String>): Unit = runBlocking {
    val url = args.getOrNull(0) ?: DEFAULT_URL
    val token = args.getOrNull(1)?.ifBlank { null } ?: System.getenv("REMOTE_BLE_TOKEN")
    // Override for when macOS's advertisement-name cache is showing a stale name for this
    // peripheral identity (see docs/rig-a-evidence.md's operational notes) — connect by
    // whatever name the scan actually reports, independent of which app is really running.
    val advertisedName = args.getOrNull(2)?.ifBlank { null } ?: DEFAULT_ADVERTISED_NAME
    val observeWindow = args.getOrNull(3)?.toIntOrNull()?.seconds ?: DEFAULT_OBSERVE_WINDOW
    // Arg 5: link-probe interval in seconds; 0 disables it. Disabling matters as a *control* — the
    // probe's GATT read can itself demand pairing on an unbonded link, and a rejected pairing tears
    // the connection down, which would masquerade as the drop under test (Rig A, 2026-07-28).
    val probeInterval = (args.getOrNull(4)?.toIntOrNull() ?: 10).seconds

    println("== RemoteBle peripheral-state observer (Rig A case 2) ==")
    println("agent : $url")
    println("device: \"$advertisedName\"  token=${if (token != null) "set" else "none"}")

    val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    val http = defaultWebSocketHttpClient()
    val session = DefaultAgentSession(
        WebSocketAgentTransport(url, scope, http, authToken = { token }),
        CborProtocolCodec(),
        scope,
    )
    var peripheral: Peripheral? = null
    var reachedDisconnected = false
    var disconnectEvent: AgentEvent.ConnectionState? = null
    try {
        withTimeoutOrNull(15.seconds) { session.transportState.first { it == TransportState.CONNECTED } }
            ?: error("transport never connected")
        println("transport connected; scanning for \"$advertisedName\"...")
        val adv = withTimeoutOrNull(30.seconds) {
            RemoteScanner(session).advertisements.first { it.name == advertisedName }
        } ?: error("scan timed out")
        val p = peripheralFor(BleMode.REMOTE, adv, session).also { peripheral = it }
        println("connecting...")
        p.connect()

        val stateWatcher = scope.launch {
            p.state.onEach { println("state -> $it") }.collect { if (it is State.Disconnected) reachedDisconnected = true }
        }
        // The wire event alongside the state, purely to recover the *cause* the state drops. See
        // this file's header: a null reason means the agent's liveness poll declared the drop, not
        // the radio, which is the false positive that spoiled attempt 2.
        val causeWatcher = scope.launch {
            session.events()
                .filterIsInstance<AgentEvent.ConnectionState>()
                .filter { it.device == adv.handle && it.state == BleConnState.DISCONNECTED }
                .collect { event ->
                    println("wire event -> DISCONNECTED reason=${event.reason ?: "<none>"}")
                    if (disconnectEvent == null) disconnectEvent = event
                }
        }

        // Don't prompt until the link has proven stable: a connection that was already collapsing
        // makes whatever happens next unattributable (attempt 3's lesson).
        print("holding for ${STABILITY_HOLD.inWholeSeconds}s to confirm the link is stable... ")
        kotlinx.coroutines.delay(STABILITY_HOLD)
        if (reachedDisconnected) {
            println()
            println("INCONCLUSIVE: the link dropped on its own before the prompt — the radio was not stable.")
            println("Recover the Mac's Bluetooth stack (toggle Bluetooth, check for stray bonds) and retry.")
            exitProcess(2)
        }
        println("stable.")

        // Link-liveness probe. Without this the run cannot distinguish "the central failed to notice
        // a dead link" from "the link was never actually killed" — and those have opposite
        // conclusions. A GATT read is unambiguous: it can only succeed over a live connection.
        // Rig A, 2026-07-28: Android's BluetoothGattServer.cancelConnection() drops the *server's*
        // reference without necessarily terminating the link a remote central established, so the
        // peripheral app can report CentralDisconnected while the radio link is still up.
        val readable = p.services.value
            ?.flatMap { it.characteristics }
            ?.firstOrNull { it.properties.read }
        val probe = scope.launch {
            if (probeInterval.inWholeSeconds <= 0L) {
                println("link probe: disabled (control run — no GATT reads, so no pairing can be provoked)")
                return@launch
            }
            if (readable == null) {
                println("link probe: no readable characteristic; skipping")
                return@launch
            }
            while (true) {
                kotlinx.coroutines.delay(probeInterval)
                val outcome = runCatching { p.read(readable) }
                println(
                    "link probe: " + if (outcome.isSuccess) {
                        "read OK — the radio link is STILL UP"
                    } else {
                        "read failed (${outcome.exceptionOrNull()?.message}) — the link is down"
                    },
                )
            }
        }

        println()
        println(">>> Now trigger the drop on the phone (within ${observeWindow.inWholeSeconds}s) <<<")
        val promptedAt = TimeSource.Monotonic.markNow()
        val ok = withTimeoutOrNull(observeWindow) {
            while (!reachedDisconnected) kotlinx.coroutines.delay(200)
            true
        }
        probe.cancel()
        val elapsed = promptedAt.elapsedNow()
        stateWatcher.cancel()
        causeWatcher.cancel()
        println()

        val cause = disconnectEvent?.reason
        when {
            ok != true -> {
                println("FAILED: client never reached State.Disconnected within ${observeWindow.inWholeSeconds}s")
                exitProcess(1)
            }
            cause == null -> {
                // Either no wire event was seen at all, or one arrived with no cause — both mean the
                // radio's own DeviceDisconnected is not what we observed.
                println("INCONCLUSIVE after $elapsed: the client did reach State.Disconnected, but the")
                println("agent reported no cause, so this is the liveness-poll fallback rather than a")
                println("native BLE drop. Case 2 asks for the native path — this does not close it.")
                println("Re-run with a longer REMOTE_BLE_LIVENESS_PROBE_MS so the poll can't fire first.")
                exitProcess(2)
            }
            else -> {
                println("PASS after $elapsed: client reached State.Disconnected on an unsolicited BLE drop")
                println("  agent-reported cause: $cause")
                println("  NOTE: against agent-rs the cause is hardcoded and does NOT prove the native")
                println("  path fired — confirm with 'DeviceDisconnected' in the agent's debug log.")
            }
        }
    } catch (t: Throwable) {
        println("FAILED: ${t.message}")
        exitProcess(1)
    } finally {
        runCatching { peripheral?.close() }
        scope.cancel()
        http.close()
    }
}
