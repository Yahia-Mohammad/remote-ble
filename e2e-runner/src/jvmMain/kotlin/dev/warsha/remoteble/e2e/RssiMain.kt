@file:OptIn(ExperimentalApi::class, ExperimentalUuidApi::class)

package dev.warsha.remoteble.e2e

import com.juul.kable.ExperimentalApi
import com.juul.kable.Peripheral
import dev.warsha.remoteble.client.BleMode
import dev.warsha.remoteble.client.DefaultAgentSession
import dev.warsha.remoteble.client.RemoteScanner
import dev.warsha.remoteble.client.TransportState
import dev.warsha.remoteble.client.WebSocketAgentTransport
import dev.warsha.remoteble.client.defaultWebSocketHttpClient
import dev.warsha.remoteble.client.peripheralFor
import dev.warsha.remoteble.protocol.CborProtocolCodec
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
 * Connected-RSSI (F2) live check: connect to a peripheral via a running agent and print the
 * connected link RSSI once a second so you can watch it change as the peripheral moves.
 *
 * A live connected read needs an agent whose radio backend implements it (Kable **Android**
 * `readRemoteRssi()` / **Apple** `readRSSI()`); the JVM/btleplug and agent-rs backends don't
 * advertise the `rssi` capability, so `rssi()` there fails fast with UNSUPPORTED — which is the
 * point of the capability gating.
 *
 *   ./gradlew :e2e-runner:rssiRun --args "ws://localhost:8080/agent \"Warsha HRM\" <token> 120"
 *
 * args: [ws-url] [device-name] [token] [sample-count]  (token also read from REMOTE_BLE_TOKEN).
 */
fun main(args: Array<String>): Unit = runBlocking {
    val url = args.getOrNull(0) ?: "ws://localhost:8080/agent"
    val name = args.getOrNull(1) ?: "Warsha HRM"
    val token = args.getOrNull(2)?.ifBlank { null } ?: System.getenv("REMOTE_BLE_TOKEN")
    val samples = args.getOrNull(3)?.toIntOrNull() ?: 120

    println("== RemoteBle connected-RSSI (F2) ==")
    println("agent : $url")
    println("device: \"$name\"  token=${if (token != null) "set" else "none"}  samples=$samples")

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
        println("transport connected; scanning for \"$name\"...")
        val adv = withTimeout(60.seconds) {
            RemoteScanner(session).advertisements.first { it.name == name }
        }
        println("found: name=${adv.name}  advertisement RSSI=${adv.rssi} dBm")
        val p = peripheralFor(BleMode.REMOTE, adv, session).also { peripheral = it }
        println("connecting (accept the pairing prompt on BOTH devices if it appears)...")
        withTimeout(30.seconds) { p.connect() }
        println("connected — reading CONNECTED RSSI every 1s (move the peripheral to see it change):")
        repeat(samples) { i ->
            val r = runCatching { p.rssi() }
            println("[%3d] connected RSSI = %s".format(i, r.fold({ "$it dBm" }, { "ERR: ${it.message}" })))
            delay(1000)
        }
    } catch (t: Throwable) {
        println("FAILED: ${t.message}")
    } finally {
        runCatching { peripheral?.disconnect() }
        runCatching { peripheral?.close() }
        scope.cancel()
        http.close()
    }
}
