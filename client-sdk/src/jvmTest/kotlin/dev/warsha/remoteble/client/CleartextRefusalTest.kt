package dev.warsha.remoteble.client

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.plugin
import io.ktor.client.plugins.websocket.WebSockets
import java.io.IOException
import java.net.UnknownServiceException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

/**
 * Android's cleartext policy can't be exercised off-device, so these raise OkHttp's exact refusal
 * from inside the client's send pipeline and check the transport treats it as a configuration
 * failure: named, immediate, and never retried.
 */
class CleartextRefusalTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun tearDown() = scope.cancel()

    private fun refusingClient(attempts: AtomicInteger, wrap: Boolean = false): HttpClient =
        HttpClient(CIO) { install(WebSockets) }.apply {
            plugin(HttpSend).intercept {
                attempts.incrementAndGet()
                val refusal = UnknownServiceException(
                    "CLEARTEXT communication to 192.168.1.20 not permitted by network security policy",
                )
                throw if (wrap) IOException("WebSocket upgrade failed", refusal) else refusal
            }
        }

    @Test
    fun aPolicyRefusalGivesUpAtOnceDespiteReconnectBeingEnabled() = runBlocking {
        val attempts = AtomicInteger()
        val gaveUp = AtomicInteger()
        val transport = WebSocketAgentTransport(
            url = "ws://192.168.1.20:8080/agent",
            scope = scope,
            httpClient = refusingClient(attempts),
            reconnect = ReconnectPolicy(
                backoff = Backoff(10.milliseconds, 20.milliseconds),
                onGaveUp = { gaveUp.incrementAndGet() },
            ),
        )

        val thrown = assertFailsWith<CleartextTrafficNotPermittedException> { transport.connect() }

        assertEquals("ws://192.168.1.20:8080/agent", thrown.url)
        assertEquals(TransportState.GAVE_UP, transport.state.value)
        assertEquals(1, gaveUp.get())
        // Well past several backoff periods: a reconnect loop would have tried again by now.
        delay(200.milliseconds)
        assertEquals(1, attempts.get())
    }

    @Test
    fun aRefusalWrappedByTheEngineIsStillRecognised() = runBlocking {
        val transport = WebSocketAgentTransport(
            url = "ws://agent.local/agent",
            scope = scope,
            httpClient = refusingClient(AtomicInteger(), wrap = true),
            reconnect = ReconnectPolicy.None,
        )

        assertFailsWith<CleartextTrafficNotPermittedException> { transport.connect() }
        assertEquals(TransportState.GAVE_UP, transport.state.value)
    }

    @Test
    fun anOrdinaryConnectFailureIsNotMistakenForARefusal() {
        val refused = IOException("Connection refused")

        assertNull(refused.asCleartextRefusal("ws://agent.local/agent"))
    }

    @Test
    fun theOriginalFailureIsKeptAsTheCause() {
        val original = IOException(
            "upgrade failed",
            UnknownServiceException("CLEARTEXT communication to host not permitted by network security policy"),
        )

        assertSame(original, original.asCleartextRefusal("ws://host/agent")?.cause)
    }
}
