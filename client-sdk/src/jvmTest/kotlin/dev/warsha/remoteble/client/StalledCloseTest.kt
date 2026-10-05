package dev.warsha.remoteble.client

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import java.net.InetAddress
import java.net.ServerSocket
import java.security.MessageDigest
import java.util.Base64
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * [DefaultAgentSession.close] holds its close lock, uncancellably, across
 * [WebSocketAgentTransport.close]: requests wait on that lock, so a close that waited for a peer
 * that stopped reading would stall the whole session until TCP gave up. It does not today, even
 * with the close frame queued behind a backlog far past the socket buffers; this keeps it so.
 *
 * CIO, the engine Android uses for `ws://`; OkHttp only enqueues its close frame.
 */
class StalledCloseTest {

    @Test
    fun closeIsBoundedWhenThePeerStopsReading() = runBlocking<Unit> {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val peer = thread(isDaemon = true) {
            // Completes the upgrade, then never reads again: the stalled peer.
            val socket = server.accept()
            val reader = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
            val key = generateSequence { reader.readLine() }.takeWhile { it.isNotEmpty() }
                .first { it.startsWith("Sec-WebSocket-Key:", ignoreCase = true) }.substringAfter(':').trim()
            val accept = Base64.getEncoder().encodeToString(
                MessageDigest.getInstance("SHA-1").digest("${key}258EAFA5-E914-47DA-95CA-C5AB0DC85B11".toByteArray()),
            )
            socket.getOutputStream().apply {
                write("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\n\r\n".toByteArray())
                flush()
            }
            try { Thread.sleep(60_000) } catch (_: InterruptedException) {}
            socket.close()
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val client = HttpClient(CIO) { install(WebSockets) }
        try {
            val transport = WebSocketAgentTransport(
                "ws://127.0.0.1:${server.localPort}/agent", scope, client, reconnect = ReconnectPolicy.None,
            )
            transport.connect()
            assertEquals(TransportState.CONNECTED, transport.state.value)
            // Ktor queues outgoing frames without bound and its close flushes that queue first:
            // a backlog past the socket buffers keeps the close frame waiting on the stalled peer.
            val frame = ByteArray(1024 * 1024)
            repeat(64) { transport.send(frame) }
            val started = TimeSource.Monotonic.markNow()
            withTimeout(15.seconds) { transport.close() }
            val took = started.elapsedNow()
            assertTrue(took < 10.seconds, "close waited $took on a peer that stopped reading")
            assertEquals(TransportState.DISCONNECTED, transport.state.value)
        } finally {
            client.close()
            scope.cancel()
            peer.interrupt()
            server.close()
        }
    }
}
