@file:OptIn(ExperimentalForeignApi::class)

package dev.warsha.remoteble.agent

import dev.warsha.remoteble.log.Logger
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import dev.warsha.remoteble.agent.tlsrelay.remoteble_pump
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toKString
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.Network.nw_connection_cancel
import platform.Network.nw_connection_copy_current_path
import platform.Network.nw_connection_copy_endpoint
import platform.Network.nw_connection_create
import platform.Network.nw_connection_set_queue
import platform.Network.nw_connection_set_state_changed_handler
import platform.Network.nw_connection_start
import platform.Network.nw_connection_state_cancelled
import platform.Network.nw_connection_state_failed
import platform.Network.nw_connection_state_ready
import platform.Network.nw_connection_state_waiting
import platform.Network.nw_connection_t
import platform.Network.nw_endpoint_copy_address_string
import platform.Network.nw_endpoint_create_host
import platform.Network.nw_endpoint_get_port
import platform.Network.nw_endpoint_t
import platform.Network.nw_error_get_error_code
import platform.Network.nw_error_t
import platform.Network.nw_listener_cancel
import platform.Network.nw_listener_create
import platform.Network.nw_listener_create_with_port
import platform.Network.nw_listener_get_port
import platform.Network.nw_listener_set_new_connection_handler
import platform.Network.nw_listener_set_queue
import platform.Network.nw_listener_set_state_changed_handler
import platform.Network.nw_listener_start
import platform.Network.nw_listener_state_failed
import platform.Network.nw_listener_state_ready
import platform.Network.nw_listener_t
import platform.Network.nw_parameters_copy_default_protocol_stack
import platform.Network.nw_parameters_create
import platform.Network.nw_parameters_create_secure_tcp
import platform.Network.nw_parameters_set_local_endpoint
import platform.Network.nw_parameters_set_reuse_local_address
import platform.Network.nw_path_copy_effective_local_endpoint
import platform.Network.nw_protocol_stack_set_transport_protocol
import platform.Network.nw_tcp_create_options
import platform.Network.nw_tls_copy_sec_protocol_options
import platform.Security.sec_identity_create
import platform.Security.sec_protocol_options_append_tls_ciphersuite
import platform.Security.sec_protocol_options_set_local_identity
import platform.Security.sec_protocol_options_set_min_tls_protocol_version
import platform.Security.tls_ciphersuite_AES_128_GCM_SHA256
import platform.Security.tls_ciphersuite_AES_256_GCM_SHA384
import platform.Security.tls_ciphersuite_CHACHA20_POLY1305_SHA256
import platform.Security.tls_ciphersuite_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256
import platform.Security.tls_ciphersuite_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384
import platform.Security.tls_ciphersuite_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256
import platform.Security.tls_protocol_version_TLSv12
import platform.darwin.DISPATCH_TIME_NOW
import platform.darwin.NSEC_PER_SEC
import platform.darwin.dispatch_after
import platform.darwin.dispatch_queue_create
import platform.darwin.dispatch_time
import platform.posix.free

/**
 * [TlsFront] on Network.framework: an `NWListener` serving the agent's Keychain identity, relaying
 * each connection to the plain listener over a second, plain `NWConnection`. Everything runs on one
 * serial queue, so a connection's relay state needs no locking; only the peer registry, which Ktor
 * reads from its own threads, does.
 *
 * TLS 1.2 is the floor, not legacy tolerance: Ktor's CIO client, the SDK's JVM engine, speaks
 * nothing newer. Network.framework offers 1.3 above it. Only AEAD suites are offered, as on the other
 * agents: the platform's 1.2 defaults include CBC ones.
 *
 * Two Kotlin/Native interop traps shape this file:
 *
 * - `NW_PARAMETERS_DISABLE_PROTOCOL` and `NW_PARAMETERS_DEFAULT_CONFIGURATION` are sentinel blocks
 *   recognised by identity, and Kotlin/Native hands over a re-wrapped block instead. "Disable TLS"
 *   silently became "TLS with defaults", and the plain upstream tried a TLS handshake with CIO. So
 *   plain TCP is built from the protocol stack, and default configuration is an empty block.
 * - Network.framework's static content contexts are mistaken for blocks, and converting one
 *   terminates the process. The receive callback passes one at the end of every stream, so the byte
 *   pump is Objective-C (`src/nativeInterop/cinterop/tlsrelay.def`), and Kotlin only hears that a
 *   direction ended.
 */
