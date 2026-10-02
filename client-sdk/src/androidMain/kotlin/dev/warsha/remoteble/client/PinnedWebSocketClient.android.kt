package dev.warsha.remoteble.client

import dev.warsha.remoteble.protocol.AgentFingerprint
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.WebSockets
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLPeerUnverifiedException

/**
 * Android: OkHttp with the trust manager the JVM client uses, accepting exactly the pinned key.
 * Agents are reached by IP addresses their certificates cannot name, so OkHttp's host-name check
 * defers to the pin too. It checks the session's certificate again rather than passing everything,
 * because a resumed session skips the trust manager, and this check is what still runs.
 */
actual fun pinnedWebSocketHttpClient(fingerprint: AgentFingerprint): HttpClient = HttpClient(OkHttp) {
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
