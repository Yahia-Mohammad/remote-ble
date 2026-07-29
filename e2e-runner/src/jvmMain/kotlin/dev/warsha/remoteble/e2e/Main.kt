@file:OptIn(ExperimentalApi::class, ExperimentalUuidApi::class)

package dev.warsha.remoteble.e2e

import dev.warsha.remoteble.client.BleMode
import dev.warsha.remoteble.client.DefaultAgentSession
import dev.warsha.remoteble.client.RemoteScanner
import dev.warsha.remoteble.client.TransportState
import dev.warsha.remoteble.client.WebSocketAgentTransport
import dev.warsha.remoteble.client.defaultWebSocketHttpClient
import dev.warsha.remoteble.client.peripheralFor
import dev.warsha.remoteble.protocol.AgentException
import dev.warsha.remoteble.protocol.CborProtocolCodec
import dev.warsha.remoteble.protocol.ErrorKind
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
 * Why the write-error scenario is gated rather than expected to pass on btleplug-backed agents.
 *
 * Confirmed on hardware (Rig A, 2026-07-27): btleplug on macOS never delivers the completion for a
 * write-with-response the peripheral answers with an ATT error — the native call neither returns
 * nor throws, so no agent above it can report WRITE_FAILED. The agents bound the transaction and
 * report TIMEOUT instead (`EngineBleBackend.GATT_OP_TIMEOUT`), which is honest but is not the
 * documented expectation, so this is an XFAIL rather than a relaxed assertion.
 */
private const val BTLEPLUG_ATT_ERROR_GAP =
    "btleplug does not deliver ATT errors for write-with-response; the agent reports TIMEOUT instead"

/**
 * The same backend gap, second symptom.
 *
 * Confirmed on hardware (Rig A, 2026-07-28): once btleplug has had one write-with-response answered
 * by an ATT error, it stops delivering write completions for that peripheral for the rest of the
 * connection. The peripheral's own log shows the later write arriving and being accepted with error
 * injection already off, yet no completion comes back. Reads still work, and a fresh connection
 * writes normally in ~66ms — reconnecting is the only observed recovery, so no agent-side handling
 * can make this step pass on btleplug. Gated for the same reason as [BTLEPLUG_ATT_ERROR_GAP]:
 * the expectation is right, this backend cannot meet it.
 */
private const val BTLEPLUG_WRITE_POISONING =
    "btleplug stops delivering write completions for the rest of the connection after an ATT error; " +
        "only reconnecting recovers"

/**
 * Whether the agent under test sits on btleplug (the Kotlin JVM agent on macOS/Linux, or agent-rs).
 *
 * Operator-declared: the wire protocol carries no agent/platform identity, and the runner is
 * already driven by hand against a chosen agent. Defaults to true because both desktop reference
 * agents are btleplug-backed; set `REMOTE_BLE_E2E_BTLEPLUG=false` when pointing the runner at the
 * Android or Apple agent, whose native Kable backends are expected to deliver ATT errors properly
 * (unverified on hardware as of 2026-07-27 — an XPASS there is the confirmation).
 */
