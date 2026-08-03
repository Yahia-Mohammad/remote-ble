@file:OptIn(ExperimentalApi::class, ExperimentalUuidApi::class)

package dev.warsha.remoteble.e2e

import dev.warsha.remoteble.client.DefaultAgentSession
import dev.warsha.remoteble.client.RemoteAdvertisement
import dev.warsha.remoteble.client.RemoteScanner
import dev.warsha.remoteble.client.TransportState
import dev.warsha.remoteble.client.WebSocketAgentTransport
import dev.warsha.remoteble.client.awaitScanConcurrencyMode
import dev.warsha.remoteble.client.defaultWebSocketHttpClient
import dev.warsha.remoteble.protocol.CborProtocolCodec
import dev.warsha.remoteble.protocol.ScanFilter
import com.juul.kable.ExperimentalApi
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.ExperimentalUuidApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.delay

/**
 * Scan-only client: client SDK -> WebSocket -> agent -> radio. Lists every BLE
 * advertisement the remote agent's radio sees for a fixed window, then exits.
 *
 * The client process has NO Bluetooth radio of its own — proving the proxied-scan
 * path (e.g. an emulator scanning through the host Mac).
 *
 * An optional third argument sends a **service-UUID scan filter** instead of scanning unfiltered.
 * That is the one variable gap 15 turns on: Apple ignores a `nil` `serviceUUIDs` scan entirely while
 * the app is backgrounded, so an unfiltered scan through a backgrounded iOS agent is expected to find
 * nothing while a filtered one still discovers. Running the same probe twice with only this argument
 * changed is what makes the difference attributable to the filter rather than to the rig.
 *
 * Usage: java ... dev.warsha.remoteble.e2e.ScanMainKt [ws-url] [seconds] [service-uuid]
 */
fun main(args: Array<String>): Unit = runBlocking {
    val url = args.getOrNull(0) ?: "ws://localhost:8080/agent"
    val window = (args.getOrNull(1)?.toIntOrNull() ?: 15).seconds
    val service = args.getOrNull(2)?.takeIf { it.isNotBlank() }
    val token = System.getenv("REMOTE_BLE_TOKEN")

    println("== RemoteBle scan-only client ==")
    println("agent : $url")
    println("window: $window")
    println("filter: ${service?.let { "service=$it" } ?: "none (unfiltered scan)"}")
    println()

    val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    val http = defaultWebSocketHttpClient()
    val session = DefaultAgentSession(
        WebSocketAgentTransport(url, scope, http, authToken = { token }),
        CborProtocolCodec(),
        scope,
    )

    var found = 0
    try {
        print("• connecting transport ... ")
        withTimeout(15.seconds) { session.transportState.first { it == TransportState.CONNECTED } }
        println("CONNECTED")
        // Printed on every run because the same command means different things in different modes,
        // so an evidence file that does not name the negotiated mode cannot be read back later.
        println("• scan concurrency: ${withTimeoutOrNull(10.seconds) { session.awaitScanConcurrencyMode() } ?: "UNKNOWN"}")

        val seen = LinkedHashSet<String>()
        println("• scanning (listing devices as they arrive):")
        val filters = service?.let { listOf(ScanFilter(service = it)) } ?: emptyList()
        val job = RemoteScanner(session, filters).advertisements
            .onEach { adv: RemoteAdvertisement ->
                val id = adv.identifier.toString()
                if (seen.add(id)) {
                    found++
                    val name = adv.name ?: "(no name)"
                    val uuids = if (adv.uuids.isEmpty()) "" else " uuids=${adv.uuids}"
                    // Kable reports Int.MIN_VALUE when the advertisement carries no RSSI.
                    val rssi = if (adv.rssi == Int.MIN_VALUE) "n/a" else adv.rssi.toString()
                    println("    [%2d] %-28s rssi=%-5s id=%s%s".format(found, name, rssi, id, uuids))
                }
            }
            .launchIn(scope)

        delay(window)
        job.cancel()
        println()
        println("------------------------------")
        println("RESULT: $found unique device(s) seen via the remote agent.")
    } catch (t: Throwable) {
        println("FAIL: ${t.message}")
    } finally {
        http.close()
        scope.cancel()
    }

    exitProcess(if (found > 0) 0 else 1)
}
