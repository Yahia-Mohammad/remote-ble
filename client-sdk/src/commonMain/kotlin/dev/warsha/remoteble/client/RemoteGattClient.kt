package dev.warsha.remoteble.client

import dev.warsha.remoteble.protocol.AgentError
import dev.warsha.remoteble.protocol.AgentEvent
import dev.warsha.remoteble.protocol.AgentException
import dev.warsha.remoteble.protocol.BleBondState
import dev.warsha.remoteble.protocol.CharRef
import dev.warsha.remoteble.protocol.ConnParamHint
import dev.warsha.remoteble.protocol.ConnPriority
import dev.warsha.remoteble.protocol.ConnProfile
import dev.warsha.remoteble.protocol.DescRef
import dev.warsha.remoteble.protocol.DeviceHandle
import dev.warsha.remoteble.protocol.ErrorKind
import dev.warsha.remoteble.protocol.Op
import dev.warsha.remoteble.protocol.OpResult
import dev.warsha.remoteble.protocol.ResultPayload
import dev.warsha.remoteble.protocol.ServiceNode
import dev.warsha.remoteble.protocol.orThrow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.flow.channelFlow

/**
 * Per-op-class request deadlines. Tuned for the *relayed* worst case, not localhost:
 * establishing a BLE link (scan→connect→bond) and discovering the full GATT table are
 * far slower and more variable than a single read/write, so they get more headroom than
 * the ordinary [op] timeout. Tighten these when you control the network path.
 */
data class RemoteTimeouts(
    val connect: Duration = 30.seconds,
    val discover: Duration = 20.seconds,
    val op: Duration = AgentSession.DEFAULT_TIMEOUT,
)

/**
 * The remote GATT operation layer: drives a single device's ops over an [AgentSession]
 * using protocol-level [CharRef]s. The Kable-facing [RemotePeripheral] adapts this to
 * Kable's `Peripheral` interface; tests and non-Kable callers can use it directly.
 */
