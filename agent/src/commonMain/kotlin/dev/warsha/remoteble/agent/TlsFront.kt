package dev.warsha.remoteble.agent

import dev.warsha.remoteble.protocol.AgentFingerprint

import kotlin.time.Duration
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Terminates TLS for an [AgentWebSocketServer] and relays each connection, decrypted, to the
 * server's plain listener on loopback.
 *
 * It exists because Ktor's CIO server cannot serve TLS on any platform, so the encryption has to
 * sit in front of it (`docs/proposals/agent-transport-encryption.md`). The cost of a relay is that
 * every request then arrives from loopback, and the agent's rate limiter and the dashboard's
 * own-device gate both decide on the peer address. [peerOf] restores it.
 */
interface TlsFront {
    /** The public port, which is what clients connect to. */
    val port: Int

    /**
     * The real peer of a relayed connection, looked up by the relay's own local port, which is
     * the remote port the plain listener sees. `null` when no relay owns that port: a process on
     * this device connecting to the loopback listener directly, which is genuinely local.
     *
     * Nothing a remote party sends influences the lookup, which is why this is a registry and not
     * an `X-Forwarded-For`-style header.
     */
    fun peerOf(relayPort: Int): PeerAddress?

    fun stop()

    interface Factory {
        /**
         * The identity this front presents: what clients pin, and what a pairing for this agent
         * carries. Part of the front so the two cannot be configured apart.
         */
        val fingerprint: AgentFingerprint

        /**
         * Binds [host]:[port] (0 for an ephemeral port) and relays to `127.0.0.1:[upstreamPort]`.
         * Throws [AgentBindException] if the public port cannot be bound. [onFailure] hears of a
         * listener that fails after starting, when there is no caller left to throw to.
         */
        suspend fun start(host: String, port: Int, upstreamPort: Int, onFailure: (reason: String) -> Unit): TlsFront
    }
}

data class PeerAddress(val host: String, val port: Int) {
    override fun toString(): String = "$host:$port"
}

/**
 * Waits up to [timeout] for an asynchronous listener to report the port it bound through [ready].
 * However the wait ends without one (the timeout, a reported failure, the caller's cancellation),
 * [cancel] releases the listener before the failure propagates; the timeout throws [timedOut].
 * Bounded because a listener can wait indefinitely, as Network.framework's does for a usable network.
 */
internal suspend fun awaitListening(
    ready: Deferred<Int>,
    timeout: Duration,
    cancel: () -> Unit,
    timedOut: () -> Throwable,
): Int =
    try {
        withTimeoutOrNull(timeout) { ready.await() } ?: throw timedOut()
    } catch (failure: Throwable) {
        cancel()
        throw failure
    }

/**
 * A certificate's SHA-256 as browsers show it, in upper-case hex pairs joined by colons. A browser
 * cannot pin, so the first visit to the `https://` dashboard warns about a self-signed certificate,
 * and this is what the operator compares before accepting it. It is not the identity clients pin,
 * which hashes the key alone, so it changes whenever the certificate is reissued.
 */
internal fun certificateSha256Text(digest: ByteArray): String =
    digest.joinToString(":") { byte -> (byte.toInt() and 0xFF).toString(16).uppercase().padStart(2, '0') }
