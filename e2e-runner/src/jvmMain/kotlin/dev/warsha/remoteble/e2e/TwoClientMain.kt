@file:OptIn(ExperimentalUuidApi::class)

package dev.warsha.remoteble.e2e

import dev.warsha.remoteble.client.AgentSession
import dev.warsha.remoteble.client.DefaultAgentSession
import dev.warsha.remoteble.client.RemoteScanner
import dev.warsha.remoteble.client.TransportState
import dev.warsha.remoteble.client.WebSocketAgentTransport
import dev.warsha.remoteble.client.defaultWebSocketHttpClient
import dev.warsha.remoteble.protocol.CborProtocolCodec
import dev.warsha.remoteble.protocol.CharRef
import dev.warsha.remoteble.protocol.ConnProfile
import dev.warsha.remoteble.protocol.DeviceHandle
import dev.warsha.remoteble.protocol.ErrorKind
import dev.warsha.remoteble.protocol.Op
import dev.warsha.remoteble.protocol.OpResult
import dev.warsha.remoteble.protocol.ResultPayload
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.ExperimentalUuidApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

private const val DEFAULT_URL = "ws://localhost:8080/agent"
private const val DEFAULT_ADVERTISED_NAME = "RBTestPeripheral"

/** GATT characteristic property bit for Read (Bluetooth Core spec, Vol 3 Part G 3.3.1.1). */
private const val GATT_PROP_READ = 0x02

/** The Bluetooth SIG base UUID suffix: `0000xxxx-0000-1000-8000-00805f9b34fb` is an assigned UUID. */
private const val SIG_BASE_SUFFIX = "-0000-1000-8000-00805f9b34fb"

/** True for a 16-/32-bit SIG-assigned UUID, as opposed to a vendor-specific 128-bit one. */
private fun String.isSigAssigned(): Boolean = endsWith(SIG_BASE_SUFFIX, ignoreCase = true)

/**
 * Rig A case 3 (pr8-validation-plan.md): two-client authorization on a real radio. Client A leases a
 * peripheral; client B must still be able to *scan* and see it, but every device-bearing operation
 * must be refused — `read`/`write`/`observe`/`configure`/`disconnect`, and `connect` itself.
 *
 *   ./gradlew :e2e-runner:twoClientRun --args "ws://localhost:8080/agent"
 *
 * args: [ws-url] [token] [advertised-name] (token also read from REMOTE_BLE_TOKEN).
 *
 * **Two sessions in one process is a faithful harness, not a shortcut.** Ownership is keyed on
 * `session_key(principal, clientId)`, and `WebSocketAgentTransport` mints `clientId` as a fresh
 * `Uuid.random()` per instance — so two transports are two distinct clients to the agent even on one
 * host and one token. That isolates exactly what this case is about (cross-*client* ownership) from
 * what it is not (cross-*principal* auth, already covered by AUTH-PRINCIPAL-01's paired tests). A
 * second physical device would add no coverage here.
 *
 * Ops are issued through [AgentSession.request] rather than the `Peripheral` facade on purpose: this
 * case asserts on the *typed error* the agent returns, which the higher-level API turns into
 * exceptions and partially hides.
 */