class RemoteGattClient(
    val handle: DeviceHandle,
    private val session: AgentSession,
    private val timeouts: RemoteTimeouts = RemoteTimeouts(),
) {
    suspend fun connect() {
        session.request(Op.Connect(handle), timeouts.connect).orThrow()
    }

    suspend fun disconnect() {
        session.request(Op.Disconnect(handle), timeouts.op).orThrow()
    }

    suspend fun discover(): List<ServiceNode> =
        session.request(Op.Discover(handle), timeouts.discover).payloadAs<ResultPayload.Services>().services

    suspend fun read(char: CharRef): ByteArray =
        session.request(Op.Read(handle, char), timeouts.op).payloadAs<ResultPayload.Bytes>().value

    suspend fun write(char: CharRef, value: ByteArray, withResponse: Boolean) {
        session.request(Op.Write(handle, char, value, withResponse), timeouts.op).orThrow()
    }

    /**
     * Pipelines [values] as WithoutResponse writes to [char]: up to [window] frames are kept in
     * flight at once instead of serially awaiting each Reply before sending the next (0.8.3 /
     * feature C — see `ai-context/0.8.3-implementation-plan.md` §2c option 1). Frames are still
     * sent one per write and one at a time from this coroutine — [AgentSession.dispatch] sends
     * synchronously before returning — so **submission order onto the wire is preserved**; a
     * [window] of 1 degenerates to today's serial-await behavior. Returns each write's [OpResult]
     * in submission order; one write failing (e.g. a local queue-full) does not cancel the rest.
     *
     * **Submission order is preserved end-to-end**, by design on both sides: the client sends the
     * frames in order (above), and the reference agent — though it runs each `Command` on its own
     * coroutine — chains writes *per device* so they reach `backend.write` (and thus the radio's
     * FIFO GATT queue) in submission order rather than in coroutine-launch race order. Writes to
     * *different* devices, and non-write ops, stay fully concurrent. A non-reference agent must
     * uphold the same per-device write ordering (see agent-proxy-spec / design-decisions). Radio
     * *delivery* remains best-effort per WWR (no ATT ack); ordering here is about the enqueue
     * sequence — guaranteed in code and asserted in CI (`BleAgentTest`), with end-to-end on-radio
     * confirmation batched into the next hardware-rig round (plan §2d/§3).
     */
    suspend fun writeWithoutResponseBurst(
        char: CharRef,
        values: List<ByteArray>,
        window: Int = DEFAULT_BURST_WINDOW,
    ): List<OpResult> {
        require(window >= 1) { "window must be >= 1, was $window" }
        val inFlight = ArrayDeque<Deferred<OpResult>>(minOf(window, values.size))
        val results = ArrayList<OpResult>(values.size)

        suspend fun drainOldest() {
            results += inFlight.removeFirst().await()
        }

        for (value in values) {
            if (inFlight.size >= window) drainOldest()
            inFlight += session.dispatch(Op.Write(handle, char, value, withResponse = false), timeouts.op)
        }
        while (inFlight.isNotEmpty()) drainOldest()

        return results
    }

    suspend fun requestMtu(mtu: Int): Int =
        session.request(Op.RequestMtu(handle, mtu), timeouts.op).payloadAs<ResultPayload.Mtu>().mtu

    /** Reads the connected link's RSSI in dBm (requires the agent's `rssi` capability). */
    suspend fun readRssi(): Int =
        session.request(Op.ReadRssi(handle), timeouts.op).payloadAs<ResultPayload.Rssi>().rssi

    /** Reads a descriptor (requires the agent's `descriptors` capability). */
    suspend fun readDescriptor(desc: DescRef): ByteArray =
        session.request(Op.ReadDescriptor(handle, desc), timeouts.op).payloadAs<ResultPayload.Bytes>().value

    /** Writes a descriptor (requires the agent's `descriptors` capability). */
    suspend fun writeDescriptor(desc: DescRef, value: ByteArray) {
        session.request(Op.WriteDescriptor(handle, desc, value), timeouts.op).orThrow()
    }

    /** Bonds with the device, returning the resulting state (requires the `pairing` capability). */
    suspend fun pair(): BleBondState =
        session.request(Op.Pair(handle), timeouts.connect).payloadAs<ResultPayload.Bond>().state

    /** Removes the bond with the device (requires the `pairing` capability). */
    suspend fun unpair() {
        session.request(Op.Unpair(handle), timeouts.op).orThrow()
    }

    /** Requests a link connection priority (requires the `conn.priority` capability). */
    suspend fun requestConnectionPriority(priority: ConnPriority) {
        session.request(Op.RequestConnectionPriority(handle, priority), timeouts.op).orThrow()
    }

    /** Requests connection parameters (requires the agent's `conn.params` capability). */
    suspend fun setConnParams(profile: ConnProfile, hint: ConnParamHint? = null) {
        session.request(Op.SetConnParams(handle, profile, hint), timeouts.op).orThrow()
    }

    /**
     * Opens a subscription on collect and tears it down on cancel, bridging the
     * request side (observe.start/stop) and the event side (notifications by subId).
     * [onSubscription] runs once the subscription is established (after observe.start
     * succeeds) — mirroring Kable's `observe(..., onSubscription)` contract.
     */
    fun observe(char: CharRef, onSubscription: suspend () -> Unit = {}): Flow<ByteArray> = channelFlow {
        val subId = session.nextStreamId()
        val pump = session.events()
            // Issue observe.start from onSubscription — only once this collector is registered on
            // the shared event stream — so the first notification can't be emitted before we're
            // listening and get dropped. The caller's onSubscription hook then runs.
            .onSubscription {
                session.request(Op.ObserveStart(subId, handle, char), timeouts.op).orThrow()
                onSubscription()
            }
            .filterIsInstance<AgentEvent.Notification>()
            .filter { it.subId == subId }
            .onEach { send(it.value) }
            .launchIn(this)
        awaitClose {
            pump.cancel()
            session.fireAndForget(Op.ObserveStop(subId))
        }
    }

    companion object {
        /** Default in-flight window for [writeWithoutResponseBurst]. */
        const val DEFAULT_BURST_WINDOW: Int = 8
    }
}

/** Extracts the expected success payload, or throws with a clear [ErrorKind.UNSUPPORTED]. */
internal inline fun <reified T : ResultPayload> OpResult.payloadAs(): T {
    val payload = orThrow()
    return payload as? T
        ?: throw AgentException(
            AgentError(ErrorKind.UNSUPPORTED, message = "unexpected payload: $payload"),
        )
}
