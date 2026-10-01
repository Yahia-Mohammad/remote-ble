package dev.warsha.remoteble.client

import dev.warsha.remoteble.protocol.AGENT_TLS_SERVER_NAME
import dev.warsha.remoteble.protocol.AgentFingerprint
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager

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

/** Trusts exactly the leaf certificate whose SPKI hashes to [pinned]; nothing else is consulted. */
internal class PinningTrustManager(private val pinned: AgentFingerprint) : X509TrustManager {

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val leaf = chain?.firstOrNull()
        val presented = leaf?.let {
            AgentFingerprint.ofSpkiSha256(MessageDigest.getInstance("SHA-256").digest(it.publicKey.encoded))
        }
        if (presented == null || presented != pinned) {
            val mismatch = AgentIdentityMismatchException(pinned, presented)
            // A CertificateException is what TLS stacks expect a trust manager to throw; the
            // mismatch rides as its cause so the transport can recognise it.
            throw CertificateException(mismatch.message, mismatch)
        }
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        throw CertificateException("this trust manager only authenticates agents")
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}
