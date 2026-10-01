package dev.warsha.remoteble.e2e

import dev.warsha.remoteble.client.AgentIdentityMismatchException
import dev.warsha.remoteble.client.DefaultAgentSession
import dev.warsha.remoteble.client.ReconnectPolicy
import dev.warsha.remoteble.client.RemoteScanner
import dev.warsha.remoteble.client.TransportState
import dev.warsha.remoteble.client.WebSocketAgentTransport
import dev.warsha.remoteble.client.pinnedWebSocketHttpClient
import dev.warsha.remoteble.protocol.AgentFingerprint
import dev.warsha.remoteble.protocol.CborProtocolCodec
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * The pinned-TLS acceptance check against a live agent (`TLS-PIN-01` and `02`, transport
 * encryption phases 2 and 3): the SDK's pinned client connects to an agent serving `wss://` and
 * runs a scan through it, then a client pinning a different fingerprint is refused with the identity
 * error and gives up at once. Point it at a phone agent with encryption switched on, using the
 * fingerprint its screen shows.
 *
 *   REMOTE_BLE_TOKEN=secret ./gradlew :e2e-runner:pinRun --args "wss://192.168.1.23:8080/agent sha256:<hex>"
 *
 * args: <wss-url> <fingerprint> [token] (token also read from REMOTE_BLE_TOKEN).
 */
fun main(args: Array<String>): Unit = runBlocking {
    val url = args.getOrNull(0) ?: usage()
    val pin = args.getOrNull(1)?.let(AgentFingerprint::parseOrNull) ?: usage()
    val token = args.getOrNull(2)?.ifBlank { null } ?: System.getenv("REMOTE_BLE_TOKEN")
    println("== RemoteBle pinned TLS check (TLS-PIN-01, 02) ==")
    println("agent: $url  pin: $pin  token=${if (token != null) "set" else "none"}")

    val pinned = runCatching { connectAndScan(url, pin, token) }
    println("• pinned client: ${pinned.fold({ "connected, scan ran ($it advertisement(s))" }, { "FAIL ${it::class.simpleName}: ${it.message}" })}")

    val wrong = otherThan(pin)
    val refused = runCatching { refusedWith(url, wrong, token) }
    println("• wrong pin $wrong: ${refused.fold({ it }, { "FAIL ${it::class.simpleName}: ${it.message}" })}")

    val passed = pinned.isSuccess && refused.isSuccess
    println(if (passed) "RESULT: PASS" else "RESULT: FAIL")
    exitProcess(if (passed) 0 else 1)
}

private suspend fun connectAndScan(url: String, pin: AgentFingerprint, token: String?): Int {
    val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    val http = pinnedWebSocketHttpClient(pin)
    try {
        val session = DefaultAgentSession(WebSocketAgentTransport(url, scope, http, authToken = { token }), CborProtocolCodec(), scope)
        // GAVE_UP ends the wait too: a refused pin gives up at once, and saying so beats a timeout.
        val state = withTimeout(15.seconds) {
            session.transportState.first { it == TransportState.CONNECTED || it == TransportState.GAVE_UP }
        }
        check(state == TransportState.CONNECTED) { "the transport gave up; is this the agent's current fingerprint?" }
        // A scan is an op the agent must accept over the encrypted channel; finding nothing nearby
        // still proves that, so the count is reported but not required.
        var seen = 0
        val job = RemoteScanner(session).advertisements.onEach { seen++ }.launchIn(scope)
        delay(5.seconds)
        if (job.isCancelled) error("scan failed")
        job.cancel()
        return seen
    } finally {
        http.close()
        scope.cancel()
    }
}

private suspend fun refusedWith(url: String, wrong: AgentFingerprint, token: String?): String {
    val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    val http = pinnedWebSocketHttpClient(wrong)
    try {
        val transport = WebSocketAgentTransport(url, scope, http, authToken = { token }, reconnect = ReconnectPolicy())
        val failure = runCatching { withTimeout(15.seconds) { transport.connect() } }.exceptionOrNull()
        check(failure is AgentIdentityMismatchException) { "expected the identity error, got $failure" }
        check(transport.state.value == TransportState.GAVE_UP) { "expected GAVE_UP, got ${transport.state.value}" }
        return "refused, presented ${failure.presented}, GAVE_UP"
    } finally {
        http.close()
        scope.cancel()
    }
}

/** The pin with its last hex digit changed: a fingerprint this agent cannot present. */
private fun otherThan(pin: AgentFingerprint): AgentFingerprint {
    val text = pin.toString()
    val last = if (text.last() == '0') '1' else '0'
    return AgentFingerprint.parse(text.dropLast(1) + last)
}

private fun usage(): Nothing {
    System.err.println("usage: pinRun <wss-url> <sha256:fingerprint> [token]")
    exitProcess(2)
}
