@file:OptIn(ExperimentalForeignApi::class, ExperimentalAtomicApi::class)

package dev.warsha.remoteble.client

import dev.warsha.remoteble.protocol.AgentFingerprint
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.plugin
import io.ktor.client.plugins.websocket.WebSockets
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.CoreCrypto.CC_SHA256
import platform.CoreCrypto.CC_SHA256_DIGEST_LENGTH
import platform.CoreFoundation.CFArrayGetCount
import platform.CoreFoundation.CFArrayGetValueAtIndex
import platform.CoreFoundation.CFDataGetBytePtr
import platform.CoreFoundation.CFDataGetLength
import platform.CoreFoundation.CFRelease
import platform.Foundation.NSURLAuthenticationMethodServerTrust
import platform.Foundation.NSURLCredential
import platform.Foundation.NSURLSessionAuthChallengeCancelAuthenticationChallenge
import platform.Foundation.NSURLSessionAuthChallengePerformDefaultHandling
import platform.Foundation.NSURLSessionAuthChallengeUseCredential
import platform.Foundation.credentialForTrust
import platform.Foundation.serverTrust
import platform.Security.SecCertificateCopyData
import platform.Security.SecCertificateRef
import platform.Security.SecTrustCopyCertificateChain
import platform.Security.SecTrustRef

/**
 * A WebSocket [HttpClient] for a `wss://` agent whose identity this client pinned at pairing.
 *
 * The agent's certificate is self-signed, so no certificate authority is consulted: the connection
 * is trusted exactly when the agent's TLS key hashes to [fingerprint]. NSURLSession hands the server
 * trust to this client's challenge handler, which accepts it only for the pinned key; host names are
 * not checked, since agents are reached by IP. A different key cancels the handshake before any
 * request, and so before the bearer token, is sent.
 *
 * NSURLSession reports a cancelled challenge as a generic cancellation, so the handler records the
 * mismatch and the client raises [AgentIdentityMismatchException] in its place, which the transport
 * treats as terminal.
 */
fun pinnedWebSocketHttpClient(fingerprint: AgentFingerprint): HttpClient {
    val refused = AtomicReference<AgentIdentityMismatchException?>(null)
    val client = HttpClient(Darwin) {
        engine {
            // The dispositions are NSInteger, which Ktor's shared Darwin metadata commonizes to a
            // width of its own; convert() adapts each to whatever the handler expects.
            handleChallenge { _, _, challenge, completionHandler ->
                val space = challenge.protectionSpace
                val trust = space.serverTrust
                if (space.authenticationMethod != NSURLAuthenticationMethodServerTrust || trust == null) {
                    completionHandler(NSURLSessionAuthChallengePerformDefaultHandling.convert(), null)
                } else {
                    val presented = leafFingerprint(trust)
                    if (presented == fingerprint) {
                        completionHandler(NSURLSessionAuthChallengeUseCredential.convert(), NSURLCredential.credentialForTrust(trust))
                    } else {
                        refused.store(AgentIdentityMismatchException(fingerprint, presented))
                        completionHandler(NSURLSessionAuthChallengeCancelAuthenticationChallenge.convert(), null)
                    }
                }
            }
        }
        install(WebSockets)
    }
    client.plugin(HttpSend).intercept { request ->
        try {
            execute(request)
        } catch (failure: Throwable) {
            throw refused.exchange(null) ?: failure
        }
    }
    return client
}

/** The fingerprint of the certificate the server presented first, or `null` if it is unreadable. */
private fun leafFingerprint(trust: SecTrustRef): AgentFingerprint? {
    val chain = SecTrustCopyCertificateChain(trust) ?: return null
    try {
        if (CFArrayGetCount(chain) < 1) return null
        val leaf: SecCertificateRef = CFArrayGetValueAtIndex(chain, 0)?.reinterpret() ?: return null
        val data = SecCertificateCopyData(leaf) ?: return null
        val der = try {
            val length = CFDataGetLength(data).toInt()
            CFDataGetBytePtr(data)?.reinterpret<ByteVar>()?.readBytes(length) ?: return null
        } finally {
            CFRelease(data)
        }
        return certificateSpki(der)?.let { AgentFingerprint.ofSpkiSha256(sha256(it)) }
    } finally {
        CFRelease(chain)
    }
}

private fun sha256(bytes: ByteArray): ByteArray {
    val digest = UByteArray(CC_SHA256_DIGEST_LENGTH)
    bytes.usePinned { input ->
        digest.usePinned { output -> CC_SHA256(input.addressOf(0), bytes.size.convert(), output.addressOf(0)) }
    }
    return digest.toByteArray()
}
