package dev.warsha.remoteble.client

import dev.warsha.remoteble.protocol.AgentFingerprint

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** State of the IP link to the agent. Distinct from the physical BLE link state. */
enum class TransportState {
    CONNECTING,
    CONNECTED,

    /**
     * Not connected, but recovery is still expected — a reconnect episode is running, or is about
     * to be armed. Callers should treat this as a blip, not a failure.
     */
    DISCONNECTED,

    /**
     * Not connected and **nothing is going to fix it**: the reconnect policy exhausted its
     * attempts, or reconnect is disabled and the link dropped.
     *
     * Distinct from [DISCONNECTED] because the two demand opposite reactions, and collapsing them
     * is what left `RemotePeripheral.state` reporting `Connected` forever against a dead agent
     * (Rig B case 5). A caller that waits out a [DISCONNECTED] is right to; one that waits out this
     * is waiting for something that will never happen.
     *
     * Not terminal for the *instance*: a later explicit [AgentTransport.connect] may start a fresh
     * episode, which is why this is not merged into [INCOMPATIBLE_PROTOCOL].
     */
    GAVE_UP,

    /** Terminal for this transport instance: the peer closed with an incompatible protocol range. */
    INCOMPATIBLE_PROTOCOL,
}

/**
 * LAYER 1 — the pluggable seam. Byte-level, BLE-agnostic.
 *
 * One bidirectional, message-oriented link to ONE agent at an opaque endpoint.
 * A WebSocket impl, a raw-TCP impl, a cloud-relay impl all satisfy this. The
 * endpoint (host:port, URL, MagicDNS name) is handed in at construction and is
 * none of this interface's business.
 */
interface AgentTransport {
    val state: StateFlow<TransportState>

    /** Frames arriving from the agent (replies + events), already reassembled. */
    val incoming: Flow<ByteArray>

    /** Idempotent: safe to call to (re)establish after a drop. */
    suspend fun connect()

    suspend fun send(frame: ByteArray)

    suspend fun close()
}

/** Thrown by [AgentTransport.send] when the link is not (or no longer) usable. */
class TransportClosedException(message: String? = null) : Exception(message)

/**
 * The platform refused to open a cleartext (`ws://`) connection to [url]. This is a configuration
 * failure, not an unreachable agent, so the transport does not retry it: it goes
 * [TransportState.GAVE_UP] at once and [AgentTransport.connect] throws this.
 *
 * On Android it means the app's network security policy forbids cleartext traffic, which is the
 * default from targetSdk 28. The SDK's default client runs on OkHttp, which enforces that policy.
 * Any one of these fixes it: pass `cioWebSocketHttpClient()` (plain sockets, which the policy does
 * not govern) to [WebSocketAgentTransport], permit cleartext for the agent's host in a network
 * security config, or reach the agent over `wss://`.
 */
class CleartextTrafficNotPermittedException(
    val url: String,
    cause: Throwable? = null,
) : Exception(
    "Cleartext connection to $url refused by the platform's network security policy. On Android, " +
        "pass cioWebSocketHttpClient() to WebSocketAgentTransport, permit cleartext for the agent's " +
        "host in a network security config, or use wss://.",
    cause,
)

/**
 * Recognises the platform's cleartext refusal anywhere in this throwable's cause chain. OkHttp,
 * which also backs Android's own `HttpURLConnection`, reports it as `UnknownServiceException` with
 * exactly this wording; the engine may wrap it, hence the walk. Matched on the message rather than
 * the class so the check stays in common code and is testable off-device.
 */
internal fun Throwable.asCleartextRefusal(url: String): CleartextTrafficNotPermittedException? {
    var current: Throwable? = this
    repeat(MAX_CAUSE_DEPTH) {
        val t = current ?: return null
        if (t is CleartextTrafficNotPermittedException) return t
        val message = t.message
        if (message != null && CLEARTEXT_REFUSAL in message && SECURITY_POLICY in message) {
            return CleartextTrafficNotPermittedException(url, this)
        }
        current = t.cause?.takeIf { it !== t }
    }
    return null
}

/**
 * The agent at the other end presented a different identity from the one this client pinned: its
 * TLS key's fingerprint is not [expected]. Either the agent reset its identity, or something else
 * is answering at its address.
 *
 * Raised inside the TLS handshake, before any HTTP request, so the bearer token is never sent to
 * whoever presented [presented]. Like a cleartext refusal it is not retried: the transport goes
 * [TransportState.GAVE_UP] at once and [AgentTransport.connect] throws it. Re-pair with the agent to
 * pin its new fingerprint, after confirming the reset was intended.
 */
class AgentIdentityMismatchException(
    val expected: AgentFingerprint,
    val presented: AgentFingerprint?,
) : Exception(
    "Agent identity mismatch: pinned $expected, but the agent presented ${presented ?: "no usable key"}. " +
        "The agent's identity was reset, or another host is answering at its address; re-pair only " +
        "if the reset was intended.",
)

/**
 * A connect failure that every retry would repeat, so the transport stops instead of looping: a
 * platform cleartext refusal or a pinned-identity mismatch. `null` for anything else.
 */
internal fun Throwable.asTerminalConnectFailure(url: String): Exception? =
    asCleartextRefusal(url) ?: causeOfType<AgentIdentityMismatchException>()

private inline fun <reified T : Throwable> Throwable.causeOfType(): T? {
    var current: Throwable? = this
    repeat(MAX_CAUSE_DEPTH) {
        val t = current ?: return null
        if (t is T) return t
        current = t.cause?.takeIf { it !== t }
    }
    return null
}

private const val CLEARTEXT_REFUSAL = "CLEARTEXT communication"
private const val SECURITY_POLICY = "not permitted by network security policy"
private const val MAX_CAUSE_DEPTH = 16
