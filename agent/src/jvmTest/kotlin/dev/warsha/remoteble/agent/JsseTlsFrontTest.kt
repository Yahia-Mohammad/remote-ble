package dev.warsha.remoteble.agent

import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime
import kotlinx.coroutines.runBlocking

/**
 * The limits that keep a hostile LAN peer from holding the JSSE front, each reproduced the way it
 * was first found: against the front alone, with a plain upstream that only has to accept.
 */
class JsseTlsFrontTest {
    private val identity = AgentIdentityStore.generate()
    private val upstream = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    private val fronts = mutableListOf<TlsFront>()
    private val sockets = mutableListOf<Socket>()

    init {
        // Accepts and holds whatever the front relays, as CIO would until a request arrives.
        thread(isDaemon = true) {
            while (!upstream.isClosed) {
                try {
                    sockets += upstream.accept()
                } catch (_: IOException) {
                    break
                }
            }
        }
    }

    @AfterTest
    fun tearDown() {
        fronts.forEach { it.stop() }
        sockets.forEach { runCatching { it.close() } }
        upstream.close()
    }

    private fun front(
        handshakeTimeout: kotlin.time.Duration = JsseTlsFront.HANDSHAKE_TIMEOUT,
        maxPerHost: Int = JsseTlsFront.MAX_PER_HOST,
        maxConnections: Int = JsseTlsFront.MAX_CONNECTIONS,
    ): TlsFront = runBlocking {
        JsseTlsFront(identity, handshakeTimeout, maxPerHost, maxConnections).start("127.0.0.1", 0, upstream.localPort) {}
    }.also { fronts += it }

    private fun connect(port: Int): Socket = Socket("127.0.0.1", port).also { sockets += it }

    /** Waits for the front to close [socket], up to [within]; true if it did. */
    private fun closedWithin(socket: Socket, within: kotlin.time.Duration): Boolean {
        socket.soTimeout = within.inWholeMilliseconds.toInt()
        return try {
            socket.getInputStream().read() == -1
        } catch (_: SocketTimeoutException) {
            false
        } catch (_: IOException) {
            true
        }
    }

    /** Handshakes as a client offering only [protocol] and [suites]; the negotiated suite. */
    private fun honestHandshake(port: Int, protocol: String? = null, suites: List<String>? = null): String {
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustAll), null) }
        return (context.socketFactory.createSocket("127.0.0.1", port) as SSLSocket).use { socket ->
            socket.soTimeout = 30_000
            protocol?.let { socket.enabledProtocols = arrayOf(it) }
            suites?.let { socket.enabledCipherSuites = it.toTypedArray() }
            socket.startHandshake()
            socket.session.cipherSuite
        }
    }

    @Test
    fun onlyAeadSuitesAreNegotiated() {
        val front = front()

        assertEquals("TLS_AES_128_GCM_SHA256", honestHandshake(front.port, "TLSv1.3", listOf("TLS_AES_128_GCM_SHA256")))
        for (suite in listOf("TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256", "TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256")) {
            assertEquals(suite, honestHandshake(front.port, "TLSv1.2", listOf(suite)))
        }
        for (suite in listOf("TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA256", "TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA")) {
            assertFailsWith<SSLHandshakeException>(suite) { honestHandshake(front.port, "TLSv1.2", listOf(suite)) }
        }
    }

    @Test
    fun aTrickledHandshakeIsClosedAtTheDeadlineNotKeptAlive() {
        val front = front(handshakeTimeout = 1.seconds)
        val socket = connect(front.port)
        // The start of a ClientHello, one byte every 200 ms: each read succeeds well inside any
        // per-read timeout, which is how a socket timeout alone let this run indefinitely.
        val hello = byteArrayOf(0x16, 0x03, 0x01, 0x00, 0xC4.toByte(), 0x01, 0x00, 0x00, 0xC0.toByte(), 0x03, 0x03) + ByteArray(32)
        val elapsed = measureTime {
            for (byte in hello) {
                try {
                    socket.getOutputStream().write(byte.toInt())
                } catch (_: IOException) {
                    break
                }
                if (closedWithin(socket, 200.milliseconds)) break
            }
        }
        assertTrue(elapsed < 3.seconds, "the handshake ran for $elapsed past a 1 s deadline")
    }

    @Test
    fun silentPeersDoNotDelayAnHonestHandshake() {
        // Above Dispatchers.IO's 64 threads, which silent peers once filled.
        val front = front(maxPerHost = 200, maxConnections = 200)
        repeat(80) { connect(front.port) }
        Thread.sleep(500)

        val elapsed = measureTime { honestHandshake(front.port) }

        assertTrue(elapsed < 2.seconds, "an honest handshake took $elapsed behind 80 silent peers")
    }

    @Test
    fun aHostOverItsLimitIsClosedAtOnce() {
        val front = front(maxPerHost = 2)
        val held = List(2) { connect(front.port) }
        Thread.sleep(300)

        val third = connect(front.port)

        assertTrue(closedWithin(third, 2.seconds), "a third connection from one host was not refused")
        held.forEach { assertTrue(!closedWithin(it, 100.milliseconds), "a connection within the limit was closed") }
    }

    @Test
    fun aClosedConnectionFreesItsPlaceForTheHost() {
        val front = front(maxPerHost = 1)
        connect(front.port).close()
        Thread.sleep(300)

        honestHandshake(front.port)
    }

    @Test
    fun aTransientAcceptFailureIsRetriedAndOnlyAClosedListenerEndsTheLoop() = runBlocking {
        var closed = false
        var calls = 0
        val accepted = mutableListOf<Socket>()
        acceptUntilClosed(
            accept = {
                calls++
                when (calls) {
                    1, 2 -> throw IOException("Too many open files")
                    3 -> Socket()
                    else -> {
                        closed = true
                        throw IOException("Socket closed")
                    }
                }
            },
            isClosed = { closed },
            maxBackoff = 100.milliseconds,
        ) { accepted += it }

        assertEquals(1, accepted.size, "the connection after two failed accepts was not served")
        assertEquals(4, calls)
    }
}
