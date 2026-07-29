@file:OptIn(ExperimentalUuidApi::class, ObsoleteKableApi::class)

package dev.warsha.remoteble.androidclient

import com.juul.kable.Filter
import com.juul.kable.ObsoleteKableApi
import com.juul.kable.Scanner
import com.juul.kable.State
import com.juul.kable.characteristicOf
import dev.warsha.remoteble.client.BleMode
import dev.warsha.remoteble.client.peripheralFor
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

// The TestProfile contract, same constants :e2e-runner drives the remote path with.
private const val SERVICE = "a1b2c3d4-0000-4000-8000-000000000001"
private const val READABLE = "a1b2c3d4-0000-4000-8000-000000000002"

// Kable's String-taking `characteristicOf` is deprecated, and this build treats warnings as errors.
private val SERVICE_UUID = Uuid.parse(SERVICE)
private val READABLE_UUID = Uuid.parse(READABLE)

/**
 * Debug-only hardware probe for the client SDK's **LOCAL** mode, on this device's own radio.
 *
 * Why it exists: `peripheralFor(BleMode.LOCAL, …)` is the one SDK path that builds an ordinary Kable
 * peripheral instead of a [dev.warsha.remoteble.client.RemotePeripheral], and on Apple it needs
 * `forceCharacteristicEqualityByUuid` or **every read hangs forever** — CoreBluetooth hands back a
 * different `CBCharacteristic` instance than the one the operation was issued against, and Kable
 * matches completions by reference by default. The agent hit exactly this on Rig B and was fixed;
 * the SDK's LOCAL path carried the same defect and no app in this repo exercises it, on any platform.
 * Nothing about it is unit-testable: Kable's `PeripheralBuilder` constructor is `internal`, so the
 * applied option cannot be asserted without a radio (open item 18).
 *
 * **[forceUuidEquality] is the control.** `peripheralFor` applies the workaround and then runs the
 * caller's builder block, so passing `false` overrides it back to Kable's default and re-creates the
 * defect. Running this probe twice with only that argument changed is what makes a completing read
 * attributable to the fix rather than to the peripheral, the link, or the phase of the moon — the
 * expected result is a read that completes in milliseconds with `true` and one that times out with
 * `false`. Do not report a pass without having seen the control fail.
 *
 * Driven by a launch argument rather than any UI (see `ContentView.swift`), so it is invisible in
 * normal use and fully scriptable: `xcrun devicectl device process launch --device $UDID
 * dev.warsha.remoteble.iosclient --arguments --local-mode-probe`.
 *
 * @param log receives one line per step; the iOS shell prints these to the console.
 * @param onDone receives the single-line verdict when the run ends.
 */
fun runLocalModeProbe(
    forceUuidEquality: Boolean = true,
    log: (String) -> Unit,
    onDone: (String) -> Unit,
) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    scope.launch {
        val verdict = probe(forceUuidEquality, log)
        log(verdict)
        onDone(verdict)
    }
}

private fun ByteArray.hex(): String = joinToString(" ") { b -> b.toUByte().toString(16).padStart(2, '0') }

