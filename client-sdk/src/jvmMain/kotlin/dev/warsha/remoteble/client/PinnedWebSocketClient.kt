package dev.warsha.remoteble.client

import dev.warsha.remoteble.protocol.AGENT_TLS_SERVER_NAME
import dev.warsha.remoteble.protocol.AgentFingerprint
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets

/**
 * A WebSocket [HttpClient] for a `wss://` agent whose identity this client pinned at pairing.
 *
 * The agent's certificate is self-signed, so no certificate authority is consulted: the connection
 * is trusted exactly when the agent's TLS key hashes to [fingerprint]. A different key fails the
 * handshake with [AgentIdentityMismatchException] before any request, and so before the bearer
 * token, is sent. The TLS server name is [AGENT_TLS_SERVER_NAME], which every agent certificate
 * carries, because agents are reached by IP addresses their certificates cannot name.
 */
fun pinnedWebSocketHttpClient(fingerprint: AgentFingerprint): HttpClient = HttpClient(CIO) {
    engine {
        https {
            serverName = AGENT_TLS_SERVER_NAME
            trustManager = PinningTrustManager(fingerprint)
        }
    }
    install(WebSockets)
}