fun main(args: Array<String>): Unit = runBlocking {
    val url = args.getOrNull(0) ?: DEFAULT_URL
    val token = args.getOrNull(1)?.ifBlank { null } ?: System.getenv("REMOTE_BLE_TOKEN")
    val advertisedName = args.getOrNull(2)?.ifBlank { null } ?: DEFAULT_ADVERTISED_NAME

    println("== RemoteBle two-client authorization (Rig A case 3) ==")
    println("agent : $url")
    println("device: \"$advertisedName\"  token=${if (token != null) "set" else "none"}")
    println()

    val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    val http = defaultWebSocketHttpClient()
    fun newSession(): AgentSession = DefaultAgentSession(
        WebSocketAgentTransport(url, scope, http, authToken = { token }),
        CborProtocolCodec(),
        scope,
    )

    val a = newSession()
    val b = newSession()
    // PASS / XFAIL / FAIL. XFAIL is a *known, documented divergence* — the assertion is kept intact
    // rather than loosened to accommodate a backend (the same practice as the conformance suite's
    // gated steps), so an XPASS later is the signal that the gate is stale.
    val checks = mutableListOf<Pair<String, String>>()
    fun record(label: String, verdict: String, detail: String) {
        checks += label to verdict
        println("%-5s %s — %s".format(verdict, label, detail))
    }
    fun check(label: String, ok: Boolean, detail: String) =
        record(label, if (ok) "PASS" else "FAIL", detail)

    try {
        for ((name, s) in listOf("A" to a, "B" to b)) {
            withTimeoutOrNull(15.seconds) { s.transportState.first { it == TransportState.CONNECTED } }
                ?: error("client $name transport never connected")
        }
        println("both clients connected (distinct clientIds → distinct sessions)")

        // --- Client A takes the lease -------------------------------------------------------
        val adv = withTimeoutOrNull(30.seconds) {
            RemoteScanner(a).advertisements.first { it.name == advertisedName }
        } ?: error("client A scan timed out — is the peripheral advertising?")
        val device = adv.handle
        println("A scanned $advertisedName -> ${device.value}")

        val connected = a.request(Op.Connect(device))
        require(connected is OpResult.Ok) { "client A could not connect: $connected" }
        println("A connected (holds the lease)")

        // A discovers so B has a real characteristic to aim at — a non-owner would plausibly know
        // one from documentation or an earlier session, so this is not privileged information.
        val services = (a.request(Op.Discover(device)) as? OpResult.Ok)?.payload as? ResultPayload.Services
            ?: error("client A could not discover")
        // Prefer a *vendor* service over a SIG-assigned one. An Android phone acting as a peripheral
        // also exposes the platform's own 16-bit SIG services (Volume Control, GATT, …), and those
        // routinely require encryption — reading one raises a pairing dialog, and with nobody to
        // answer it the read stalls until GATT_OP_TIMEOUT. Picking "the first readable
        // characteristic" walked straight into 00001849/00002b93 and cost a run (Rig A, 2026-07-28).
        val readable = { svc: dev.warsha.remoteble.protocol.ServiceNode ->
            svc.characteristics.firstOrNull { it.properties and GATT_PROP_READ != 0 }
                ?.let { CharRef(svc.uuid, it.uuid) }
        }
        val target = services.services.filterNot { it.uuid.isSigAssigned() }.firstNotNullOfOrNull(readable)
            ?: services.services.firstNotNullOfOrNull(readable)
            ?: error("no readable characteristic on the peripheral")
        println("target characteristic: ${target.service}/${target.characteristic}")
        println()

        // --- Client B: scanning is allowed ---------------------------------------------------
        val seenByB = withTimeoutOrNull(30.seconds) {
            RemoteScanner(b).advertisements.first { it.handle == device }
        }
        check(
            "B can still scan and see the leased device",
            seenByB != null,
            if (seenByB != null) "saw ${seenByB.name} (${device.value})" else "did not see it within 30s",
        )

        // --- Client B: every device-bearing op must be refused -------------------------------
        // PERIPHERAL_BUSY is the expected kind: the registry knows the device is leased to someone
        // else. NOT_CONNECTED would mean the agent lost the lease, which is a different failure.
        val refusals = listOf(
            "connect" to Op.Connect(device),
            "read" to Op.Read(device, target),
            "write" to Op.Write(device, target, byteArrayOf(0x01), withResponse = true),
            "observe" to Op.ObserveStart(b.nextStreamId(), device, target),
            "discover" to Op.Discover(device),
            "rssi" to Op.ReadRssi(device),
            "configure" to Op.SetConnParams(device, ConnProfile.BALANCED),
            "disconnect" to Op.Disconnect(device),
        )
        for ((label, op) in refusals) {
            val result = b.request(op)
            val err = (result as? OpResult.Err)?.error
            val shown = err?.let { "${it.kind}${it.message?.let { m -> " (\"$m\")" } ?: ""}" }
            when {
                // The only real failure: a non-owner got through.
                err == null -> record("B's $label is refused", "FAIL", "B was ALLOWED to $label — got $result")
                err.kind == ErrorKind.PERIPHERAL_BUSY -> record("B's $label is refused", "PASS", shown!!)
                // `agent-rs` used to answer UNSUPPORTED here for ops it does not implement, from a
                // catch-all arm that ran before any authorization check — so an unowned device got a
                // capability answer instead of PERIPHERAL_BUSY. Fixed 2026-07-28 (it now authorizes
                // first, matching the Kotlin agent) and confirmed on Rig A, so this is a plain
                // failure again rather than a gated divergence.
                else -> record("B's $label is refused", "FAIL", "expected PERIPHERAL_BUSY, got $shown")
            }
        }

        // --- A still owns it, and releasing hands it over -------------------------------------
        val aStillWorks = a.request(Op.Read(device, target))
        check(
            "A's own read still works while B is refused",
            aStillWorks is OpResult.Ok,
            if (aStillWorks is OpResult.Ok) "Ok" else "$aStillWorks",
        )

        require(a.request(Op.Disconnect(device)) is OpResult.Ok) { "client A could not release" }
        println("A released the lease")
        val bAfterRelease = b.request(Op.Connect(device))
        val handoverErr = (bAfterRelease as? OpResult.Err)?.error
        when {
            bAfterRelease is OpResult.Ok -> record("B can connect once A releases", "PASS", "Ok")
            // Still gated: agent-rs resolves a handle by scanning `adapter.peripherals()`, and
            // btleplug drops a peripheral from that list once it disconnects with no scan running,
            // so the handle stops resolving and a client must rescan before it can reconnect. The
            // Kotlin agent builds a Kable Peripheral from the identifier and has no such dependency.
            // Caching connected peripherals to close the gap was tried and reverted — see
            // `BtleplugBackend::find_peripheral` for why the cure was worse.
            handoverErr?.kind == ErrorKind.UNKNOWN_DEVICE -> record(
                "B can connect once A releases",
                "XFAIL",
                "UNKNOWN_DEVICE — agent-rs needs a rescan to re-resolve a handle after disconnect",
            )
            else -> record("B can connect once A releases", "FAIL", "$bAfterRelease")
        }
        if (bAfterRelease is OpResult.Ok) b.request(Op.Disconnect(device))
    } catch (t: Throwable) {
        println()
        println("ERROR: ${t.message}")
        exitProcess(1)
    } finally {
        runCatching { a.close() }
        runCatching { b.close() }
        scope.cancel()
        http.close()
    }

    println()
    val failed = checks.count { it.second == "FAIL" }
    val xfail = checks.count { it.second == "XFAIL" }
    val passed = checks.count { it.second == "PASS" }
    val gated = if (xfail > 0) ", $xfail gated (known divergence)" else ""
    if (failed == 0) {
        println("PASS — $passed/${checks.size} checks$gated; cross-client authorization holds on real radio")
    } else {
        println("FAILED — $failed of ${checks.size} checks failed$gated")
        exitProcess(1)
    }
}