private suspend fun probe(forceUuidEquality: Boolean, log: (String) -> Unit): String {
    log("== LOCAL-mode probe (client SDK, this device's radio) ==")
    log("forceCharacteristicEqualityByUuid = $forceUuidEquality${if (forceUuidEquality) " (fix applied)" else " (CONTROL — expected to hang)"}")

    val scanner = Scanner {
        filters { match { services = listOf(SERVICE_UUID) } }
    }

    log("• scanning for service $SERVICE ...")
    val advertisement = withTimeoutOrNull(20.seconds) {
        scanner.advertisements.filter { it.uuids.any { u -> u == SERVICE_UUID } }.first()
    } ?: return "FAIL: no peripheral advertising $SERVICE within 20s — is the test peripheral running?"
    log("  found ${advertisement.name ?: "(no name)"} id=${advertisement.identifier}")

    // The `kableLogging` block runs *after* peripheralFor's own workaround, so this genuinely
    // overrides it — that is what makes the control run a control rather than a no-op.
    val peripheral = peripheralFor(BleMode.LOCAL, advertisement) {
        forceCharacteristicEqualityByUuid = forceUuidEquality
    }

    // Guard against the probe testing the wrong thing entirely: if this reports RemotePeripheral,
    // LOCAL mode did not take the local branch and nothing below is evidence about Kable at all.
    val kind = peripheral::class.simpleName ?: "unknown"
    log("  peripheral type = $kind")
    if (kind.contains("Remote")) {
        return "FAIL: LOCAL mode produced $kind — the probe is not exercising the local radio path"
    }

    try {
        log("• connecting ...")
        val connected = withTimeoutOrNull(20.seconds) {
            peripheral.connect()
            peripheral.state.first { it is State.Connected }
        } ?: return "FAIL: never reached Connected within 20s"
        log("  $connected")

        // WHICH characteristic object the read is issued against is the whole experiment, and the
        // first version of this probe got it wrong: it read a `characteristicOf(uuid, uuid)` lazy
        // characteristic, which Kable can only resolve *by UUID*, so reference identity never enters
        // the matching and `forceCharacteristicEqualityByUuid` cannot possibly matter. The control
        // run duly passed when it was supposed to fail — the probe was measuring a path the defect
        // does not live on. `EngineBleBackend.findCharacteristic` reads a **DiscoveredCharacteristic**
        // taken from `peripheral.services`, a platform-backed object, and that is the path Rig B's
        // finding 5 was measured on. Both are read here, in that order, so the difference between
        // them is reported rather than assumed.
        log("• discovering services ...")
        val services = withTimeoutOrNull(20.seconds) { peripheral.services.filterNotNull().first() }
            ?: return "FAIL: services never discovered within 20s"
        val discovered = services.firstOrNull { it.serviceUuid == SERVICE_UUID }
            ?.characteristics?.firstOrNull { it.characteristicUuid == READABLE_UUID }
            ?: return "FAIL: $READABLE not found among discovered characteristics"
        log("  discovered characteristic = ${discovered::class.simpleName}")

        log("• read 1/2 — DISCOVERED characteristic (the agent's path; the defect lives here) ...")
        val discoveredStart = TimeSource.Monotonic.markNow()
        val discoveredValue = withTimeoutOrNull(15.seconds) { peripheral.read(discovered) }
        val discoveredElapsed = discoveredStart.elapsedNow()
        log(
            if (discoveredValue == null) {
                "  TIMED OUT after $discoveredElapsed — completion never matched"
            } else {
                "  ok in $discoveredElapsed: ${discoveredValue.hex()}"
            },
        )

        log("• read 2/2 — LAZY characteristicOf(uuid, uuid) (resolved by UUID; flag cannot matter) ...")
        val lazyStart = TimeSource.Monotonic.markNow()
        val lazyValue = withTimeoutOrNull(15.seconds) {
            peripheral.read(characteristicOf(service = SERVICE_UUID, characteristic = READABLE_UUID))
        }
        val lazyElapsed = lazyStart.elapsedNow()
        log(
            if (lazyValue == null) {
                "  TIMED OUT after $lazyElapsed"
            } else {
                "  ok in $lazyElapsed: ${lazyValue.hex()}"
            },
        )

        // The verdict keys on the discovered read only — read 2 is context, not evidence.
        return if (discoveredValue == null) {
            // The defect's exact signature: the peripheral answers, the continuation is never resumed.
            "FAIL: discovered-characteristic read did not complete within 15s (lazy read: " +
                "${if (lazyValue == null) "also timed out" else "completed in $lazyElapsed"})"
        } else {
            "PASS: discovered-characteristic read completed in $discoveredElapsed, " +
                "${discoveredValue.size} byte(s): ${discoveredValue.hex()} (lazy read: " +
                "${if (lazyValue == null) "TIMED OUT" else "ok in $lazyElapsed"})"
        }
    } finally {
        runCatching { peripheral.disconnect() }
        runCatching { peripheral.close() }
    }
}
