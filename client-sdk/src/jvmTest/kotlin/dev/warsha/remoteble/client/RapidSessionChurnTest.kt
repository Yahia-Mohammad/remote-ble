package dev.warsha.remoteble.client

import dev.warsha.remoteble.agent.AgentWebSocketServer
import dev.warsha.remoteble.protocol.CLIENT_ID_HEADER
import dev.warsha.remoteble.protocol.CborProtocolCodec
import dev.warsha.remoteble.protocol.ClientHello
import dev.warsha.remoteble.protocol.ServerHello
import dev.warsha.remoteble.log.Logger
import dev.warsha.remoteble.log.LogLevel
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.header
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readBytes
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Reproduction for the acceptance-suite TRANSPORT_LOST failures.
 *
 * The `remote-ble-tools` live-agent suite runs each scenario as a separate short-lived CLI process
 * against one long-lived agent, so the agent sees a burst of 35-45 connect/handshake/disconnect
 * cycles in about a minute. On the two-core CI runner a varying subset of those (1-8 across
 * otherwise identical runs) fails with `TRANSPORT_LOST`, and the agent log shows the matching
 * connections logging `client connected` and never `handshake` -- so the socket is accepted and the
 * ClientHello exchange never completes. The agent logs no warning for them: the run logs are pure
 * INFO, which rules out the duplicate-live-session rejection (that path logs at warn).
 *
 * This drives the same shape directly. Each iteration is one short session that must reach
 * ServerHello, reusing a stable client id the way the CLI does, because the agent keys ownership on
 * `sessionKey(principal, stableClientId)` and Ktor writes the 101 before the route body -- and so
 * before `tryAcquire` -- ever runs.
 *
 * It is deliberately not asserting timing. It asserts that every session completes its handshake;
 * a failure prints which iteration dropped and how many preceded it.
 *
 * NOTE: this passes on an unloaded developer machine -- 80 sessions in about a second -- and
 * saturating every core does not change that, so the scheduler alone is not the trigger. It needs
 * the two-core CI runner to be useful, which is why it belongs in a job there rather than in a
 * local-only investigation.
 */
class RapidSessionChurnTest {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val httpClient = defaultWebSocketHttpClient()

    // Agent and client share one Logger, so a single sink captures both halves of the handshake
    // window — the whole point of the instrumentation added for #12. Without this the harness can
    // report *that* a session failed but not which side let go of the frame.
    private val log = mutableListOf<String>()

    @BeforeTest
    fun captureLogs() {
        Logger.configure(level = LogLevel.INFO) { level, tag, message, _ ->
            synchronized(log) { log.add("$level [$tag] $message") }
        }
    }

    @AfterTest
    fun tearDown() {
        Logger.configure(level = null)
        httpClient.close()
        scope.cancel()
    }

    /** What the two sides said about the window, plus enough tail to see the ordering. */
    private fun diagnosis(): String {
        val lines = synchronized(log) { log.toList() }
        val agentSawNoHello = lines.filter { "before sending ClientHello" in it }
        val clientFailedToSend = lines.filter { "hello NOT sent" in it }
        return buildString {
            appendLine()
            appendLine("agent reported ${agentSawNoHello.size} connection(s) closed before a ClientHello arrived")
            appendLine("client reported ${clientFailedToSend.size} hello send failure(s)")
            appendLine(
                when {
                    agentSawNoHello.isNotEmpty() && clientFailedToSend.isNotEmpty() ->
                        "=> both: the client could not write the frame (look at the client's send path)"
                    agentSawNoHello.isNotEmpty() ->
                        "=> agent only: the frame was lost in flight or on the agent's read path"
                    clientFailedToSend.isNotEmpty() ->
                        "=> client only: the write failed and the agent never saw the connection get that far"
                    else ->
                        "=> neither: this is not the #12 signature; read the tail below"
                },
            )
            appendLine("last 15 log lines:")
            lines.takeLast(15).forEach { appendLine("  $it") }
        }
    }

    private suspend fun DefaultClientWebSocketSession.awaitServerHello(codec: CborProtocolCodec): ServerHello? =
        withTimeoutOrNull(HANDSHAKE_TIMEOUT) {
            while (true) {
                // receiveCatching, not receive: when the socket dies mid-handshake Ktor *cancels*
                // this channel, and `receive()` then throws a CancellationException that travels
                // straight through withTimeoutOrNull and out of the test — reporting
                // `CancellationException at BufferedChannel.kt` instead of the diagnosis this
                // harness exists to produce. That is exactly what the first CI reproduction did
                // (2026-08-18), which cost the run its evidence.
                val frame = incoming.receiveCatching().getOrNull() ?: return@withTimeoutOrNull null
                if (frame !is Frame.Binary) continue
                val decoded = runCatching { codec.decode(frame.readBytes()) }.getOrNull()
                if (decoded is ServerHello) return@withTimeoutOrNull decoded
            }
            @Suppress("UNREACHABLE_CODE")
            null
        }

    @Test
    fun everyShortSessionCompletesItsHandshake() = runBlocking {
        // Port 0, not a probed one: this harness churns sockets as fast as it can, which is the
        // worst place to leave a window between choosing a port and binding it.
        val server = AgentWebSocketServer(port = 0).also { it.startAndAwaitReady() }
        val port = server.resolvedPort
        val codec = CborProtocolCodec()
        try {
            repeat(SESSIONS) { attempt ->
                val socket = httpClient.webSocketSession(urlString = "ws://localhost:$port/agent") {
                    // The CLI reuses one stable id per logical client across its many processes.
                    header(CLIENT_ID_HEADER, STABLE_CLIENT_ID)
                }
                try {
                    socket.send(Frame.Binary(fin = true, data = codec.encode(ClientHello())))
                    if (socket.awaitServerHello(codec) == null) {
                        val reason = withTimeoutOrNull(1.seconds) { socket.closeReason.await() }
                        fail(
                            "session $attempt of $SESSIONS never completed its handshake " +
                                "($attempt succeeded before it); close reason: $reason" +
                                diagnosis(),
                        )
                    }
                } finally {
                    socket.close()
                }
            }
        } finally {
            server.stop()
        }
        Unit
    }

    private companion object {
        /** Comfortably above the 35-45 the acceptance suite produces in roughly a minute. */
        const val SESSIONS = 80
        const val STABLE_CLIENT_ID = "churn-client"
        val HANDSHAKE_TIMEOUT = 5.seconds
    }
}
