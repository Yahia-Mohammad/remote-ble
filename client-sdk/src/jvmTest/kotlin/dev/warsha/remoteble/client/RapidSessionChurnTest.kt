package dev.warsha.remoteble.client

import dev.warsha.remoteble.agent.AgentWebSocketServer
import dev.warsha.remoteble.protocol.CLIENT_ID_HEADER
import dev.warsha.remoteble.protocol.CborProtocolCodec
import dev.warsha.remoteble.protocol.ClientHello
import dev.warsha.remoteble.protocol.ServerHello
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.header
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readBytes
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.test.AfterTest
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

    @AfterTest
    fun tearDown() {
        httpClient.close()
        scope.cancel()
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun AgentWebSocketServer.startAndAwaitReady(port: Int): AgentWebSocketServer {
        runBlocking { start() }
        val deadline = System.nanoTime() + 5.seconds.inWholeNanoseconds
        while (true) {
            try {
                Socket().use { it.connect(InetSocketAddress("localhost", port), 200) }
                return this
            } catch (_: IOException) {
                check(System.nanoTime() < deadline) { "agent on port $port did not start" }
                Thread.sleep(10)
            }
        }
    }

    private suspend fun DefaultClientWebSocketSession.awaitServerHello(codec: CborProtocolCodec): ServerHello? =
        withTimeoutOrNull(HANDSHAKE_TIMEOUT) {
            while (true) {
                val frame = incoming.receive()
                if (frame !is Frame.Binary) continue
                val decoded = runCatching { codec.decode(frame.readBytes()) }.getOrNull()
                if (decoded is ServerHello) return@withTimeoutOrNull decoded
            }
            @Suppress("UNREACHABLE_CODE")
            null
        }

    @Test
    fun everyShortSessionCompletesItsHandshake() = runBlocking {
        val port = freePort()
        val server = AgentWebSocketServer(port).startAndAwaitReady(port)
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
                                "(${attempt} succeeded before it); close reason: $reason",
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
