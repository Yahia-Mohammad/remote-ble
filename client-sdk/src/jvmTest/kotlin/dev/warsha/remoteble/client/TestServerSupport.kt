package dev.warsha.remoteble.client

import dev.warsha.remoteble.agent.AgentWebSocketServer
import io.ktor.server.engine.EmbeddedServer
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Starts [this] server and blocks until its TCP port is actually accepting connections, returning
 * the server so the caller can read [AgentWebSocketServer.resolvedPort].
 *
 * This used to poll the port with bare TCP connects, because Ktor's `start(wait = false)` returned
 * *before* CIO bound the listening socket: the transport's initial `connect()` is fire-and-forget
 * and — unlike a drop after CONNECTED — an initial failure schedules no reconnect, so a client that
 * raced ahead of the bind silently never connected and the test timed out at `awaitConnected`.
 *
 * `AgentWebSocketServer.start()` now suspends until the bind resolves and throws if it fails, so
 * the polling is gone and this is just the blocking adapter for tests that are not themselves
 * suspend.
 *
 * **Construct the server with `port = 0` and take the address from `resolvedPort` afterwards.**
 * Picking a port first with `ServerSocket(0)` and binding it a moment later is a TOCTOU race: in
 * that window anything on the host can take the port — including this JVM's own outbound sockets,
 * which the OS draws from the same ephemeral range — and the bind then fails with
 * `AgentBindException`, failing the whole build for a reason that has nothing to do with the test.
 */
internal fun AgentWebSocketServer.startAndAwaitReady(
    timeout: Duration = 5.seconds,
): AgentWebSocketServer {
    runBlocking { withTimeout(timeout) { start() } }
    return this
}

/**
 * The raw-Ktor counterpart of [startAndAwaitReady], for the few tests that stand up a bare
 * [EmbeddedServer] instead of an agent. Starts it, waits for the bind, and returns the port the
 * engine actually took — so these call sites can bind 0 as well.
 */
internal fun EmbeddedServer<*, *>.startAndResolvePort(timeout: Duration = 5.seconds): Int {
    start(wait = false)
    return runBlocking { withTimeout(timeout) { engine.resolvedConnectors() } }.first().port
}
