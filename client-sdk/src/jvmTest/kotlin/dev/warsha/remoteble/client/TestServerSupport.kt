package dev.warsha.remoteble.client

import dev.warsha.remoteble.agent.AgentWebSocketServer
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Starts [this] server and blocks until its TCP port is actually accepting connections.
 *
 * Ktor's `start(wait = false)` returns *before* CIO binds the listening socket. The transport's
 * initial `connect()` is fire-and-forget and — unlike a drop after CONNECTED — an initial failure
 * schedules no reconnect, so a client that races ahead of the bind silently never connects and the
 * test times out at `awaitConnected`. That race is the source of the occasional connection-timeout
 * flakes. Probing real readiness closes it deterministically (no fixed sleep).
 *
 * A bare TCP connect never sends the HTTP upgrade, so it never reaches the `/agent` WebSocket route
 * — it only confirms the socket is live and has no effect on the agent's client bookkeeping.
 */
internal fun AgentWebSocketServer.startAndAwaitReady(
    port: Int,
    timeout: Duration = 5.seconds,
): AgentWebSocketServer {
    start()
    val deadline = System.nanoTime() + timeout.inWholeNanoseconds
    while (true) {
        try {
            Socket().use { it.connect(InetSocketAddress("localhost", port), 200) }
            return this
        } catch (_: IOException) {
            check(System.nanoTime() < deadline) {
                "agent server on port $port did not start accepting within $timeout"
            }
            Thread.sleep(10)
        }
    }
}
