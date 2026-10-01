package dev.warsha.remoteble.agent

import dev.warsha.remoteble.log.Logger
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyStore
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * [TlsFront] on the platform's JSSE: an `SSLServerSocket` holding the agent's identity, relaying
 * each connection to the plain listener with one thread-blocking pump per direction. Agents serve a
 * handful of clients, so blocking I/O on [Dispatchers.IO] is simpler than an `SSLEngine` state
 * machine at no cost that matters.
 *
 * TLS 1.3 and 1.2 are both enabled. 1.2 is not legacy tolerance: Ktor's CIO client, the SDK's JVM
 * engine, speaks nothing newer.
 */
class JsseTlsFront(private val identity: AgentTlsIdentity) : TlsFront.Factory {

    override suspend fun start(host: String, port: Int, upstreamPort: Int): TlsFront = withContext(Dispatchers.IO) {
        val server = try {
            context().serverSocketFactory.createServerSocket(port, BACKLOG, InetAddress.getByName(host)) as SSLServerSocket
        } catch (failure: IOException) {
            throw AgentBindException(host, port, failure)
        }
        server.enabledProtocols = PROTOCOLS.filter { it in server.supportedProtocols }.toTypedArray()
        Running(server, upstreamPort).also { it.acceptLoop() }
    }

    private fun context(): SSLContext {
        val store = KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setKeyEntry("agent", identity.privateKey, KEY_PASSWORD, arrayOf(identity.certificate))
        }
        val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            .apply { init(store, KEY_PASSWORD) }
            .keyManagers
        return SSLContext.getInstance("TLS").apply { init(keyManagers, null, null) }
    }

    private class Running(private val server: SSLServerSocket, private val upstreamPort: Int) : TlsFront {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val peers = ConcurrentHashMap<Int, PeerAddress>()
        private val live = ConcurrentHashMap.newKeySet<Socket>()

        override val port: Int get() = server.localPort

        override fun peerOf(relayPort: Int): PeerAddress? = peers[relayPort]

        fun acceptLoop() {
            scope.launch {
                while (isActive) {
                    val client = try {
                        server.accept() as SSLSocket
                    } catch (_: IOException) {
                        break // closed by stop()
                    }
                    launch { relay(client) }
                }
            }
        }

        private suspend fun relay(client: SSLSocket) {
            val peer = PeerAddress(client.inetAddress.hostAddress, client.port)
            var upstream: Socket? = null
            live += client
            try {
                // Bounded, so a peer that opens TCP and never speaks TLS cannot hold a pump forever.
                client.soTimeout = HANDSHAKE_TIMEOUT_MILLIS
                client.startHandshake()
                client.soTimeout = 0
                upstream = Socket().apply { connect(InetSocketAddress(InetAddress.getLoopbackAddress(), upstreamPort)) }
                live += upstream
                // Registered before a single byte is relayed, so the plain listener can never handle
                // a request from this connection without the registry already knowing its peer.
                peers[upstream.localPort] = peer
                pumpBothWays(client, upstream)
            } catch (failure: IOException) {
                // Most often a client that rejected this identity, which is the pinning working.
                Logger.debug(LogTags.SERVER) { "TLS connection from $peer ended: ${failure.message}" }
            } finally {
                upstream?.let { peers.remove(it.localPort); live -= it; it.closeQuietly() }
                live -= client
                client.closeQuietly()
            }
        }

        /**
         * Pumps both directions until either ends. Whichever ends first closes both sockets, which
         * is also what unblocks the other pump's read. A WebSocket closes in-band before TCP does,
         * so ending both directions together loses nothing.
         */
        private suspend fun pumpBothWays(client: SSLSocket, upstream: Socket) = withContext(Dispatchers.IO) {
            launch {
                pump(client.inputStream, upstream.outputStream)
                client.closeQuietly()
                upstream.closeQuietly()
            }
            pump(upstream.inputStream, client.outputStream)
            client.closeQuietly()
            upstream.closeQuietly()
        }

        private fun pump(from: InputStream, to: OutputStream) {
            val buffer = ByteArray(BUFFER_BYTES)
            try {
                while (true) {
                    val read = from.read(buffer)
                    if (read < 0) break
                    to.write(buffer, 0, read)
                    to.flush()
                }
            } catch (_: IOException) {
                // The other side closed; the caller tears both down.
            }
        }

        override fun stop() {
            server.closeQuietly()
            live.forEach { it.closeQuietly() }
            scope.cancel()
        }
    }

    private companion object {
        val PROTOCOLS = listOf("TLSv1.3", "TLSv1.2")
        const val BACKLOG = 50
        const val HANDSHAKE_TIMEOUT_MILLIS = 10_000
        const val BUFFER_BYTES = 16 * 1024

        // Only protects the in-memory keystore built from the identity file; never stored. Not
        // empty, because PKCS#12 implementations may refuse an empty password.
        val KEY_PASSWORD = "remoteble".toCharArray()
    }
}

private fun java.io.Closeable.closeQuietly() {
    try {
        close()
    } catch (_: IOException) {
    }
}
