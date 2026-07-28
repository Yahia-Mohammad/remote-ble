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
import dev.warsha.remoteble.protocol.CharRef
import dev.warsha.remoteble.protocol.Op
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

/**
 * Focused diagnostic for the one live-E2E step that fails on real hardware: a peripheral-side ATT
 * error on a write-with-response is expected to surface as `WRITE_FAILED`, but the client sees
 * `TIMEOUT` instead.
 *
 * The full runner cannot answer *why*, because two 15-second clocks expire together and hide the
 * cause: [dev.warsha.remoteble.client.DefaultAgentSession.DEFAULT_TIMEOUT] cuts the op off at 15s,
 * and the agent's deep liveness probe runs on the same interval and tears the link down. This
 * driver removes both confounds:
 *
 *  - it issues the raw [Op.Write] through the session with a long timeout, so a slow-but-correct
 *    error reply is distinguishable from one that never arrives at all, and
 *  - it is meant to be run against an agent started with a long `REMOTE_BLE_LIVENESS_PROBE_MS`, so
 *    the watchdog cannot retire the connection mid-measurement.
 *
 * Toggle 'Force write error' ON on the peripheral **before** starting this, so there is no timing
 * coordination to get wrong. After the measured write it reads a characteristic to report whether
 * the link is still usable — distinguishing "the error was swallowed" from "the link died".
 *
 * args: [ws-url] [device-name] [timeout-seconds]   (token read from `REMOTE_BLE_TOKEN`).
 */
fun main(args: Array<String>): Unit = runBlocking {
    val url = args.getOrNull(0) ?: "ws://localhost:8080/agent"
    val name = args.getOrNull(1) ?: "RBTestPeripheral"
    val opTimeout = (args.getOrNull(2)?.toIntOrNull() ?: 90).seconds
    val token = System.getenv("REMOTE_BLE_TOKEN")

    val service = "a1b2c3d4-0000-4000-8000-000000000001"
    val writable = "a1b2c3d4-0000-4000-8000-000000000003"
    val readable = "a1b2c3d4-0000-4000-8000-000000000002"

    println("== write-error propagation probe ==")
    println("agent  : $url")
    println("device : \"$name\"   op timeout: $opTimeout")
    println("PRECONDITION: 'Force write error' must already be ON on the peripheral.")
    println()

    val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    val http = defaultWebSocketHttpClient()
    val session = DefaultAgentSession(
        WebSocketAgentTransport(url, scope, http, authToken = { token }),
        CborProtocolCodec(),
        scope,
    )
    var peripheral: Peripheral? = null
    try {
        withTimeout(20.seconds) { session.transportState.first { it == TransportState.CONNECTED } }
        val adv = withTimeout(60.seconds) { RemoteScanner(session).advertisements.first { it.name == name } }
        val handle = adv.handle
        println("found \"$name\" [dev=${handle.value}]")

        val p = peripheralFor(BleMode.REMOTE, adv, session).also { peripheral = it }
        withTimeout(30.seconds) { p.connect() }
        println("connected + discovered")
        println()

        val charRef = CharRef(service = service, characteristic = writable)
        val started = TimeSource.Monotonic.markNow()
        println("issuing write-with-response 0xEE (error injection is ON) …")
        val outcome = runCatching {
            session.request(
                Op.Write(device = handle, char = charRef, value = byteArrayOf(0xEE.toByte()), withResponse = true),
                timeout = opTimeout,
            )
        }
        val elapsed = started.elapsedNow()

        println()
        println("-".repeat(64))
        outcome
            .onSuccess { println("RESULT after $elapsed: $it") }
            .onFailure { println("THREW after $elapsed: ${it::class.simpleName}: ${it.message}") }
        println("-".repeat(64))

        // Is the link still usable, or did the rejected write take the connection with it? This is
        // what separates "the error reply was swallowed" from "the peripheral dropped us".
        val liveness = runCatching {
            session.request(
                Op.Read(device = handle, char = CharRef(service = service, characteristic = readable)),
                timeout = 15.seconds,
            )
        }
        liveness
            .onSuccess { println("link still usable — follow-up read returned: $it") }
            .onFailure { println("link NOT usable — follow-up read failed: ${it::class.simpleName}: ${it.message}") }
    } catch (t: Throwable) {
        println("SETUP FAILED: ${t::class.simpleName}: ${t.message}")
    } finally {
        runCatching { peripheral?.disconnect() }
        runCatching { peripheral?.close() }
        scope.cancel()
        http.close()
    }
}
