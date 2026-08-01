@file:OptIn(ExperimentalApi::class, ExperimentalUuidApi::class)

package dev.warsha.remoteble.e2e

import dev.warsha.remoteble.client.AgentSession
import dev.warsha.remoteble.client.DefaultAgentSession
import dev.warsha.remoteble.client.RemoteAdvertisement
import dev.warsha.remoteble.client.RemoteScanner
import dev.warsha.remoteble.client.ScanConcurrencyMode
import dev.warsha.remoteble.client.TransportState
import dev.warsha.remoteble.client.WebSocketAgentTransport
import dev.warsha.remoteble.client.awaitScanConcurrencyMode
import dev.warsha.remoteble.client.defaultWebSocketHttpClient
import dev.warsha.remoteble.protocol.CborProtocolCodec
import dev.warsha.remoteble.protocol.ScanFilter
import com.juul.kable.ExperimentalApi
import kotlin.system.exitProcess
import kotlin.time.Duration
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
import kotlinx.coroutines.withTimeoutOrNull

private const val DEFAULT_URL = "ws://localhost:8080/agent"

/** Which phase of the run an advertisement arrived in, relative to scan B's window. */
private enum class Phase { BEFORE, DURING, AFTER }

private class Observation(val device: String, val name: String?, val services: Set<String>)

private val SHORT_UUID = Regex("[0-9a-f]{4}")
private val MEDIUM_UUID = Regex("[0-9a-f]{8}")

/**
 * Expands a 16-/32-bit SIG-assigned UUID to its 128-bit form, mirroring the agent's own matcher.
 *
 * Both sides must be canonicalised or every service comparison here silently answers false: the
 * operator passes `180d` on the command line while Kable hands back the full
 * `0000180d-0000-1000-8000-00805f9b34fb`. A probe that compares them raw reports a filter leak on a
 * correctly filtered scan, and "A still saw a device without B's service" for a device that carries
 * it — i.e. it fails the check that matters and passes the one that doesn't.
 */
private fun canonicalUuid(value: String): String {
    val raw = value.lowercase()
    return when {
        SHORT_UUID.matches(raw) -> "0000$raw-0000-1000-8000-00805f9b34fb"
        MEDIUM_UUID.matches(raw) -> "$raw-0000-1000-8000-00805f9b34fb"
        else -> raw
    }
}

/**
 * Gap 21 / `SCAN-CONC` hardware probe: two concurrent scans through one agent, staggered, with a
 * verdict on both directions of the defect this feature exists to fix.
 *
 *   ./gradlew :e2e-runner:scanConcurrencyRun --args "ws://192.168.178.85:8080/agent a1b2c3d4-0000-4000-8000-000000000001"
 *
 * args: [ws-url] [service-uuid] [same-client|two-clients] [totalSeconds] [bDelaySeconds] [bWindowSeconds]
 * (token from `REMOTE_BLE_TOKEN`). Defaults: two-clients, 60 s total, B starts at 15 s for 10 s.
 *
 * **Scan A** is broad (unfiltered) and runs for the whole window. **Scan B** is service-filtered and
 * lives inside it. Two hazards are then separable, which is the entire point of staggering rather
 * than starting both at once:
 *
 * - **stop direction** — does A keep receiving advertisements *after* B stops? On one
 *   `CBCentralManager`, `stopScan()` takes no arguments, so B's stop could plausibly end A's scan.
 * - **start direction** — do A's results *narrow* to B's service while B is running? Apple documents
 *   that a second scan's parameters replace the running scan's, so this is the worse of the two and
 *   the one that is silent on both sides.
 *
 * `same-client` puts both scanners on **one session**, which is the ordinary app holding two
 * `RemoteScanner`s and is what makes gap 21 reachable without a second process; it also exercises the
 * coordinator's per-stable-client accounting. `two-clients` uses two sessions (two client keys) and is
 * the cross-client case. Run both: they take different paths through admission.
 *
 * A lone-A **baseline run** (`:e2e-runner:scanRun` for the same window) is a prerequisite, not an
 * optional extra — without it, A going quiet is unattributable to B rather than to the rig.
 */
