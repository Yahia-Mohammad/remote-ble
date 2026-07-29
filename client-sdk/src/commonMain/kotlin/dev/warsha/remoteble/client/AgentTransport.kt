package dev.warsha.remoteble.client

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
