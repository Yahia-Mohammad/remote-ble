@file:OptIn(ExperimentalApi::class, ExperimentalUuidApi::class)

package dev.warsha.remoteble.e2e

import dev.warsha.remoteble.client.DefaultAgentSession
import dev.warsha.remoteble.client.RemoteScanSource
import dev.warsha.remoteble.client.TransportState
import dev.warsha.remoteble.client.WebSocketAgentTransport
import dev.warsha.remoteble.client.awaitScanConcurrencyMode
import dev.warsha.remoteble.client.defaultWebSocketHttpClient
import dev.warsha.remoteble.client.pairingWebSocketHttpClient
import dev.warsha.remoteble.protocol.AdvertisementDto
import dev.warsha.remoteble.protocol.AgentPairing
import dev.warsha.remoteble.protocol.Capabilities
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
 * The first argument is an agent address or its pairing link (`remoteble://…`); a link brings the
 * token and, for an encrypted agent, the pinned identity, so an encrypted agent is reachable too.
 * Each device's line shows the `scan.fields` the agent forwarded, so a run against a real radio is
 * also the hardware check for them.
 *
 * Usage: java ... dev.warsha.remoteble.e2e.ScanMainKt [ws-url | pairing-link] [seconds] [service-uuid]
 */
fun main(args: Array<String>): Unit = runBlocking {
    val target = args.getOrNull(0) ?: "ws://localhost:8080/agent"
    val pairing = target.takeIf { it.startsWith("${AgentPairing.SCHEME}://", ignoreCase = true) }?.let(AgentPairing::parse)
    val url = pairing?.url ?: target
    val window = (args.getOrNull(1)?.toIntOrNull() ?: 15).seconds
    val service = args.getOrNull(2)?.takeIf { it.isNotBlank() }
    val token = pairing?.token ?: System.getenv("REMOTE_BLE_TOKEN")

    println("== RemoteBle scan-only client ==")
    // The pairing's own toString leaves the token out.
    println("agent : ${pairing ?: url}")
    println("window: $window")
    println("filter: ${service?.let { "service=$it" } ?: "none (unfiltered scan)"}")
    println()

    val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    val http = pairing?.let(::pairingWebSocketHttpClient) ?: defaultWebSocketHttpClient()
    val session = DefaultAgentSession(
        WebSocketAgentTransport(url, scope, http, authToken = { token }),
        CborProtocolCodec(),
        scope,
    )

    var found = 0
    val fields = FieldTally()
    try {
        print("• connecting transport ... ")
        withTimeout(15.seconds) { session.transportState.first { it == TransportState.CONNECTED } }
        println("CONNECTED")
        // Printed on every run because the same command means different things in different modes,
        // so an evidence file that does not name the negotiated mode cannot be read back later.
        println("• scan concurrency: ${withTimeoutOrNull(10.seconds) { session.awaitScanConcurrencyMode() } ?: "UNKNOWN"}")
        println("• scan fields: ${if (Capabilities.SCAN_FIELDS in session.capabilities.value.orEmpty()) "negotiated" else "not offered by this agent"}")

        val seen = LinkedHashSet<String>()
        println("• scanning (listing devices as they arrive):")
        val filters = service?.let { listOf(ScanFilter(service = it)) } ?: emptyList()
        // The protocol-level stream rather than Kable's: Kable can only look service data up by a UUID
        // already known, and this lists every entry the agent sent.
        val job = RemoteScanSource(session).advertisements(filters)
            .onEach { adv: AdvertisementDto ->
                val id = adv.device.value
                if (seen.add(id)) {
                    found++
                    val name = adv.name ?: "(no name)"
                    val uuids = if (adv.serviceUuids.isEmpty()) "" else " uuids=${adv.serviceUuids.map(::shortUuid)}"
                    // Kable reports Int.MIN_VALUE when the advertisement carries no RSSI.
                    val rssi = if (adv.rssi == Int.MIN_VALUE) "n/a" else adv.rssi.toString()
                    println("    [%2d] %-28s rssi=%-5s id=%s%s%s".format(found, name, rssi, id, uuids, fieldsOf(adv)))
                    fields.record(adv)
                }
            }
            .launchIn(scope)

        delay(window)
        job.cancel()
        println()
        println("------------------------------")
        println("RESULT: $found unique device(s) seen via the remote agent.")
        println("FIELDS: $fields")
    } catch (t: Throwable) {
        println("FAIL: ${t.message}")
    } finally {
        http.close()
        scope.cancel()
    }

    exitProcess(if (found > 0) 0 else 1)
}

/** The payload and `scan.fields` of one advertisement, for its line in the listing. */
private fun fieldsOf(adv: AdvertisementDto): String = buildString {
    if (adv.manufacturerData.isNotEmpty()) append(" mfg=").append(adv.manufacturerData.keys.map { "0x%04X".format(it) })
    if (adv.serviceData.isNotEmpty()) append(" serviceData=").append(adv.serviceData.mapKeys { shortUuid(it.key) }.mapValues { it.value.size })
    adv.txPower?.let { append(" tx=").append(it) }
    adv.isConnectable?.let { append(" connectable=").append(it) }
    adv.peripheralName?.let { append(" platformName=").append(it) }
}

/** A SIG UUID by its 16-bit form, anything else in full. */
private fun shortUuid(uuid: String): String =
    uuid.lowercase().takeIf { it.startsWith("0000") && it.endsWith("-0000-1000-8000-00805f9b34fb") }?.substring(4, 8) ?: uuid

/** How many of the devices seen carried each field, so one run says what the agent's platform reports. */
private class FieldTally {
    private var manufacturer = 0
    private var serviceData = 0
    private var txPower = 0
    private var connectable = 0
    private var notConnectable = 0
    private var platformName = 0

    fun record(adv: AdvertisementDto) {
        if (adv.manufacturerData.isNotEmpty()) manufacturer++
        if (adv.serviceData.isNotEmpty()) serviceData++
        if (adv.txPower != null) txPower++
        when (adv.isConnectable) {
            true -> connectable++
            false -> notConnectable++
            null -> Unit
        }
        if (adv.peripheralName != null) platformName++
    }

    override fun toString(): String =
        "manufacturer=$manufacturer serviceData=$serviceData txPower=$txPower " +
            "connectable=$connectable notConnectable=$notConnectable platformName=$platformName"
}