fun main(args: Array<String>): Unit = runBlocking {
    val url = args.getOrNull(0) ?: DEFAULT_URL
    val service = args.getOrNull(1)?.takeIf { it.isNotBlank() }
        ?: error("a service UUID is required — scan B must be service-filtered for the probe to mean anything")
    val sameClient = when (val mode = args.getOrNull(2)?.takeIf { it.isNotBlank() } ?: "two-clients") {
        "same-client" -> true
        "two-clients" -> false
        else -> error("third argument must be 'same-client' or 'two-clients', got '$mode'")
    }
    val total = (args.getOrNull(3)?.toIntOrNull() ?: 60).seconds
    val bDelay = (args.getOrNull(4)?.toIntOrNull() ?: 15).seconds
    val bWindow = (args.getOrNull(5)?.toIntOrNull() ?: 10).seconds
    require(bDelay + bWindow < total) { "B's window must end before A's ($bDelay + $bWindow >= $total)" }
    val token = System.getenv("REMOTE_BLE_TOKEN")

    println("== RemoteBle concurrent-scan probe (gap 21 / SCAN-CONC) ==")
    println("agent   : $url")
    println("topology: ${if (sameClient) "same-client (one session, two RemoteScanners)" else "two-clients (two sessions)"}")
    println("scan A  : unfiltered, 0s .. ${total.inWholeSeconds}s")
    println("scan B  : service=$service, ${bDelay.inWholeSeconds}s .. ${(bDelay + bWindow).inWholeSeconds}s")
    println()

    val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    val http = defaultWebSocketHttpClient()
    fun newSession(): AgentSession = DefaultAgentSession(
        WebSocketAgentTransport(url, scope, http, authToken = { token }),
        CborProtocolCodec(),
        scope,
    )

    val sessionA = newSession()
    val sessionB = if (sameClient) sessionA else newSession()
    val sessions = if (sameClient) listOf(sessionA) else listOf(sessionA, sessionB)

    val start = TimeSource.Monotonic.markNow()
    fun phaseAt(elapsed: Duration): Phase = when {
        elapsed < bDelay -> Phase.BEFORE
        elapsed < bDelay + bWindow -> Phase.DURING
        else -> Phase.AFTER
    }

    val aByPhase = Phase.entries.associateWith { mutableListOf<Observation>() }
    val bSeen = mutableListOf<Observation>()
    val checks = mutableListOf<Pair<String, String>>()
    fun record(label: String, verdict: String, detail: String) {
        checks += label to verdict
        println("%-12s %s — %s".format(verdict, label, detail))
    }

    var failed = false
    try {
        for ((name, s) in listOf("A" to sessionA, "B" to sessionB).distinctBy { it.second }) {
            withTimeoutOrNull(15.seconds) { s.transportState.first { it == TransportState.CONNECTED } }
                ?: error("session $name never connected")
        }
        // Recorded on every run: an evidence file that does not name the negotiated mode cannot be
        // read later, because the same commands mean different things in different modes.
        val mode = withTimeoutOrNull(10.seconds) { sessionA.awaitScanConcurrencyMode() }
        println("negotiated scan concurrency: ${mode ?: "UNKNOWN (no server hello within 10s)"}")
        if (mode == ScanConcurrencyMode.LEGACY_OR_UNKNOWN) {
            println("  ! the agent advertised no scan.concurrency capability — this is a pre-0.10.0 agent")
        }
        println()

        fun observe(advertisement: RemoteAdvertisement) = Observation(
            device = advertisement.handle.value,
            name = advertisement.name,
            services = advertisement.uuids.map { canonicalUuid(it.toString()) }.toSet(),
        )

        println("• scan A starting (unfiltered)")
        val jobA = RemoteScanner(sessionA).advertisements
            .onEach { aByPhase.getValue(phaseAt(start.elapsedNow())) += observe(it) }
            .launchIn(scope)

        delay(bDelay)
        println("• scan B starting (service=$service)")
        val jobB = RemoteScanner(sessionB, listOf(ScanFilter(service = service))).advertisements
            .onEach { bSeen += observe(it) }
            .launchIn(scope)

        delay(bWindow)
        jobB.cancel()
        println("• scan B stopped")

        delay(total - bDelay - bWindow)
        jobA.cancel()
        println("• scan A stopped")
        println()

        // --- what A saw, per phase ------------------------------------------------------------
        val devicesBefore = aByPhase.getValue(Phase.BEFORE).map { it.device }.toSet()
        val devicesDuring = aByPhase.getValue(Phase.DURING).map { it.device }.toSet()
        val devicesAfter = aByPhase.getValue(Phase.AFTER).map { it.device }.toSet()
        println("A: before=${devicesBefore.size} device(s)/${aByPhase.getValue(Phase.BEFORE).size} adv, " +
            "during=${devicesDuring.size}/${aByPhase.getValue(Phase.DURING).size}, " +
            "after=${devicesAfter.size}/${aByPhase.getValue(Phase.AFTER).size}")
        println("B: ${bSeen.map { it.device }.toSet().size} device(s)/${bSeen.size} adv")
        println()

        val target = canonicalUuid(service)
        fun Observation.carriesTarget() = target in services

        // --- the prerequisite the two hazard checks depend on ------------------------------------
        if (devicesBefore.isEmpty()) {
            record("A scanned at all", "FAIL", "A saw nothing before B started — check the peripheral is advertising")
            failed = true
        } else {
            record("A scanned at all", "PASS", "${devicesBefore.size} device(s) before B started")
        }

        // --- stop direction ---------------------------------------------------------------------
        when {
            devicesBefore.isEmpty() -> record("stop direction", "INCONCLUSIVE", "no baseline traffic to compare against")
            devicesAfter.isNotEmpty() -> record(
                "stop direction",
                "PASS",
                "A kept receiving after B stopped (${devicesAfter.size} device(s))",
            )
            else -> {
                record("stop direction", "FAIL", "A received nothing after B stopped — B's stop ended A's scan")
                failed = true
            }
        }

        // --- start direction --------------------------------------------------------------------
        // The question is whether A *narrowed* to B's service while B ran. Answering it needs a
        // device that A could see before and that does NOT carry B's service; without one, a narrowed
        // scan and an unnarrowed scan are indistinguishable and the honest verdict is INCONCLUSIVE.
        val offTargetBefore = aByPhase.getValue(Phase.BEFORE).filterNot { it.carriesTarget() }.map { it.device }.toSet()
        val offTargetDuring = aByPhase.getValue(Phase.DURING).filterNot { it.carriesTarget() }.map { it.device }.toSet()
        when {
            offTargetBefore.isEmpty() -> record(
                "start direction",
                "INCONCLUSIVE",
                "every device A saw before B carries B's service — no discriminating device in range",
            )
            offTargetDuring.isNotEmpty() -> record(
                "start direction",
                "PASS",
                "A still saw ${offTargetDuring.size} device(s) without B's service while B ran",
            )
            else -> {
                record(
                    "start direction",
                    "FAIL",
                    "A saw only B's service while B ran (${offTargetBefore.size} off-target device(s) before) " +
                        "— B's filter replaced A's scan parameters",
                )
                failed = true
            }
        }

        // --- filter correctness, which is the part multiplexed actually guarantees ---------------
        val leaked = bSeen.filterNot { it.carriesTarget() }.map { it.device }.toSet()
        when {
            bSeen.isEmpty() -> record("B filter correctness", "INCONCLUSIVE", "B saw nothing — is the filtered peripheral advertising?")
            leaked.isEmpty() -> record("B filter correctness", "PASS", "every advertisement B received carries $service")
            else -> {
                record("B filter correctness", "FAIL", "B received ${leaked.size} device(s) without its service: $leaked")
                failed = true
            }
        }
    } catch (t: Throwable) {
        println()
        println("ERROR: ${t.message}")
        exitProcess(1)
    } finally {
        sessions.forEach { runCatching { it.close() } }
        scope.cancel()
        http.close()
    }

    println()
    val inconclusive = checks.count { it.second == "INCONCLUSIVE" }
    val note = if (inconclusive > 0) " ($inconclusive inconclusive — the rig could not discriminate)" else ""
    if (failed) {
        println("FAILED — ${checks.count { it.second == "FAIL" }} of ${checks.size} checks failed$note")
        exitProcess(1)
    }
    println("PASS — ${checks.count { it.second == "PASS" }}/${checks.size} checks$note")
}