class NetworkTlsFront(private val identity: IosTlsIdentity) : TlsFront.Factory {

    override suspend fun start(host: String, port: Int, upstreamPort: Int, onFailure: (reason: String) -> Unit): TlsFront =
        Running(identity, upstreamPort, onFailure).also { it.listen(host, port) }

    private class Running(
        private val identity: IosTlsIdentity,
        private val upstreamPort: Int,
        private val onFailure: (reason: String) -> Unit,
    ) : TlsFront {
        private val queue = dispatch_queue_create("dev.warsha.remoteble.agent.tls-front", null)
        private val lock = SynchronizedObject()
        private val peers = mutableMapOf<Int, PeerAddress>()
        private val live = mutableSetOf<Relay>()
        private var listener: nw_listener_t = null
        private var boundPort = 0

        override val port: Int get() = boundPort

        override fun peerOf(relayPort: Int): PeerAddress? = synchronized(lock) { peers[relayPort] }

        suspend fun listen(host: String, port: Int) {
            val parameters = nw_parameters_create_secure_tcp(
                { tls ->
                    val options = nw_tls_copy_sec_protocol_options(tls)
                    sec_protocol_options_set_local_identity(options, sec_identity_create(identity.ref))
                    sec_protocol_options_set_min_tls_protocol_version(options, tls_protocol_version_TLSv12)
                    AEAD_SUITES.forEach { sec_protocol_options_append_tls_ciphersuite(options, it) }
                },
                { _ -> },
            )
            nw_parameters_set_reuse_local_address(parameters, true)
            val created = if (host == ANY_IPV4 || host == ANY_IPV6) {
                nw_listener_create_with_port(port.toString(), parameters)
            } else {
                nw_parameters_set_local_endpoint(parameters, nw_endpoint_create_host(host, port.toString()))
                nw_listener_create(parameters)
            } ?: throw AgentBindException(host, port, null)
            listener = created

            val ready = CompletableDeferred<Int>()
            nw_listener_set_queue(created, queue)
            nw_listener_set_state_changed_handler(created) { state, error ->
                when (state) {
                    nw_listener_state_ready -> ready.complete(nw_listener_get_port(created).toInt())
                    nw_listener_state_failed -> {
                        // Once ready, a failure (a network change can cause one) has no caller left to
                        // throw to, and the agent would look up while serving nothing.
                        if (ready.isCompleted) onFailure("port $boundPort, ${describe(error)}")
                        ready.completeExceptionally(AgentBindException(host, port, IllegalStateException(describe(error))))
                        nw_listener_cancel(created)
                    }
                    else -> Unit
                }
            }
            nw_listener_set_new_connection_handler(created) { connection -> accept(connection) }
            nw_listener_start(created)
            boundPort = ready.await()
        }

        private fun accept(client: nw_connection_t) {
            val relay = Relay(client)
            synchronized(lock) { live += relay }
            nw_connection_set_queue(client, queue)
            nw_connection_set_state_changed_handler(client) { state, error ->
                when (state) {
                    // For an inbound connection, ready means the TLS handshake has completed.
                    nw_connection_state_ready -> relay.connectUpstream()
                    nw_connection_state_failed -> relay.close("TLS connection failed: ${describe(error)}")
                    nw_connection_state_cancelled -> relay.close(null)
                    else -> Unit
                }
            }
            // Bounded, so a peer that opens TCP and never completes TLS, or an upstream that never
            // connects, cannot hold a connection forever.
            dispatch_after(dispatch_time(DISPATCH_TIME_NOW, HANDSHAKE_TIMEOUT_SECONDS * NSEC_PER_SEC.toLong()), queue) {
                if (!relay.relaying) relay.close("TLS handshake or upstream connect timed out")
            }
            nw_connection_start(client)
        }

        /** One client connection and its plain upstream; touched only on [queue]. */
        inner class Relay(val client: nw_connection_t) {
            var upstream: nw_connection_t = null
            // Handed to the Objective-C pump, which calls back once per direction; released only
            // after both have, so no late callback can reach a freed reference.
            private var pumpRef: StableRef<Relay>? = null
            private var pumpsRunning = 0
            var relaying = false
                private set
            private var relayPort: Int? = null
            private var closed = false

            fun connectUpstream() {
                if (closed || upstream != null) return
                val peer = endpointAddress(nw_connection_copy_endpoint(client))
                val plain = nw_parameters_create().also { parameters ->
                    nw_protocol_stack_set_transport_protocol(nw_parameters_copy_default_protocol_stack(parameters), nw_tcp_create_options())
                }
                val connection = nw_connection_create(nw_endpoint_create_host(UPSTREAM_HOST, upstreamPort.toString()), plain)
                upstream = connection
                nw_connection_set_queue(connection, queue)
                nw_connection_set_state_changed_handler(connection) { state, error ->
                    when (state) {
                        nw_connection_state_ready -> {
                            val local = nw_path_copy_effective_local_endpoint(nw_connection_copy_current_path(connection))
                            val port = nw_endpoint_get_port(local).toInt()
                            relayPort = port
                            // Registered before a single byte is relayed, so the plain listener can
                            // never handle a request from this connection without knowing its peer.
                            synchronized(lock) { peers[port] = peer }
                            relaying = true
                            val ref = StableRef.create(this).also { pumpRef = it }
                            pumpsRunning = 2
                            remoteble_pump(client, connection, PUMP_ENDED, ref.asCPointer())
                            remoteble_pump(connection, client, PUMP_ENDED, ref.asCPointer())
                        }
                        // Waiting means the local listener refused; it will not start answering, so give up.
                        nw_connection_state_waiting, nw_connection_state_failed -> close("upstream failed: ${describe(error)}")
                        nw_connection_state_cancelled -> close(null)
                        else -> Unit
                    }
                }
                nw_connection_start(connection)
            }

            /** One direction has ended; the relay closes, and the reference goes once both have. */
            fun pumpEnded() {
                close(null)
                if (--pumpsRunning == 0) {
                    pumpRef?.dispose()
                    pumpRef = null
                }
            }

            fun close(reason: String?) {
                if (closed) return
                closed = true
                // Most often a client that rejected this identity, which is the pinning working.
                reason?.let { Logger.debug(LogTags.SERVER) { "TLS connection ended: $it" } }
                synchronized(lock) {
                    relayPort?.let { peers.remove(it) }
                    live -= this
                }
                nw_connection_cancel(client)
                upstream?.let { nw_connection_cancel(it) }
            }
        }

        override fun stop() {
            listener?.let { nw_listener_cancel(it) }
            val open = synchronized(lock) { live.toList() }
            open.forEach { relay ->
                nw_connection_cancel(relay.client)
                relay.upstream?.let { nw_connection_cancel(it) }
            }
        }
    }

