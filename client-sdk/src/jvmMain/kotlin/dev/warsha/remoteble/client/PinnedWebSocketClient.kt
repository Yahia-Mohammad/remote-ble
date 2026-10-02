package dev.warsha.remoteble.client

import dev.warsha.remoteble.protocol.AGENT_TLS_SERVER_NAME
import dev.warsha.remoteble.protocol.AgentFingerprint
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets

/**
 * JVM: Ktor CIO with a trust manager that accepts exactly the pinned key. CIO verifies the TLS server
 * name whatever the trust manager decides, so this presents [AGENT_TLS_SERVER_NAME], which every agent
 * certificate carries. CIO speaks TLS 1.2 at most.
 */
actual fun pinnedWebSocketHttpClient(fingerprint: AgentFingerprint): HttpClient = HttpClient(CIO) {
    engine {
        https {
            serverName = AGENT_TLS_SERVER_NAME
            trustManager = PinningTrustManager(fingerprint)
        }
    }
    install(WebSockets)
}
