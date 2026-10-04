package dev.warsha.remoteble.agent

import dev.warsha.remoteble.log.Logger
import dev.warsha.remoteble.protocol.AgentFingerprint
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.Principal
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509ExtendedKeyManager
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * [TlsFront] on the platform's JSSE: an `SSLServerSocket` holding the agent's identity, relaying
 * each connection to the plain listener with one thread-blocking pump per direction.
 *
 * Blocking I/O makes every connection hold a thread, so three limits keep a hostile peer from
 * holding the agent:
 *
 * - The handshake has a deadline ([handshakeTimeout]) that closes the socket however the bytes
 *   trickle in. A socket read timeout would not do: it bounds each read, so a peer sending one byte
 *   per interval held a handshake open indefinitely.
 * - The threads are the front's own, never [Dispatchers.IO]: silent peers once filled that shared
 *   pool and held honest clients' handshakes up to the timeout.
 * - One host holds at most [maxPerHost] connections, and the front at most [maxConnections]; a
 *   connection over either is closed at once.
 *
 * TLS 1.3 and 1.2 are both enabled. 1.2 is not legacy tolerance: Ktor's CIO client, the SDK's JVM
 * engine, speaks nothing newer. Only AEAD suites are offered, as rustls and the iOS front offer: the
 * platforms' 1.2 defaults still include CBC ones, whose padding checks have a history of timing
 * oracles, and every client this project knows negotiates AES-GCM.
 */
