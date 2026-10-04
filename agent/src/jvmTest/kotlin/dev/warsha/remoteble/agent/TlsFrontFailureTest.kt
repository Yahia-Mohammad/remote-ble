package dev.warsha.remoteble.agent

import dev.warsha.remoteble.protocol.AgentFingerprint
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * A front whose listener fails after starting has no caller left to throw to. The failure must still
 * reach the activity log, the one place a phone agent's user can see it: the phone apps configure no
 * Logger sink.
 */
class TlsFrontFailureTest {

    @Test
    fun aListenerFailingAfterStartReachesTheActivityLog() = runBlocking {
        var fail: ((String) -> Unit)? = null
        val front = object : TlsFront.Factory {
            override val fingerprint = AgentFingerprint.ofSpkiSha256(ByteArray(32))
            override suspend fun start(host: String, port: Int, upstreamPort: Int, onFailure: (reason: String) -> Unit): TlsFront {
                fail = onFailure
                return object : TlsFront {
                    override val port = 1
                    override fun peerOf(relayPort: Int): PeerAddress? = null
                    override fun stop() = Unit
                }
            }
        }
        val monitor = AgentMonitor()
        val server = AgentWebSocketServer(port = 0, monitor = monitor, tls = front)
        server.start()
        try {
            fail!!("network changed")

            val logged = monitor.snapshot().logs.map { it.message }
            assertTrue(logged.any { "TLS listener failed" in it && "network changed" in it }, "activity log: $logged")
        } finally {
            server.stop()
        }
    }
}
