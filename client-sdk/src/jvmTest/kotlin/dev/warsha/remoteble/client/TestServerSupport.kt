package dev.warsha.remoteble.client

import dev.warsha.remoteble.agent.AgentWebSocketServer
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Starts [this] server and blocks until its TCP port is actually accepting connections.
 *
 * This used to poll the port with bare TCP connects, because Ktor's `start(wait = false)` returned
 * *before* CIO bound the listening socket: the transport's initial `connect()` is fire-and-forget
 * and — unlike a drop after CONNECTED — an initial failure schedules no reconnect, so a client that
 * raced ahead of the bind silently never connected and the test timed out at `awaitConnected`.
 *
 * `AgentWebSocketServer.start()` now suspends until the bind resolves and throws if it fails, so
 * the polling is gone and this is just the blocking adapter for tests that are not themselves
 * suspend. The [port] parameter is kept so the call sites read unchanged.
 */
@Suppress("UNUSED_PARAMETER")
internal fun AgentWebSocketServer.startAndAwaitReady(
    port: Int,
    timeout: Duration = 5.seconds,
): AgentWebSocketServer {
    runBlocking { withTimeout(timeout) { start() } }
    return this
}
