@file:OptIn(ExperimentalApi::class, ExperimentalUuidApi::class)

package dev.warsha.remoteble.e2e

import com.juul.kable.ExperimentalApi
import com.juul.kable.Peripheral
import com.juul.kable.WriteType
import dev.warsha.remoteble.client.BleMode
import dev.warsha.remoteble.client.DefaultAgentSession
import dev.warsha.remoteble.client.RemotePeripheral
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

// Same TestProfile contract as Main.kt/ThroughputMain.kt (docs/phase7-bringup.md#testprofile--the-contract).
private const val DEFAULT_URL = "ws://localhost:8080/agent"
private const val ADVERTISED_NAME = "RBTestPeripheral"
private const val SERVICE = "a1b2c3d4-0000-4000-8000-000000000001"
private const val WRITABLE = "a1b2c3d4-0000-4000-8000-000000000003"

/**
 * Rig A case 6, burst/ordering half (pr8-validation-plan.md): drives
 * [RemotePeripheral.writeWithoutResponseBurst] (`window > 1`) against the `TestProfile` peripheral
 * and compares it to a serial baseline (`window` effectively 1, i.e. today's plain
 * [Peripheral.write] loop — the same thing [ThroughputMain] measures standalone).
 *
 * Single-byte, strictly-incrementing payloads (`0, 1, 2, ...`) rather than MTU-sized ones: the
 * point of this run is confirming submission order survives pipelining, which is easiest to
 * verify — here, by checking the client-observed result count/order, and separately by reading
 * the peripheral's own on-screen write log — with a payload that *is* the sequence number.
 *
 *   ./gradlew :e2e-runner:wwrBurstRun --args "ws://localhost:8080/agent <token> 40 8"
 *
 * args: [ws-url] [token] [count] [window] (token also read from REMOTE_BLE_TOKEN; count default 40,
 * window default RemoteGattClient.DEFAULT_BURST_WINDOW).
 */
fun main(args: Array<String>): Unit = runBlocking {
    val url = args.getOrNull(0) ?: DEFAULT_URL
    val token = args.getOrNull(1)?.ifBlank { null } ?: System.getenv("REMOTE_BLE_TOKEN")
    val count = args.getOrNull(2)?.toIntOrNull() ?: 40
    val window = args.getOrNull(3)?.toIntOrNull()

    println("== RemoteBle WWR burst/ordering (Rig A case 6) ==")
    println("agent : $url")
    println("device: \"$ADVERTISED_NAME\"  token=${if (token != null) "set" else "none"}  count=$count")

    val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    val http = defaultWebSocketHttpClient()
    val session = DefaultAgentSession(
        WebSocketAgentTransport(url, scope, http, authToken = { token }),
        CborProtocolCodec(),
        scope,
    )
    var peripheral: Peripheral? = null
    var failed = false
    try {
        withTimeout(15.seconds) { session.transportState.first { it == TransportState.CONNECTED } }
        println("transport connected; scanning for \"$ADVERTISED_NAME\"...")
        val adv = withTimeout(30.seconds) {
            RemoteScanner(session).advertisements.first { it.name == ADVERTISED_NAME }
        }
        val p = peripheralFor(BleMode.REMOTE, adv, session) as RemotePeripheral
        peripheral = p
        println("connecting...")
        withTimeout(30.seconds) { p.connect() }

        val services = p.services.value ?: error("no services discovered")
        val svc = services.first { it.serviceUuid.toString().equals(SERVICE, ignoreCase = true) }
        val writable = svc.characteristics.first { it.characteristicUuid.toString().equals(WRITABLE, ignoreCase = true) }

        val payloads = List(count) { i -> byteArrayOf(i.toByte()) }

        println()
        println("-- serial baseline (window=1, today's plain write loop) --")
        val serialStart = TimeSource.Monotonic.markNow()
        payloads.forEach { p.write(writable, it, WriteType.WithoutResponse) }
        val serialMs = serialStart.elapsedNow().inWholeMicroseconds / 1000.0
        println("serial   : $count writes in %.1f ms (%.2f ms/write)".format(serialMs, serialMs / count))

        val effectiveWindow = window ?: 8
        println()
        println("-- burst (window=$effectiveWindow) --")
        val burstStart = TimeSource.Monotonic.markNow()
        val results = if (window != null) {
            p.writeWithoutResponseBurst(writable, payloads, window)
        } else {
            p.writeWithoutResponseBurst(writable, payloads)
        }
        val burstMs = burstStart.elapsedNow().inWholeMicroseconds / 1000.0
        println("burst    : $count writes in %.1f ms (%.2f ms/write)".format(burstMs, burstMs / count))

        val failures = results.withIndex().filter { it.value.isFailure }
        if (failures.isNotEmpty()) {
            println("FAILED: ${failures.size}/$count burst writes failed: ${failures.take(5)}")
            failed = true
        } else if (results.size != count) {
            println("FAILED: expected $count results, got ${results.size}")
            failed = true
        } else {
            val improvement = serialMs / burstMs
            println()
            println("RESULT: all $count burst writes succeeded; burst was %.2fx the serial baseline's speed".format(improvement))
            if (improvement <= 1.0) {
                println(
                    "NOTE: no measured improvement over serial — expected only if the link is " +
                        "already faster than the pipelining window helps with; not necessarily a failure.",
                )
            }
            println(
                "Cross-check submission order on the peripheral's own write log/screen: it must read " +
                    "0, 1, 2, ... ${count - 1} in that order for both the serial and burst sections.",
            )
        }
    } catch (t: Throwable) {
        println("FAILED: ${t.message}")
        failed = true
    } finally {
        runCatching { peripheral?.disconnect() }
        runCatching { peripheral?.close() }
        scope.cancel()
        http.close()
    }
    exitProcess(if (failed) 1 else 0)
}
