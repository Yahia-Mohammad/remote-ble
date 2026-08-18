package dev.warsha.remoteble.client

import dev.warsha.remoteble.log.LogLevel
import dev.warsha.remoteble.log.Logger
import dev.warsha.remoteble.protocol.CborProtocolCodec
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A `ClientHello` that fails to send leaves the session unable to negotiate, and nothing retries:
 * the socket is still open, so no drop fires, and `capabilities` stays null for the life of the
 * connection. The session used to log `hello sent` regardless and put the actual failure at `debug`,
 * so the one line that was true was the one nobody had enabled.
 *
 * From the agent's side this is indistinguishable from a client that never sent a hello — the
 * signature in [#12](https://github.com/Yahia-Mohammad/remote-ble/issues/12) — which is why the
 * claim has to be accurate on this side rather than merely reassuring.
 */
class HelloSendFailureLogTest {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val captured = mutableListOf<String>()

    @AfterTest
    fun tearDown() {
        Logger.configure(level = null)
        scope.cancel()
    }

    /** Connects, reports CONNECTED, and refuses every send — a live socket that cannot be written. */
    private class UnwritableTransport : AgentTransport {
        private val _state = MutableStateFlow(TransportState.DISCONNECTED)
        override val state: StateFlow<TransportState> = _state
        override val incoming: Flow<ByteArray> = emptyFlow()
        override suspend fun connect() {
            _state.value = TransportState.CONNECTED
        }
        override suspend fun send(frame: ByteArray): Unit = throw TransportClosedException("write refused")
        override suspend fun close() {
            _state.value = TransportState.DISCONNECTED
        }
    }

    @Test
    fun aFailedHelloIsWarnedAboutAndNotReportedAsSent() = runBlocking {
        Logger.configure(level = LogLevel.INFO) { _, _, message, _ -> captured.add(message) }
        val transport = UnwritableTransport()
        val session = DefaultAgentSession(transport, CborProtocolCodec(), scope)
        try {
            transport.connect()
            val warned = withTimeoutOrNull(5.seconds) {
                while (captured.none { it.startsWith("hello NOT sent") }) delay(20)
                true
            }
            assertTrue(warned == true, "expected a warning naming the failed hello; logged: $captured")
            assertTrue(
                captured.none { it.startsWith("hello sent") },
                "a hello that failed must not be reported as sent; logged: $captured",
            )
        } finally {
            session.close()
        }
        Unit
    }
}