class JsseTlsFront internal constructor(
    private val identity: AgentTlsIdentity,
    private val handshakeTimeout: Duration,
    private val maxPerHost: Int,
    private val maxConnections: Int,
) : TlsFront.Factory {
    override val fingerprint: AgentFingerprint get() = identity.fingerprint


    constructor(identity: AgentTlsIdentity) : this(identity, HANDSHAKE_TIMEOUT, MAX_PER_HOST, MAX_CONNECTIONS)

    // onFailure goes unused: the accept loop survives everything but a closed listener.
    override suspend fun start(host: String, port: Int, upstreamPort: Int, onFailure: (reason: String) -> Unit): TlsFront = withContext(Dispatchers.IO) {
        val server = try {
            context().serverSocketFactory.createServerSocket(port, BACKLOG, InetAddress.getByName(host)) as SSLServerSocket
        } catch (failure: IOException) {
            throw AgentBindException(host, port, failure)
        }
        server.enabledProtocols = PROTOCOLS.filter { it in server.supportedProtocols }.toTypedArray()
        server.enabledCipherSuites = server.supportedCipherSuites.filter(::isAead).toTypedArray()
        Running(server, upstreamPort).also { it.acceptLoop() }
    }

    private fun context(): SSLContext =
        SSLContext.getInstance("TLS").apply { init(arrayOf(IdentityKeyManager(identity)), null, null) }

    /**
     * Offers the one identity for every EC server handshake. A key manager rather than a keystore,
     * because a keystore would have to hold the key: an Android Keystore key has no encoding to put
     * in one, and is only usable through the provider that owns it, which this hands it to as is.
     */
    private class IdentityKeyManager(private val identity: AgentTlsIdentity) : X509ExtendedKeyManager() {
        private fun aliasFor(keyType: String?): String? = ALIAS.takeIf { keyType.equals(KEY_TYPE, ignoreCase = true) }

        override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?) = aliasFor(keyType)
        override fun chooseEngineServerAlias(keyType: String?, issuers: Array<out Principal>?, engine: SSLEngine?) = aliasFor(keyType)
        override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?) = aliasFor(keyType)?.let { arrayOf(it) }
        override fun getCertificateChain(alias: String?) = if (alias == ALIAS) arrayOf(identity.certificate) else null
        override fun getPrivateKey(alias: String?) = if (alias == ALIAS) identity.privateKey else null

        // A server only; no client certificate is ever offered.
        override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?): String? = null
        override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null

        private companion object {
            const val ALIAS = "agent"
            const val KEY_TYPE = "EC"
        }
    }

    private inner class Running(private val server: SSLServerSocket, private val upstreamPort: Int) : TlsFront {
        private val threads = Executors.newCachedThreadPool(daemonThreads("remoteble-tls"))
        private val deadlines = Executors.newSingleThreadScheduledExecutor(daemonThreads("remoteble-tls-deadline"))
        private val scope = CoroutineScope(SupervisorJob() + threads.asCoroutineDispatcher())
        private val peers = ConcurrentHashMap<Int, PeerAddress>()
        private val live = ConcurrentHashMap.newKeySet<Socket>()
        private val perHost = ConcurrentHashMap<String, AtomicInteger>()
        private val total = AtomicInteger()

        override val port: Int get() = server.localPort

        override fun peerOf(relayPort: Int): PeerAddress? = peers[relayPort]

        fun acceptLoop() {
            scope.launch {
                acceptUntilClosed(accept = server::accept, isClosed = server::isClosed) { socket ->
                    val client = socket as SSLSocket
                    val host = client.inetAddress.hostAddress
                    if (admit(host)) {
                        launch {
                            try {
                                relay(client)
                            } finally {
                                release(host)
                            }
                        }
                    } else {
                        Logger.debug(LogTags.SERVER) { "TLS connection from $host refused: connection limit reached" }
                        client.closeQuietly()
                    }
                }
            }
        }

        // Per address, not per IPv6 /64: every device on a home LAN shares one /64, so grouping would
        // let one device fill the slot all of them need. A host using many addresses meets the total.
        private fun admit(host: String): Boolean {
            if (total.incrementAndGet() > maxConnections) {
                total.decrementAndGet()
                return false
            }
            val count = perHost.computeIfAbsent(host) { AtomicInteger() }
            if (count.incrementAndGet() > maxPerHost) {
                count.decrementAndGet()
                total.decrementAndGet()
                return false
            }
            return true
        }

        private fun release(host: String) {
            perHost[host]?.let { count -> if (count.decrementAndGet() <= 0) perHost.remove(host, count) }
            total.decrementAndGet()
        }

        private suspend fun relay(client: SSLSocket) {
            val peer = PeerAddress(client.inetAddress.hostAddress, client.port)
            var upstream: Socket? = null
            live += client
            try {
                handshake(client)
                upstream = Socket().apply { connect(InetSocketAddress(UPSTREAM_HOST, upstreamPort)) }
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

        /** Completes the handshake within [handshakeTimeout] in total, or closes [client] trying. */
        private fun handshake(client: SSLSocket) {
            val deadline = deadlines.schedule({ client.closeQuietly() }, handshakeTimeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
            try {
                client.startHandshake()
            } finally {
                deadline.cancel(false)
            }
        }

        /**
         * Pumps both directions until either ends. Whichever ends first closes both sockets, which
         * is also what unblocks the other pump's read. A WebSocket closes in-band before TCP does,
         * so ending both directions together loses nothing.
         */
        private suspend fun pumpBothWays(client: SSLSocket, upstream: Socket) = coroutineScope {
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
            deadlines.shutdownNow()
            threads.shutdown()
        }
    }

    internal companion object {
        val HANDSHAKE_TIMEOUT = 10.seconds
        // Agents serve a handful of clients, and one host is usually one client; a test rig or a
        // conformance run opens a few more. Each connection holds a thread while it handshakes and
        // two while it relays.
        const val MAX_PER_HOST = 16
        const val MAX_CONNECTIONS = 128

        val PROTOCOLS = listOf("TLSv1.3", "TLSv1.2")
        const val BACKLOG = 50
        const val BUFFER_BYTES = 16 * 1024

        // The address the contract names, spelled out. `InetAddress.getLoopbackAddress()` is
        // 127.0.0.1 on the JDK but ::1 on Android, where CIO's IPv4-only listener refuses it.
        val UPSTREAM_HOST: InetAddress = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
    }
}

/**
 * Whether [suite] is AEAD: every TLS 1.3 suite, and the ECDHE-ECDSA GCM and ChaCha20-Poly1305 ones of
 * 1.2. An agent's key is EC, so no RSA suite could be negotiated anyway.
 */
internal fun isAead(suite: String): Boolean =
    suite.startsWith("TLS_AES_") || suite.startsWith("TLS_CHACHA20_") ||
        (suite.startsWith("TLS_ECDHE_ECDSA_WITH_") && ("_GCM_" in suite || "_CHACHA20_POLY1305_" in suite))

/**
 * Accepts until the listener is closed, handing each connection to [onAccepted]. A failed accept
 * on an open listener (fd exhaustion, a peer that reset between SYN and accept) is transient: it is
 * logged and retried with a backoff capped at [maxBackoff], because ending the loop would leave an
 * agent that looks up and serves nothing. Only a closed listener ends it.
 */
internal suspend fun acceptUntilClosed(
    accept: () -> Socket,
    isClosed: () -> Boolean,
    maxBackoff: Duration = 1.seconds,
    onAccepted: (Socket) -> Unit,
) {
    var backoff = Duration.ZERO
    while (true) {
        val socket = try {
            accept()
        } catch (failure: IOException) {
            if (isClosed()) return
            backoff = (backoff * 2).coerceIn(50.milliseconds, maxBackoff)
            Logger.warn(LogTags.SERVER) { "TLS accept failed (${failure.message}); retrying in $backoff" }
            delay(backoff)
            continue
        }
        backoff = Duration.ZERO
        onAccepted(socket)
    }
}

private fun daemonThreads(name: String): ThreadFactory {
    val count = AtomicInteger()
    return ThreadFactory { runnable -> Thread(runnable, "$name-${count.incrementAndGet()}").apply { isDaemon = true } }
}

private fun java.io.Closeable.closeQuietly() {
    try {
        close()
    } catch (_: IOException) {
    }
}
