package dev.warsha.remoteble.client

import dev.warsha.remoteble.protocol.AgentFingerprint
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.WebSockets
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLPeerUnverifiedException

/**
 * A WebSocket [HttpClient] for a `wss://` agent whose identity this client pinned at pairing.
 *
 * The agent's certificate is self-signed, so no certificate authority is consulted: the connection
 * is trusted exactly when the agent's TLS key hashes to [fingerprint]. A different key fails the
 * handshake with [AgentIdentityMismatchException] before any request, and so before the bearer
 * token, is sent.
 *
 * Agents are reached by IP addresses their certificates cannot name, so OkHttp's host-name check
 * defers to the pin too. It checks the session's certificate again rather than passing everything,
 * because a resumed session skips the trust manager, and this check is what still runs.
 */
fun pinnedWebSocketHttpClient(fingerprint: AgentFingerprint): HttpClient = HttpClient(OkHttp) {
    engine {
        config {
            val trustManager = PinningTrustManager(fingerprint)
            val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustManager), null) }
            sslSocketFactory(context.socketFactory, trustManager)
            hostnameVerifier { _, session ->
                val leaf = try {
                    session.peerCertificates.firstOrNull()
                } catch (_: SSLPeerUnverifiedException) {
                    null
                }
                trustManager.matches(leaf)
            }
        }
    }
    install(WebSockets)
}