private val btleplugBackedAgent: Boolean =
    System.getenv("REMOTE_BLE_E2E_BTLEPLUG")?.toBooleanStrictOrNull() ?: true

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
    println("device: service $SERVICE (advertised as \"$ADVERTISED_NAME\")")
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

        // Match on the TestProfile service UUID, not the advertised name. The name is not a
        // reliable discriminator through every agent: on Apple hosts Kable never surfaces a local
        // name carried in the scan response (which is where it must live — the 128-bit service
        // UUID plus the name overflows a 31-byte legacy PDU), so this peripheral arrives nameless
        // via the Kotlin agent while `agent-rs` reports it by name off the same radio. The service
        // UUID is the actual TestProfile contract and is present in the primary PDU on every path.
        // See docs/pr8-rig-b-evidence.md.
        val advertisement = report.capture("Scan finds the peripheral") {
            withTimeout(30.seconds) {
                RemoteScanner(session).advertisements.first { adv ->
                    adv.uuids.any { it.toString().equals(SERVICE, ignoreCase = true) }
                }
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

        val baseline = report.capture("Read the readable characteristic (baseline)") {
            peripheral.read(readable)
        }

        println()
        println(">>> Now change the readable characteristic's value on the phone ('Bump readable value'), then press Enter <<<")
        readlnOrNull()
        report.step("Read exactness (F) — reflects the just-set bump, not a stale/cached value") {
            val after = peripheral.read(readable)
            check(!after.contentEquals(baseline)) {
                "read returned the same bytes as the baseline after a bump — looks stale/cached, not exact"
            }
            after.toHex()
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
        println(">>> Now toggle 'Force write error' ON on the phone, then press Enter <<<")
        readlnOrNull()
        report.knownFailing(
            "Write-with-response error surfaces WRITE_FAILED (F)",
            expectedToFail = btleplugBackedAgent,
            reason = BTLEPLUG_ATT_ERROR_GAP,
        ) {
            val failure = runCatching {
                peripheral.write(writable, byteArrayOf(0xEE.toByte()), WriteType.WithResponse)
            }.exceptionOrNull()
            val kind = (failure as? AgentException)?.error?.kind
            check(kind == ErrorKind.WRITE_FAILED) {
                "expected WRITE_FAILED, got ${failure?.let { it::class.simpleName } ?: "no failure"} ($kind)"
            }
            "WRITE_FAILED as expected"
        }
        report.step("WWR still returns Ok despite the same peripheral-side reject (inherent BLE limit, not a bug)") {
            // WithoutResponse has no ATT response, so nothing can carry the peripheral's rejection
            // back to the client — Ok here documents that limit rather than "fixing" it.
            peripheral.write(writable, byteArrayOf(0xEE.toByte()), WriteType.WithoutResponse)
            "Ok, as expected"
        }
        println()
        println(">>> Now toggle 'Force write error' OFF on the phone, then press Enter <<<")
        readlnOrNull()
        report.knownFailing(
            "Write-with-response succeeds again — a failed write never poisons the session",
            expectedToFail = btleplugBackedAgent,
            reason = BTLEPLUG_WRITE_POISONING,
        ) {
            peripheral.write(writable, byteArrayOf(0x01, 0x02), WriteType.WithResponse)
            null
        }

        println()
        println(">>> Now press 'Notify (counter +1)' on the phone TWICE (within 60s) <<<")
        println()
        report.step("Observe 2 notifications, no miss/dup") {
            val received = withTimeout(60.seconds) { peripheral.observe(notify).take(2).toList() }
            check(received.size == 2) { "expected 2 notifications, got ${received.size}" }
            check(!received[0].contentEquals(received[1])) {
                "received a duplicate notification payload — missed or duplicated delivery"
            }
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
    var knownFailed = 0
        private set
    var unexpectedlyPassed = 0
        private set

    /**
     * A step whose expectation is correct but that a *specific backend* cannot currently satisfy.
     *
     * Unlike [step] this never aborts the run when [expectedToFail] holds: it records XFAIL and
     * continues, so one backend-level gap doesn't hide every later step. The expectation itself is
     * deliberately left unchanged — where [expectedToFail] is false the step is enforced normally,
     * and if it *passes* while expected to fail that is reported as XPASS, which is the signal that
     * the underlying gap is fixed and the gate can be removed.
     */
    suspend fun knownFailing(
        name: String,
        expectedToFail: Boolean,
        reason: String,
        block: suspend () -> String?,
    ) {
        if (!expectedToFail) return step(name, block)
        print("• $name ... ")
        try {
            val detail = block()
            println("XPASS" + (detail?.let { " — $it" } ?: "") + " — expected to fail ($reason); the gate can be removed")
            unexpectedlyPassed++
        } catch (t: Throwable) {
            println("XFAIL (known): ${t.message} — $reason")
            knownFailed++
        }
    }

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
        val extra = buildList {
            if (knownFailed > 0) add("$knownFailed known-failing")
            if (unexpectedlyPassed > 0) add("$unexpectedlyPassed unexpectedly passing")
        }
        println("RESULT: $passed passed, $failed failed" + extra.joinToString("") { ", $it" })
        if (unexpectedlyPassed > 0) {
            println("NOTE: an XPASS means a backend gate is stale — re-check it and drop the gate.")
        }
    }
}

private fun List<DiscoveredCharacteristic>.byUuid(uuid: String): DiscoveredCharacteristic =
    first { it.characteristicUuid.toString().equals(uuid, ignoreCase = true) }

private fun ByteArray.toHex(): String =
    if (isEmpty()) "(empty)" else joinToString(" ") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
