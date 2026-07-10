@file:OptIn(ExperimentalApi::class, ExperimentalUuidApi::class)

package dev.warsha.remoteble.e2e

import com.juul.kable.ExperimentalApi
import com.juul.kable.Peripheral
import dev.warsha.remoteble.client.BleMode
import dev.warsha.remoteble.client.DefaultAgentSession
import dev.warsha.remoteble.client.RemotePeripheral
import dev.warsha.remoteble.client.RemoteScanner
import dev.warsha.remoteble.client.TransportState
import dev.warsha.remoteble.client.WebSocketAgentTransport
import dev.warsha.remoteble.client.awaitCapabilities
import dev.warsha.remoteble.client.defaultWebSocketHttpClient
import dev.warsha.remoteble.client.peripheralFor
import dev.warsha.remoteble.protocol.Capabilities
import dev.warsha.remoteble.protocol.CborProtocolCodec
import dev.warsha.remoteble.protocol.ConnProfile
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.ExperimentalUuidApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Connection-parameters (B / `conn.params`) live check: connect to a peripheral via a running agent
 * and request each [ConnProfile] in turn, printing whether the agent accepted it.
 *
 * `conn.params` (and its `conn.priority` alias) is honored only by an agent whose radio backend
 * implements it — Kable's **Android** `AndroidPeripheral.requestConnectionPriority`. iOS/CoreBluetooth
 * exposes no app-facing API and JVM/btleplug none through Kable, so those agents don't advertise the
 * capability and answer `UNSUPPORTED`. This driver makes that split observable:
 *
 *   - Android agent  → capabilities include `conn.params` + `conn.priority`; all three profiles `OK`.
 *   - iOS/JVM agent  → neither capability advertised; each `setConnParams` fails `UNSUPPORTED`.
 *
 *   ./gradlew :e2e-runner:connParamsRun --args "ws://localhost:8080/agent \"Warsha HRM\" <token>"
 *
 * args: [ws-url] [device-name] [token]  (token also read from REMOTE_BLE_TOKEN).
 */
fun main(args: Array<String>): Unit = runBlocking {
    val url = args.getOrNull(0) ?: "ws://localhost:8080/agent"
    val name = args.getOrNull(1) ?: "Warsha HRM"
    val token = args.getOrNull(2)?.ifBlank { null } ?: System.getenv("REMOTE_BLE_TOKEN")

    println("== RemoteBle connection-parameters (conn.params) ==")
    println("agent : $url")
    println("device: \"$name\"  token=${if (token != null) "set" else "none"}")

    val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    val http = defaultWebSocketHttpClient()
    val session = DefaultAgentSession(
        WebSocketAgentTransport(url, scope, http, authToken = { token }),
        CborProtocolCodec(),
        scope,
    )
    var peripheral: Peripheral? = null
    try {
        withTimeout(15.seconds) { session.transportState.first { it == TransportState.CONNECTED } }
        println("transport connected; negotiating capabilities...")
        val caps = withTimeout(15.seconds) { session.awaitCapabilities() }
        val hasConnParams = Capabilities.CONN_PARAMS in caps
        println("negotiated capabilities: ${caps.sorted().joinToString(", ").ifEmpty { "(none)" }}")
        println(
            "  conn.params  : ${if (hasConnParams) "ADVERTISED" else "absent"}" +
                "   conn.priority: ${if (Capabilities.CONN_PRIORITY in caps) "ADVERTISED" else "absent"}",
        )
        if (!hasConnParams) {
            println("  → this agent has no conn.params backend; expect every request below to fail UNSUPPORTED.")
        }

        println("scanning for \"$name\"...")
        val adv = withTimeout(60.seconds) {
            RemoteScanner(session).advertisements.first { it.name == name }
        }
        println("found: name=${adv.name}  advertisement RSSI=${adv.rssi} dBm")
        // conn.params is a RemoteBLE extension beyond Kable's Peripheral surface — reach it via the
        // concrete RemotePeripheral (peripheralFor returns the common Peripheral type).
        val p = peripheralFor(BleMode.REMOTE, adv, session).also { peripheral = it } as RemotePeripheral
        println("connecting (accept the pairing prompt on BOTH devices if it appears)...")
        withTimeout(30.seconds) { p.connect() }
        println("connected — requesting each ConnProfile (watch the peripheral's connection-interval log):")

        var allOk = true
        // LOW_POWER last so the link is left on a battery-friendly interval after the run.
        for (profile in listOf(ConnProfile.LOW_LATENCY, ConnProfile.BALANCED, ConnProfile.LOW_POWER)) {
            val outcome = runCatching { p.setConnParams(profile) }
            outcome.fold(
                onSuccess = { println("  setConnParams(%-11s) ... OK".format(profile)) },
                onFailure = {
                    allOk = false
                    println("  setConnParams(%-11s) ... ERR: %s".format(profile, it.message))
                },
            )
            delay(1500)
        }

        val verdict = when {
            hasConnParams && allOk -> "PASS — agent advertised conn.params and accepted every profile."
            !hasConnParams && !allOk -> "PASS — no conn.params backend; every request degraded to UNSUPPORTED cleanly."
            else -> "MIXED — capability advertisement and request outcomes disagree; investigate."
        }
        println(verdict)
    } catch (t: Throwable) {
        println("FAILED: ${t.message}")
    } finally {
        runCatching { peripheral?.disconnect() }
        runCatching { peripheral?.close() }
        scope.cancel()
        http.close()
    }
}
