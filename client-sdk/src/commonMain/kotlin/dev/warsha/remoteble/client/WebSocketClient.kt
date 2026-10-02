package dev.warsha.remoteble.client

import dev.warsha.remoteble.protocol.AgentFingerprint
import dev.warsha.remoteble.protocol.AgentPairing
import io.ktor.client.HttpClient

/**
 * A platform-default [HttpClient] with WebSocket support, ready for
 * [WebSocketAgentTransport]. Each target binds the conventional Ktor engine
 * (JVM: CIO, Android: OkHttp, iOS: Darwin). Apps that need custom engine config
 * (proxies, TLS pinning, timeouts) can build their own `HttpClient { WebSockets }`
 * and hand it to the transport instead.
 *
 * On Android, OkHttp enforces the app's network security policy, which forbids `ws://` by default
 * from targetSdk 28; the transport then fails with [CleartextTrafficNotPermittedException]. Use
 * `cioWebSocketHttpClient()` there for a plain `ws://` agent.
 */
expect fun defaultWebSocketHttpClient(): HttpClient

/**
 * A WebSocket [HttpClient] for a `wss://` agent whose identity this client pinned at pairing.
 *
 * The agent's certificate is self-signed, so no certificate authority is consulted: the connection
 * is trusted exactly when the agent's TLS key hashes to [fingerprint]. A different key fails the
 * handshake with [AgentIdentityMismatchException] before any request, and so before the bearer
 * token, is sent, and the transport gives up rather than retrying. Agents are reached by IP
 * addresses their certificates cannot name, so host names are not what is checked; the pin is.
 *
 * JVM: Ktor CIO (TLS 1.2). Android: OkHttp. iOS and macOS: Darwin (NSURLSession).
 */
expect fun pinnedWebSocketHttpClient(fingerprint: AgentFingerprint): HttpClient

/**
 * The [HttpClient] for an agent reached through [pairing]: [pinnedWebSocketHttpClient] whenever the
 * pairing carries a fingerprint, so a paired encrypted agent is only ever trusted by its pin, and
 * [defaultWebSocketHttpClient] for a cleartext one. Connect with
 *
 * ```
 * WebSocketAgentTransport(pairing.url, scope, pairingWebSocketHttpClient(pairing), authToken = { pairing.token })
 * ```
 *
 * The caller owns the client and closes it, as with the other factories. On Android a cleartext
 * pairing meets OkHttp's network security policy; see [defaultWebSocketHttpClient].
 */
fun pairingWebSocketHttpClient(pairing: AgentPairing): HttpClient =
    pairing.fingerprint?.let(::pinnedWebSocketHttpClient) ?: defaultWebSocketHttpClient()
