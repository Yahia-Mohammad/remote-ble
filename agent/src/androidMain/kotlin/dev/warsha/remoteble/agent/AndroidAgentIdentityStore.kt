package dev.warsha.remoteble.agent

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dev.warsha.remoteble.log.Logger
import dev.warsha.remoteble.protocol.AGENT_TLS_SERVER_NAME
import java.io.ByteArrayInputStream
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The Android agent's identity, held in Android Keystore: the private key is generated there and
 * never leaves it, and the TLS front signs with it through the keystore's own provider.
 *
 * The keystore's generator issues a self-signed certificate of its own, but it cannot carry the
 * extensions every agent certificate must: [AGENT_TLS_SERVER_NAME] above all, which the SDK's CIO
 * client verifies whatever the pin says. So the agent builds its certificate with
 * [SelfSignedCertificate], as the other agents do, signs it with the keystore key, and stores it
 * over the generated one.
 */
object AndroidAgentIdentityStore {
    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "remoteble-agent-identity"
    private val lock = Any()

    /**
     * Loads the identity, creating it on first use. [reset] discards an existing one first, which
     * changes the fingerprint and makes every paired client fail with an identity error until it
     * pairs again: that is the point of a reset.
     */
    fun loadOrCreate(reset: Boolean = false): AgentTlsIdentity = synchronized(lock) {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        if (reset) store.deleteEntry(ALIAS)
        existing(store)?.let { return it }
        // An entry with the generator's certificate is a creation interrupted before its own
        // certificate was stored. Its key was never presented to anyone, so replace it.
        store.deleteEntry(ALIAS)
        create(store)
        return existing(store)
            ?.also { Logger.info(LogTags.AGENT) { "created agent identity ${it.fingerprint}" } }
            ?: error("Android Keystore did not keep the agent certificate")
    }

    private fun existing(store: KeyStore): AgentTlsIdentity? {
        val entry = store.getEntry(ALIAS, null) as? KeyStore.PrivateKeyEntry ?: return null
        val certificate = entry.certificate as? X509Certificate ?: return null
        if (certificate.subjectAlternativeNames?.none { it[1] == AGENT_TLS_SERVER_NAME } != false) return null
        return AgentTlsIdentity(entry.privateKey, certificate)
    }

    private fun create(store: KeyStore) {
        val keys = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KEYSTORE).apply {
            initialize(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    // SHA-256 signs the certificate below. NONE is for the TLS handshake: Conscrypt
                    // hashes the transcript itself and asks the keystore to sign the digest raw.
                    .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_NONE)
                    .build(),
            )
        }.generateKeyPair()
        val der = SelfSignedCertificate.build(
            spki = keys.public.encoded,
            serial = ByteArray(16).also(SecureRandom()::nextBytes),
            // A day back, so a client whose clock runs slightly behind still sees a valid start.
            notBeforeEpochSeconds = System.currentTimeMillis() / 1000 - 86_400,
            sign = { tbs -> Signature.getInstance("SHA256withECDSA").run { initSign(keys.private); update(tbs); sign() } },
        )
        val certificate = CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(der))
        // Replaces only the certificate: the keystore keeps the key it already holds.
        store.setKeyEntry(ALIAS, keys.private, null, arrayOf(certificate))
    }
}

/** The Android agent's [AgentTlsProvider], over [AndroidAgentIdentityStore]. */
object AndroidKeystoreTls : AgentTlsProvider {
    override suspend fun load(reset: Boolean): AgentTls = withContext(Dispatchers.IO) {
        val identity = AndroidAgentIdentityStore.loadOrCreate(reset)
        AgentTls(JsseTlsFront(identity), identity.certificateSha256)
    }
}