    private companion object {
        const val ANY_IPV4 = "0.0.0.0"
        const val ANY_IPV6 = "::"
        // The address TlsFront's contract names, which CIO's IPv4-only loopback listener answers.
        const val UPSTREAM_HOST = "127.0.0.1"
        const val HANDSHAKE_TIMEOUT_SECONDS = 10L

        // TLS 1.3's suites, then 1.2's ECDHE-ECDSA AEAD ones: what rustls and the JSSE front offer.
        val AEAD_SUITES = listOf(
            tls_ciphersuite_AES_128_GCM_SHA256,
            tls_ciphersuite_AES_256_GCM_SHA384,
            tls_ciphersuite_CHACHA20_POLY1305_SHA256,
            tls_ciphersuite_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256,
            tls_ciphersuite_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384,
            tls_ciphersuite_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256,
        )

        // Runs on the front's queue, like everything else that touches a relay.
        val PUMP_ENDED = staticCFunction { context: COpaquePointer? ->
            context!!.asStableRef<Running.Relay>().get().pumpEnded()
        }

        fun endpointAddress(endpoint: nw_endpoint_t): PeerAddress {
            val text = nw_endpoint_copy_address_string(endpoint)
            val host = try {
                text?.toKString() ?: "unknown"
            } finally {
                free(text)
            }
            return PeerAddress(host, nw_endpoint_get_port(endpoint).toInt())
        }

        fun describe(error: nw_error_t): String = error?.let { "error ${nw_error_get_error_code(it)}" } ?: "no error"
    }
}

/** The iOS agent's [AgentTlsProvider], over [IosAgentIdentityStore]. */
object IosKeychainTls : AgentTlsProvider {
    override suspend fun load(reset: Boolean): AgentTls = withContext(Dispatchers.Default) {
        val identity = IosAgentIdentityStore.loadOrCreate(reset)
        AgentTls(identity.fingerprint, NetworkTlsFront(identity))
    }
}
