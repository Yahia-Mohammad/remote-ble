@file:OptIn(ExperimentalApi::class, ExperimentalUuidApi::class)

package dev.warsha.remoteble.e2e

import com.juul.kable.ExperimentalApi
import com.juul.kable.Peripheral
import com.juul.kable.State
import dev.warsha.remoteble.client.BleMode
import dev.warsha.remoteble.client.DefaultAgentSession
import dev.warsha.remoteble.client.RemoteScanner
import dev.warsha.remoteble.client.TransportState
import dev.warsha.remoteble.client.WebSocketAgentTransport
import dev.warsha.remoteble.client.defaultWebSocketHttpClient
import dev.warsha.remoteble.client.peripheralFor
import dev.warsha.remoteble.protocol.CborProtocolCodec
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.ExperimentalUuidApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

private const val DEFAULT_URL = "ws://localhost:8080/agent"
private const val DEFAULT_ADVERTISED_NAME = "RBTestPeripheral"

/**
 * Rig A case 2, client half (pr8-validation-plan.md): watches [Peripheral.state] across an
 * unsolicited BLE-level drop. The agent half (a real `DeviceDisconnected` event on "Force
 * disconnect all") is already confirmed (pr8-rig-a-evidence.md case 2); what's unverified is
 * whether that propagates all the way to the client reaching [State.Disconnected] — the available
 * runners before this one only watched *transport* (WebSocket) state, which a BLE-level drop
 * correctly leaves untouched.
 *
 *   ./gradlew :e2e-runner:peripheralStateRun --args "ws://localhost:8080/agent"
 *
 * args: [ws-url] [token] (token also read from REMOTE_BLE_TOKEN).
 */
fun main(args: Array<String>): Unit = runBlocking {
    val url = args.getOrNull(0) ?: DEFAULT_URL
    val token = args.getOrNull(1)?.ifBlank { null } ?: System.getenv("REMOTE_BLE_TOKEN")
    // Override for when macOS's advertisement-name cache is showing a stale name for this
    // peripheral identity (see docs/pr8-rig-a-evidence.md's operational notes) — connect by
    // whatever name the scan actually reports, independent of which app is really running.
    val advertisedName = args.getOrNull(2)?.ifBlank { null } ?: DEFAULT_ADVERTISED_NAME

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

        println()
        println(">>> Now tap 'Force disconnect all' on the phone (within 60s) <<<")
        val ok = withTimeoutOrNull(60.seconds) {
            while (!reachedDisconnected) kotlinx.coroutines.delay(200)
            true
        }
        stateWatcher.cancel()

        if (ok == true) {
            println()
            println("PASS: client reached State.Disconnected after the unsolicited BLE-level drop")
        } else {
            println()
            println("FAILED: client never reached State.Disconnected within 60s")
            exitProcess(1)
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
