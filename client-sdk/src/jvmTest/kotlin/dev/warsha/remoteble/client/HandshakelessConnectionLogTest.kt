package dev.warsha.remoteble.client

import dev.warsha.remoteble.agent.AgentWebSocketServer
import dev.warsha.remoteble.agent.BleAgentBackend
import dev.warsha.remoteble.log.LogLevel
import dev.warsha.remoteble.log.Logger
import dev.warsha.remoteble.protocol.CborProtocolCodec
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.websocket.close
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A connection the agent accepts but that never sends a `ClientHello` must say so in the log.
 *
 * This is the signature reported in
 * [#12](https://github.com/Yahia-Mohammad/remote-ble/issues/12): under a burst of short-lived
 * connections the agent logged `client connected` and then, for a varying subset, never logged
 * `handshake`. Nothing named the condition — diagnosing it meant noticing an *absent* line and
 * pairing it up by connection id across a few hundred lines of INFO. The agent is the only side
 * that can state this about its own accepted connections, so it now does.
 *
 * It is deliberately a warning rather than an error: the client may simply have gone away, which
 * is not the agent malfunctioning. What matters is that the condition is greppable.
 */
class HandshakelessConnectionLogTest {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val httpClient = defaultWebSocketHttpClient()
    private val captured = mutableListOf<String>()

    @AfterTest
    fun tearDown() {
        Logger.configure(level = null)
        httpClient.close()
        scope.cancel()
    }

    @Test
    fun aConnectionThatNeverSendsHelloIsNamedInTheLog() = runBlocking {
        Logger.configure(level = LogLevel.INFO) { _, _, message, _ -> captured.add(message) }
        val server = AgentWebSocketServer(port = 0, backend = BleAgentBackend(StubBleBackend()))
            .also { it.startAndAwaitReady() }
        try {
            // Connect, send nothing at all, and go away — exactly what the agent saw in #12.
            val socket = httpClient.webSocketSession(urlString = "ws://localhost:${server.resolvedPort}/agent")
            socket.close()
            val logged = withTimeoutOrNull(5.seconds) {
                while (captured.none { it.startsWith("client disconnected before sending ClientHello") }) {
                    kotlinx.coroutines.delay(20)
                }
                true
            }
            assertTrue(
                logged == true,
                "expected the agent to name the handshake-less connection; logged: $captured",
            )
        } finally {
            server.stop()
        }
        Unit
    }

    @Test
    fun anOrdinarySessionIsNotReportedAsHandshakeless() = runBlocking {
        Logger.configure(level = LogLevel.INFO) { _, _, message, _ -> captured.add(message) }
        val server = AgentWebSocketServer(port = 0, backend = BleAgentBackend(StubBleBackend()))
            .also { it.startAndAwaitReady() }
        try {
            val session = DefaultAgentSession(
                WebSocketAgentTransport("ws://localhost:${server.resolvedPort}/agent", scope, httpClient),
                CborProtocolCodec(),
                scope,
            )
            withTimeoutOrNull(10.seconds) { session.awaitCapabilities() }
            session.close()
            // Give the agent's teardown a moment to run its finally block.
            kotlinx.coroutines.delay(200)
            assertTrue(
                captured.none { it.startsWith("client disconnected before sending ClientHello") },
                "a session that handshook must not be reported as handshake-less; logged: $captured",
            )
        } finally {
            server.stop()
        }
        Unit
    }
}
