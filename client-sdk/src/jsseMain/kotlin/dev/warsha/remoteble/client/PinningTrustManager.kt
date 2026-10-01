package dev.warsha.remoteble.client

import dev.warsha.remoteble.protocol.AgentFingerprint
import java.security.MessageDigest
import java.security.cert.Certificate
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager

/** Trusts exactly the leaf certificate whose SPKI hashes to [pinned]; nothing else is consulted. */
internal class PinningTrustManager(private val pinned: AgentFingerprint) : X509TrustManager {

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val presented = chain?.firstOrNull()?.let(::fingerprintOf)
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

    /** Whether [certificate] carries the pinned key, for checks made outside the handshake. */
    fun matches(certificate: Certificate?): Boolean =
        certificate is X509Certificate && fingerprintOf(certificate) == pinned

    private fun fingerprintOf(certificate: X509Certificate): AgentFingerprint =
        AgentFingerprint.ofSpkiSha256(MessageDigest.getInstance("SHA-256").digest(certificate.publicKey.encoded))
}
