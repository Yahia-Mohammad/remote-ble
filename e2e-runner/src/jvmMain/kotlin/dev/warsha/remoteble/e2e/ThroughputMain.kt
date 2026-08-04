@file:OptIn(ExperimentalApi::class, ExperimentalUuidApi::class)

package dev.warsha.remoteble.e2e

import com.juul.kable.ExperimentalApi
import com.juul.kable.Peripheral
import com.juul.kable.WriteType
import dev.warsha.remoteble.client.BleMode
import dev.warsha.remoteble.client.DefaultAgentSession
import dev.warsha.remoteble.client.RemoteScanner
import dev.warsha.remoteble.client.TransportState
import dev.warsha.remoteble.client.WebSocketAgentTransport
import dev.warsha.remoteble.client.defaultWebSocketHttpClient
import dev.warsha.remoteble.client.peripheralFor
import dev.warsha.remoteble.protocol.CborProtocolCodec
import kotlin.math.sqrt
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

// Same TestProfile contract as Main.kt (docs/bringup.md#testprofile--the-contract),
// duplicated locally so this driver runs standalone via `:e2e-runner:throughputRun`.
private const val DEFAULT_URL = "ws://localhost:8080/agent"
private const val ADVERTISED_NAME = "RBTestPeripheral"
private const val SERVICE = "a1b2c3d4-0000-4000-8000-000000000001"
private const val WRITABLE = "a1b2c3d4-0000-4000-8000-000000000003"

/**
 * Write-without-response throughput baseline — 0.8.3 feature C (`ai-context/0.8.3-implementation-plan.md`
 * §2a). Drives a burst of N `WriteType.WithoutResponse` writes of MTU-sized payloads to the
 * `TestProfile` peripheral, **awaiting each Reply serially** (today's client behavior: one
 * `RemotePeripheral.write` call is one full client<->agent round-trip, no pipelining), and reports
 * wall-clock throughput plus the per-write latency distribution.
 *
 * This number — and specifically whether per-write latency tracks a WebSocket round-trip or the
 * radio — is what the plan gates the coalescing design on (§2b/2c): if the round-trip dominates,
 * the fix is client-side pipelining or a wire batch op, not an agent-internal change. Re-run this
 * after any coalescing change lands and compare against the recorded baseline.
 *
 *   ./gradlew :e2e-runner:throughputRun --args "ws://localhost:8080/agent <token> 200"
 *
 * args: [ws-url] [token] [burst-count]  (token also read from REMOTE_BLE_TOKEN; burst-count default 200).
 */
fun main(args: Array<String>): Unit = runBlocking {
    val url = args.getOrNull(0) ?: DEFAULT_URL
    val token = args.getOrNull(1)?.ifBlank { null } ?: System.getenv("REMOTE_BLE_TOKEN")
    val burstCount = args.getOrNull(2)?.toIntOrNull() ?: 200

    println("== RemoteBle WWR throughput baseline (0.8.3 / C) ==")
    println("agent : $url")
    println("device: \"$ADVERTISED_NAME\"  token=${if (token != null) "set" else "none"}  burst=$burstCount")

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
        println("transport connected; scanning for \"$ADVERTISED_NAME\"...")
        val adv = withTimeout(30.seconds) {
            RemoteScanner(session).advertisements.first { it.name == ADVERTISED_NAME }
        }
        val p = peripheralFor(BleMode.REMOTE, adv, session).also { peripheral = it }
        println("connecting...")
        withTimeout(30.seconds) { p.connect() }

        val services = p.services.value ?: error("no services discovered")
        val svc = services.first { it.serviceUuid.toString().equals(SERVICE, ignoreCase = true) }
        val writable = svc.characteristics.first { it.characteristicUuid.toString().equals(WRITABLE, ignoreCase = true) }

        val payloadSize = p.maximumWriteValueLengthForType(WriteType.WithoutResponse)
        val payload = ByteArray(payloadSize) { (it and 0xFF).toByte() }
        println("connected — negotiated MTU write length: $payloadSize bytes/write")
        println(
            "driving a serial burst of $burstCount WithoutResponse writes " +
                "(client awaits each Reply — no pipelining)...",
        )

        val latenciesMs = DoubleArray(burstCount)
        val burstStart = TimeSource.Monotonic.markNow()
        repeat(burstCount) { i ->
            val writeStart = TimeSource.Monotonic.markNow()
            p.write(writable, payload, WriteType.WithoutResponse)
            latenciesMs[i] = writeStart.elapsedNow().inWholeMicroseconds / 1000.0
        }
        val elapsedMs = burstStart.elapsedNow().inWholeMicroseconds / 1000.0

        val totalBytes = burstCount.toLong() * payloadSize
        val throughputBps = if (elapsedMs > 0) totalBytes / (elapsedMs / 1000.0) else Double.NaN
        val sorted = latenciesMs.sorted()
        fun percentile(fraction: Double): Double = sorted[((sorted.size - 1) * fraction).toInt()]
        val mean = latenciesMs.average()
        val stddev = sqrt(latenciesMs.sumOf { (it - mean) * (it - mean) } / latenciesMs.size)

        println()
        println("== RESULT ==")
        println("writes         : $burstCount x $payloadSize bytes = $totalBytes bytes total")
        println("wall time      : %.1f ms".format(elapsedMs))
        println("throughput     : %.1f bytes/s (%.2f KB/s)".format(throughputBps, throughputBps / 1024))
        println(
            "per-write (ms) : min=%.2f  p50=%.2f  mean=%.2f  p90=%.2f  p99=%.2f  max=%.2f  stddev=%.2f".format(
                sorted.first(), percentile(0.50), mean, percentile(0.90), percentile(0.99), sorted.last(), stddev,
            ),
        )
        println()
        println(
            "Compare per-write latency against the WebSocket round-trip time on this link (e.g. a bare " +
                "Read's latency): if they track closely, the round-trip dominates over the radio — see " +
                "ai-context/0.8.3-implementation-plan.md §2b for what that implies for the coalescing design.",
        )
    } catch (t: Throwable) {
        println("FAILED: ${t.message}")
    } finally {
        runCatching { peripheral?.disconnect() }
        runCatching { peripheral?.close() }
        scope.cancel()
        http.close()
    }
}
