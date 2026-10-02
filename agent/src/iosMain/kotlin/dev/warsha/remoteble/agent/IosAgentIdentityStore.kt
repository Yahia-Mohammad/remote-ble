@file:OptIn(ExperimentalForeignApi::class)

package dev.warsha.remoteble.agent

import dev.warsha.remoteble.log.Logger
import dev.warsha.remoteble.protocol.AgentFingerprint
import kotlin.random.Random
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.CoreCrypto.CC_SHA256
import platform.CoreCrypto.CC_SHA256_DIGEST_LENGTH
import platform.CoreFoundation.CFDataCreate
import platform.CoreFoundation.CFDataGetBytes
import platform.CoreFoundation.CFDataGetLength
import platform.CoreFoundation.CFDataRef
import platform.CoreFoundation.CFDictionaryAddValue
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFErrorRefVar
import platform.CoreFoundation.CFNumberCreate
import platform.CoreFoundation.CFRangeMake
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringCreateWithCString
import platform.CoreFoundation.CFStringRef
import platform.CoreFoundation.CFTypeRef
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFNumberIntType
import platform.CoreFoundation.kCFStringEncodingUTF8
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Security.SecCertificateCopyKey
import platform.Security.SecCertificateCreateWithData
import platform.Security.SecCertificateRef
import platform.Security.SecIdentityCopyCertificate
import platform.Security.SecIdentityRef
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.SecKeyCopyExternalRepresentation
import platform.Security.SecKeyCopyPublicKey
import platform.Security.SecKeyCreateRandomKey
import platform.Security.SecKeyCreateSignature
import platform.Security.SecKeyRef
import platform.Security.errSecItemNotFound
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
import platform.Security.kSecAttrApplicationTag
import platform.Security.kSecAttrIsPermanent
import platform.Security.kSecAttrKeySizeInBits
import platform.Security.kSecAttrKeyType
import platform.Security.kSecAttrKeyTypeECSECPrimeRandom
import platform.Security.kSecAttrLabel
import platform.Security.kSecClass
import platform.Security.kSecClassCertificate
import platform.Security.kSecClassIdentity
import platform.Security.kSecClassKey
import platform.Security.kSecKeyAlgorithmECDSASignatureMessageX962SHA256
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecPrivateKeyAttrs
import platform.Security.kSecReturnRef
import platform.Security.kSecValueRef
import platform.Foundation.NSDate
import platform.Foundation.NSUserDefaults
import platform.Foundation.timeIntervalSince1970

/**
 * The iOS agent's TLS identity: a Keychain identity, which Network.framework serves as is, and the
 * [fingerprint] clients pin.
 */
class IosTlsIdentity internal constructor(internal val ref: SecIdentityRef, val fingerprint: AgentFingerprint)

/**
 * Keeps the iOS agent's identity in the Keychain: a P-256 key generated there, which never leaves
 * it, and the agent's self-signed certificate.
 *
 * iOS has no API to create a certificate, nor to make an identity from a key and a certificate. So
 * the agent builds its certificate with [SelfSignedCertificate], signs it with the Keychain key, and
 * stores it beside the key; the Keychain then pairs the two by public key, and an identity query
 * returns them as one [SecIdentityRef].
 */
object IosAgentIdentityStore {
    private const val LABEL = "RemoteBLE Agent identity"
    private const val TAG = "dev.warsha.remoteble.agent.identity"
    private const val INSTALLED = "remote_ble_agent_identity_installed"

    /** DER `SubjectPublicKeyInfo` header for an uncompressed P-256 point (id-ecPublicKey, prime256v1). */
    private val P256_SPKI_PREFIX = byteArrayOf(
        0x30, 0x59, 0x30, 0x13, 0x06, 0x07, 0x2a, 0x86.toByte(), 0x48, 0xce.toByte(), 0x3d, 0x02, 0x01,
        0x06, 0x08, 0x2a, 0x86.toByte(), 0x48, 0xce.toByte(), 0x3d, 0x03, 0x01, 0x07, 0x03, 0x42, 0x00,
    )

