@file:OptIn(ExperimentalApi::class, ExperimentalUuidApi::class)

package dev.warsha.remoteble.e2e

import dev.warsha.remoteble.client.BleMode
import dev.warsha.remoteble.client.DefaultAgentSession
import dev.warsha.remoteble.client.RemoteScanner
import dev.warsha.remoteble.client.TransportState
import dev.warsha.remoteble.client.WebSocketAgentTransport
import dev.warsha.remoteble.client.defaultWebSocketHttpClient
import dev.warsha.remoteble.client.peripheralFor
import dev.warsha.remoteble.protocol.CborProtocolCodec
import com.juul.kable.DiscoveredCharacteristic
import com.juul.kable.ExperimentalApi
import com.juul.kable.Peripheral
import com.juul.kable.WriteType
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.ExperimentalUuidApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

// The TestProfile contract — must match the test peripheral app's GATT service exactly.
private const val DEFAULT_URL = "ws://localhost:8080/agent"
private const val ADVERTISED_NAME = "RBTestPeripheral"
private const val SERVICE = "a1b2c3d4-0000-4000-8000-000000000001"
private const val READABLE = "a1b2c3d4-0000-4000-8000-000000000002"
private const val WRITABLE = "a1b2c3d4-0000-4000-8000-000000000003"
private const val NOTIFY = "a1b2c3d4-0000-4000-8000-000000000004"
// Encryption-required read — part of the contract, but reading it triggers OS pairing (needs
// on-device user interaction), so this headless runner only confirms it's exposed, never reads it.
private const val SECURE = "a1b2c3d4-0000-4000-8000-000000000005"

/**
 * Live end-to-end runner: client SDK -> WebSocket -> agent -> radio -> the test peripheral.
 *
 * Drives the **Kable `Peripheral` API** (scan, connect, discover, read, write, observe) so a pass
 * is also proof that app logic written against Kable runs unchanged against a remote agent.
 *
 * Usage: `./gradlew :e2e-runner:jvmRun --args "<ws-url> [token]"`
 * (defaults to `$DEFAULT_URL`; token also read from `REMOTE_BLE_TOKEN`). Needs a running agent and
 * a phone running a test peripheral app with its server started (see docs/phase7-bringup.md).
 */
fun main(args: Array<String>): Unit = runBlocking {
    val url = args.getOrNull(0) ?: DEFAULT_URL
    val token = args.getOrNull(1) ?: System.getenv("REMOTE_BLE_TOKEN")

    println("== RemoteBle live E2E ==")
    println("agent : $url")
    println("token : ${if (token != null) "set" else "none"}")
    println("device: \"$ADVERTISED_NAME\"")
    println()

    val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    val http = defaultWebSocketHttpClient()
    val session = DefaultAgentSession(
        WebSocketAgentTransport(url, scope, http, authToken = { token }),
        CborProtocolCodec(),
        scope,
    )
    val report = Report()
    var peripheral: Peripheral? = null

    try {
        report.step("Transport connects") {
            withTimeout(15.seconds) { session.transportState.first { it == TransportState.CONNECTED } }
            "CONNECTED"
        }

        val advertisement = report.capture("Scan finds the peripheral") {
            withTimeout(30.seconds) {
                RemoteScanner(session).advertisements.first { it.name == ADVERTISED_NAME }
            }
        }

        peripheral = peripheralFor(BleMode.REMOTE, advertisement, session)
        val p = peripheral

        report.step("Connect + discover services") {
            p.connect()
            val services = p.services.value ?: error("no services discovered")
            "${services.size} services"
        }

        val services = p.services.value ?: error("no services discovered")
        val chars = report.capture("Locate profile characteristics") {
            val svc = services.first { it.serviceUuid.toString().equals(SERVICE, ignoreCase = true) }
            // Confirm the encrypted SECURE characteristic is exposed (throws if missing); we don't
            // read it here — that would trigger pairing.
            svc.characteristics.byUuid(SECURE)
            Triple(
                svc.characteristics.byUuid(READABLE),
                svc.characteristics.byUuid(WRITABLE),
                svc.characteristics.byUuid(NOTIFY),
            )
        }
        val (readable, writable, notify) = chars

        report.step("Read the readable characteristic") {
            peripheral.read(readable).toHex()
        }

        report.step("Write (with response)") {
            peripheral.write(writable, byteArrayOf(0x01, 0x02), WriteType.WithResponse)
            null
        }

        report.step("Write (without response)") {
            peripheral.write(writable, byteArrayOf(0x03), WriteType.WithoutResponse)
            null
        }

        report.step("Negotiated MTU write length") {
            "${peripheral.maximumWriteValueLengthForType(WriteType.WithResponse)} bytes"
        }

        println()
        println(">>> Now press 'Notify (counter +1)' on the phone TWICE (within 60s) <<<")
        println()
        report.step("Observe 2 notifications") {
            val received = withTimeout(60.seconds) { peripheral.observe(notify).take(2).toList() }
            "${received.size} received: ${received.joinToString { it.toHex() }}"
        }

        report.step("Disconnect") {
            peripheral.disconnect()
            null
        }
    } catch (_: Throwable) {
        // Each step already reported its own failure; fall through to the summary.
    } finally {
        report.summary()
        runCatching { peripheral?.close() }
        http.close()
        scope.cancel()
    }

    exitProcess(if (report.failed == 0) 0 else 1)
}

/** Tiny pass/fail reporter for the linear op sequence. */
private class Report {
    var passed = 0
        private set
    var failed = 0
        private set

    /** Run a step whose result is a short detail string (or null) shown next to PASS. */
    suspend fun step(name: String, block: suspend () -> String?) {
        print("• $name ... ")
        try {
            val detail = block()
            println("PASS" + (detail?.let { " — $it" } ?: ""))
            passed++
        } catch (t: Throwable) {
            println("FAIL: ${t.message}")
            failed++
            throw t
        }
    }

    /** Run a step that yields a value used by later steps. */
    suspend fun <T> capture(name: String, block: suspend () -> T): T {
        print("• $name ... ")
        try {
            val result = block()
            println("PASS")
            passed++
            return result
        } catch (t: Throwable) {
            println("FAIL: ${t.message}")
            failed++
            throw t
        }
    }

    fun summary() {
        println("------------------------------")
        println("RESULT: $passed passed, $failed failed")
    }
}

private fun List<DiscoveredCharacteristic>.byUuid(uuid: String): DiscoveredCharacteristic =
    first { it.characteristicUuid.toString().equals(uuid, ignoreCase = true) }

private fun ByteArray.toHex(): String =
    if (isEmpty()) "(empty)" else joinToString(" ") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
