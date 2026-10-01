package dev.warsha.remoteble.agent

import dev.warsha.remoteble.protocol.AgentFingerprint
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.cert.X509Certificate

/**
 * The agent's long-lived TLS identity: an ECDSA P-256 key and its self-signed certificate.
 * [fingerprint] is what clients pin.
 *
 * [privateKey] may be a handle the key never leaves, as an Android Keystore key is: nothing here
 * reads its encoding, so the front signs through whichever provider owns it.
 */
class AgentTlsIdentity(val privateKey: PrivateKey, val certificate: X509Certificate) {
    val fingerprint: AgentFingerprint =
        AgentFingerprint.ofSpkiSha256(MessageDigest.getInstance("SHA-256").digest(certificate.publicKey.encoded))
}