    /**
     * Loads the identity, creating it on first use. [reset] discards an existing one first, which
     * changes the fingerprint and makes every paired client fail with an identity error until it
     * pairs again: that is the point of a reset.
     */
    fun loadOrCreate(reset: Boolean = false): IosTlsIdentity {
        if (reset || !installed()) delete()
        existing()?.let { return it }
        // A key without its certificate is a creation interrupted before the certificate was
        // stored. Its key was never presented to anyone, so start again.
        delete()
        create()
        return existing()
            ?.also { Logger.info(LogTags.AGENT) { "created agent identity ${it.fingerprint}" } }
            ?: error("the Keychain did not pair the agent's key and certificate")
    }

    /**
     * Whether this installation has already been through here. Keychain items outlive an uninstall,
     * while the agent's tokens and settings do not, and Android's Keystore key goes with the app. So
     * the first run of an installation discards whatever identity a previous one left: reinstalling
     * means a new identity on both platforms.
     */
    private fun installed(): Boolean {
        val defaults = NSUserDefaults.standardUserDefaults
        if (defaults.boolForKey(INSTALLED)) return true
        defaults.setBool(true, INSTALLED)
        return false
    }

    private fun existing(): IosTlsIdentity? = cf { scope ->
        val query = scope.dict(
            kSecClass to kSecClassIdentity,
            kSecAttrLabel to scope.string(LABEL),
            kSecReturnRef to kCFBooleanTrue,
            kSecMatchLimit to kSecMatchLimitOne,
        )
        memScoped {
            val result = alloc<CFTypeRefVar>()
            val status = SecItemCopyMatching(query, result.ptr)
            if (status == errSecItemNotFound) return@cf null
            check(status == errSecSuccess) { "Keychain identity query failed: $status" }
            val identity: SecIdentityRef = result.value!!.reinterpret()
            IosTlsIdentity(identity, fingerprintOf(identity))
        }
    }

    private fun fingerprintOf(identity: SecIdentityRef): AgentFingerprint = memScoped {
        val certificate = alloc<platform.Security.SecCertificateRefVar>()
        check(SecIdentityCopyCertificate(identity, certificate.ptr) == errSecSuccess) { "identity has no certificate" }
        val cert = certificate.value!!
        try {
            val key = SecCertificateCopyKey(cert) ?: error("certificate has no key")
            try {
                AgentFingerprint.ofSpkiSha256(sha256(spkiOf(key)))
            } finally {
                CFRelease(key)
            }
        } finally {
            CFRelease(cert)
        }
    }

    private fun create() = cf { scope ->
        val parameters = scope.dict(
            kSecAttrKeyType to kSecAttrKeyTypeECSECPrimeRandom,
            kSecAttrKeySizeInBits to scope.int(256),
            kSecPrivateKeyAttrs to scope.dict(
                kSecAttrIsPermanent to kCFBooleanTrue,
                kSecAttrApplicationTag to scope.data(TAG.encodeToByteArray()),
                kSecAttrLabel to scope.string(LABEL),
                // The agent only runs in the foreground, and its identity is this device's own.
                kSecAttrAccessible to kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
            ),
        )
        val privateKey = memScoped {
            val error = alloc<CFErrorRefVar>()
            SecKeyCreateRandomKey(parameters, error.ptr) ?: error("Keychain key generation failed: ${error.value}")
        }
        scope.own(privateKey)
        val publicKey = scope.own(SecKeyCopyPublicKey(privateKey) ?: error("no public key"))
        val der = SelfSignedCertificate.build(
            spki = spkiOf(publicKey),
            serial = Random.nextBytes(16),
            // A day back, so a client whose clock runs slightly behind still sees a valid start.
            notBeforeEpochSeconds = NSDate().timeIntervalSince1970.toLong() - 86_400,
            sign = { tbs -> sign(privateKey, tbs) },
        )
        val certificate: SecCertificateRef = scope.own(
            SecCertificateCreateWithData(null, scope.data(der)) ?: error("the certificate did not parse"),
        )
        val add = scope.dict(
            kSecClass to kSecClassCertificate,
            kSecValueRef to certificate,
            kSecAttrLabel to scope.string(LABEL),
        )
        check(SecItemAdd(add, null) == errSecSuccess) { "storing the agent certificate failed" }
    }

