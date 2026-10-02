package dev.warsha.remoteble.agent

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

    fun interface Factory {
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
