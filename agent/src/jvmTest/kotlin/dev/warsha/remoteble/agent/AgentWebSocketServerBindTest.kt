package dev.warsha.remoteble.agent

import java.net.InetSocketAddress
import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Bind reporting (Rig B follow-up 16). Before this, `start()` returned `Started` and the bind
 * failure landed on a CIO worker with no handler — unreportable on the JVM and fatal on
 * Kotlin/Native, where it aborted the process.
 */
class AgentWebSocketServerBindTest {

    @Test
    fun startReportsAPortHeldByAnotherProcess() = runBlocking {
        // A plain ServerSocket stands in for "another app" — the case fixing finding 8 did *not*
        // remove, and the reason this is worth its own mechanism rather than just a teardown fix.
        ServerSocket().use { squatter ->
            squatter.bind(InetSocketAddress("127.0.0.1", 0))
            val port = squatter.localPort
            val server = AgentWebSocketServer(port = port, host = "127.0.0.1")

            val failure = assertFailsWith<AgentBindException> { server.start() }

            assertEquals(port, failure.port)
            assertEquals("127.0.0.1", failure.host)
            // A real bind error, not the timeout path — the two are distinguishable on purpose.
            assertTrue(failure.cause != null, "expected the underlying bind error as the cause")
        }
    }

    @Test
    fun aFailedStartDoesNotWedgeTheServerForALaterOne() = runBlocking {
        // The mobile runner builds a fresh graph per start, but the *user* sees one agent: a failed
        // Start followed by a good one must work. This asserts the recovery path rather than the
        // internal cleanup — an earlier version of this test claimed `stop()` after a failed start
        // was a no-op, which no mutation could falsify because stopping an already-stopped engine
        // is harmless either way. It proved nothing and has been replaced.
        ServerSocket().use { squatter ->
            squatter.bind(InetSocketAddress("127.0.0.1", 0))
            val doomed = AgentWebSocketServer(port = squatter.localPort, host = "127.0.0.1")
            assertFailsWith<AgentBindException> { doomed.start() }
            doomed.stop() // the runner's teardown calls this unconditionally; it must not throw
        }
        val server = AgentWebSocketServer(port = 0, host = "127.0.0.1")
        try {
            server.start()
            java.net.Socket().use { it.connect(InetSocketAddress("127.0.0.1", server.resolvedPort), 500) }
        } finally {
            server.stop()
        }
    }

    @Test
    fun startSucceedsAndIsListeningWhenItReturns() = runBlocking {
        val server = AgentWebSocketServer(port = 0, host = "127.0.0.1")
        try {
            server.start()
            // The point of suspending: by the time start() returns the socket is genuinely
            // accepting, so no caller has to poll for readiness.
            java.net.Socket().use { it.connect(InetSocketAddress("127.0.0.1", server.resolvedPort), 500) }
        } finally {
            server.stop()
        }
    }

    @Test
    fun bindingZeroReportsThePortTheOsActuallyChose() = runBlocking {
        // Port 0 is what every caller that just needs *a* port should ask for: the OS assigns one
        // during the bind, so there is no window between choosing a port and taking it. Probing
        // first (ServerSocket(0), note the port, close, bind it) can lose that port to anything on
        // the host — including this process's own outbound sockets, which the OS draws from the
        // same ephemeral range — and the bind then fails with AgentBindException.
        val server = AgentWebSocketServer(port = 0, host = "127.0.0.1")
        assertEquals(0, server.resolvedPort, "before start it must report what was asked for")
        try {
            server.start()
            val bound = server.resolvedPort
            assertTrue(bound > 0, "expected a real port after a successful bind, got $bound")
            // Not merely a plausible number: it is the port that accepts connections.
            java.net.Socket().use { it.connect(InetSocketAddress("127.0.0.1", bound), 500) }
        } finally {
            server.stop()
        }
    }

    @Test
    fun aPortFreedByStopCanBeBoundAgain() = runBlocking {
        // The Stop -> Start sequence from Rig B case 4, now asserted at the server level: if stop()
        // did not truly release the socket, this second start() would raise AgentBindException
        // instead of hanging or aborting.
        val first = AgentWebSocketServer(port = 0, host = "127.0.0.1")
        first.start()
        val port = first.resolvedPort // the port under test is the one stop() must release
        first.stop()
        val second = AgentWebSocketServer(port = port, host = "127.0.0.1")
        try {
            second.start()
        } finally {
            second.stop()
        }
    }
}