    private fun delete() = cf { scope ->
        SecItemDelete(scope.dict(kSecClass to kSecClassCertificate, kSecAttrLabel to scope.string(LABEL)))
        SecItemDelete(scope.dict(kSecClass to kSecClassKey, kSecAttrApplicationTag to scope.data(TAG.encodeToByteArray())))
    }

    /** ECDSA with SHA-256 over [message], as the DER `ECDSA-Sig-Value` a certificate carries. */
    private fun sign(key: SecKeyRef, message: ByteArray): ByteArray = cf { scope ->
        memScoped {
            val error = alloc<CFErrorRefVar>()
            val signature = SecKeyCreateSignature(key, kSecKeyAlgorithmECDSASignatureMessageX962SHA256, scope.data(message), error.ptr)
                ?: error("signing the certificate failed: ${error.value}")
            scope.own(signature).toByteArray()
        }
    }

    /** The key's `SubjectPublicKeyInfo`: Apple exports an EC public key as the bare X9.63 point. */
    private fun spkiOf(publicKey: SecKeyRef): ByteArray = cf { scope ->
        val point = memScoped {
            val error = alloc<CFErrorRefVar>()
            scope.own(SecKeyCopyExternalRepresentation(publicKey, error.ptr) ?: error("public key export failed")).toByteArray()
        }
        check(point.size == 65 && point[0] == 0x04.toByte()) { "not an uncompressed P-256 point" }
        P256_SPKI_PREFIX + point
    }

    private fun sha256(bytes: ByteArray): ByteArray {
        val digest = UByteArray(CC_SHA256_DIGEST_LENGTH)
        bytes.usePinned { input ->
            digest.usePinned { output -> CC_SHA256(input.addressOf(0), bytes.size.convert(), output.addressOf(0)) }
        }
        return digest.toByteArray()
    }
}

/**
 * Core Foundation values made for one Keychain call, released together when it returns. The
 * dictionaries retain what they hold, so releasing the scope's own references leaks nothing.
 */
private class CfScope {
    private val owned = mutableListOf<CFTypeRef>()

    fun <T : CFTypeRef> own(ref: T): T = ref.also { owned += it }

    fun data(bytes: ByteArray): CFDataRef = own(
        bytes.usePinned { CFDataCreate(null, if (bytes.isEmpty()) null else it.addressOf(0).reinterpret(), bytes.size.convert())!! },
    )

    fun string(value: String): CFStringRef = own(CFStringCreateWithCString(null, value, kCFStringEncodingUTF8)!!)

    fun int(value: Int): CFTypeRef = memScoped {
        val boxed = alloc<IntVar>().apply { this.value = value }
        own(CFNumberCreate(null, kCFNumberIntType, boxed.ptr)!!)
    }

    fun dict(vararg entries: Pair<CFStringRef?, CFTypeRef?>): CFDictionaryRef {
        val dict = CFDictionaryCreateMutable(null, entries.size.convert(), kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr)!!
        entries.forEach { (key, value) -> CFDictionaryAddValue(dict, key, value) }
        return own(dict)
    }

    fun release() = owned.asReversed().forEach(::CFRelease)
}

private inline fun <T> cf(block: (CfScope) -> T): T {
    val scope = CfScope()
    try {
        return block(scope)
    } finally {
        scope.release()
    }
}

private fun CFDataRef.toByteArray(): ByteArray {
    val length = CFDataGetLength(this).toInt()
    val bytes = ByteArray(length)
    if (length > 0) bytes.usePinned { CFDataGetBytes(this, CFRangeMake(0, length.convert()), it.addressOf(0).reinterpret()) }
    return bytes
}
