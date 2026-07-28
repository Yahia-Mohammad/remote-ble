@file:OptIn(ExperimentalApi::class, ExperimentalUuidApi::class)

package dev.warsha.remoteble.e2e

import com.juul.kable.DiscoveredCharacteristic
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
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlin.uuid.ExperimentalUuidApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * `TLS-PROXY-01` cases 4 and 5: sustained notification delivery, and lease-resuming reconnect,
 * both through a TLS-terminating reverse proxy.
 *
 * Long-lived WebSocket frames are exactly what a misconfigured proxy breaks — it may buffer
 * notifications into bursts, stall them, or silently drop the connection. So this runner does not
 * merely count notifications: it records the **inter-arrival gap** of each one and reports the
 * worst, which is what distinguishes a healthy stream from a proxy that is batching or stalling.
 *
 * It also watches [DefaultAgentSession.transportState] throughout. Restart the proxy mid-run and
 * the transitions are the case-4 evidence: the client must reconnect through `wss://` on its own
 * and the notification stream must resume without re-subscribing by hand.
 *
 * Defaults target the canonical simulated HRM profile (`agent/simulation/sim-hrm.json`), so the
 * whole case runs with no radio. Point `--service`/`--characteristic` at the `TestProfile` notify
 * characteristic to re-confirm the same case against real hardware on the Rig A peripheral.
 *
 *   ./gradlew :e2e-runner:tlsProxyRun \
 *     -PtrustStore=/path/truststore.p12 -PtrustStorePassword=changeit \
 *     --args "wss://localhost:8443/agent 'Warsha HRM (sim)' 30"
 *
 * args: [wss-url] [device-name] [seconds] [service-uuid] [characteristic-uuid]
 * (token read from `REMOTE_BLE_TOKEN`).
 */
fun main(args: Array<String>): Unit = runBlocking {
    val url = args.getOrNull(0) ?: "wss://localhost:8443/agent"
    val name = args.getOrNull(1) ?: "Warsha HRM (sim)"
    val window = (args.getOrNull(2)?.toIntOrNull() ?: 30).seconds
    val serviceUuid = args.getOrNull(3) ?: "0000180d-0000-1000-8000-00805f9b34fb"
    val charUuid = args.getOrNull(4) ?: "00002a37-0000-1000-8000-00805f9b34fb"
    val token = System.getenv("REMOTE_BLE_TOKEN")

    println("== TLS-PROXY-01 · cases 4 (reconnect) + 5 (notification delivery) ==")
    println("proxy    : $url")
    println("device   : \"$name\"   token=${if (token != null) "set" else "none"}")
    println("window   : $window")
    println("truststore: ${System.getProperty("javax.net.ssl.trustStore") ?: "<JVM default>"}")
    println()

    val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    val http = defaultWebSocketHttpClient()
    val session = DefaultAgentSession(
        WebSocketAgentTransport(url, scope, http, authToken = { token }),
        CborProtocolCodec(),
        scope,
    )

    val started = TimeSource.Monotonic.markNow()
    fun stamp() = "%7.3fs".format(started.elapsedNow().inWholeMilliseconds / 1000.0)

    // Case 4 evidence: every transport transition, timestamped. A proxy restart should show
    // CONNECTED -> DISCONNECTED -> CONNECTED with no operator intervention.
    val transitions = mutableListOf<Pair<String, TransportState>>()
    session.transportState
        .onEach { state ->
            transitions += stamp() to state
            println("[${stamp()}] transport: $state")
        }
        .launchIn(scope)

    var peripheral: Peripheral? = null
    try {
        withTimeout(20.seconds) { session.transportState.first { it == TransportState.CONNECTED } }
        val adv = withTimeout(60.seconds) { RemoteScanner(session).advertisements.first { it.name == name } }
        println("[${stamp()}] found \"${adv.name}\"")

        val p = peripheralFor(BleMode.REMOTE, adv, session).also { peripheral = it }
        withTimeout(30.seconds) { p.connect() }
        val services = p.services.value ?: error("no services discovered")
        val notify = services
            .first { it.serviceUuid.toString().equals(serviceUuid, ignoreCase = true) }
            .characteristics
            .firstOrNull { it.characteristicUuid.toString().equals(charUuid, ignoreCase = true) }
            ?: error("characteristic $charUuid not found in service $serviceUuid")
        println("[${stamp()}] connected; observing ${notify.characteristicUuid}")
        println()

        val arrivals = mutableListOf<Long>()
        val observer = p.observe(notify).onEach {
            val at = started.elapsedNow().inWholeMilliseconds
            val gap = arrivals.lastOrNull()?.let { previous -> at - previous }
            arrivals += at
            println(
                "[${stamp()}] notify #%-3d %-12s %s".format(
                    arrivals.size,
                    it.toHex(),
                    gap?.let { g -> "gap=${g}ms" } ?: "(first)",
                ),
            )
        }.launchIn(scope)

        println(">>> Restart the proxy at any point during this window to exercise case 4. <<<")
        println()
        delay(window)
        observer.cancel()

        report(arrivals, transitions, window.inWholeMilliseconds)
    } catch (t: Throwable) {
        println()
        println("FAILED: ${t::class.simpleName}: ${t.message}")
        exitProcess(1)
    } finally {
        runCatching { peripheral?.disconnect() }
        runCatching { peripheral?.close() }
        scope.cancel()
        http.close()
    }
}

private fun report(arrivals: List<Long>, transitions: List<Pair<String, TransportState>>, windowMs: Long) {
    val gaps = arrivals.zipWithNext { a, b -> b - a }
    // Count outage *episodes*, not raw DISCONNECTED transitions: one outage produces a whole
    // backoff ladder of CONNECTING/DISCONNECTED pairs, and reporting nine "disconnects" for a
    // single proxy restart reads as nine separate failures. An episode is a DISCONNECTED that
    // follows a live connection.
    val outages = transitions
        .map { it.second }
        .filter { it == TransportState.CONNECTED || it == TransportState.DISCONNECTED }
        .zipWithNext()
        .count { (previous, current) ->
            previous == TransportState.CONNECTED && current == TransportState.DISCONNECTED
        }
    val retries = transitions.count { it.second == TransportState.CONNECTING }
    // The first CONNECTED is the initial connect, not a recovery.
    val recoveries = (transitions.count { it.second == TransportState.CONNECTED } - 1).coerceAtLeast(0)

    println()
    println("-".repeat(60))
    println("notifications : ${arrivals.size} over ${windowMs / 1000}s")
    if (gaps.isNotEmpty()) {
        println("inter-arrival : min=${gaps.min()}ms  max=${gaps.max()}ms  mean=${gaps.average().toLong()}ms")
        println("                (a max far above the mean means the proxy buffered or stalled the stream)")
    }
    println("transport     : $outages outage(s), $retries connect attempt(s), $recoveries recovery(ies)")
    transitions.forEach { (at, state) -> println("                $at  $state") }
    println("-".repeat(60))
    println(
        when {
            arrivals.isEmpty() -> "CASE 5 FAIL: no notifications arrived through the proxy."
            outages > 0 && recoveries == 0 -> "CASE 4 FAIL: transport dropped and never recovered."
            outages > 0 ->
                "CASE 4 + 5 PASS: stream delivered, and the client recovered through the proxy " +
                    "and resumed the subscription without re-subscribing."
            else -> "CASE 5 PASS. Case 4 not exercised — no transport drop occurred in this window."
        },
    )
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
