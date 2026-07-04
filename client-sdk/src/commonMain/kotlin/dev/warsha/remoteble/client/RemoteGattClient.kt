package dev.warsha.remoteble.client

import dev.warsha.remoteble.protocol.AgentError
import dev.warsha.remoteble.protocol.AgentEvent
import dev.warsha.remoteble.protocol.AgentException
import dev.warsha.remoteble.protocol.BleBondState
import dev.warsha.remoteble.protocol.CharRef
import dev.warsha.remoteble.protocol.ConnPriority
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
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
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

    suspend fun requestMtu(mtu: Int): Int =
        session.request(Op.RequestMtu(handle, mtu), timeouts.op).payloadAs<ResultPayload.Mtu>().mtu

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

    /**
     * Opens a subscription on collect and tears it down on cancel, bridging the
     * request side (observe.start/stop) and the event side (notifications by subId).
     * [onSubscription] runs once the subscription is established (after observe.start
     * succeeds) — mirroring Kable's `observe(..., onSubscription)` contract.
     */
    fun observe(char: CharRef, onSubscription: suspend () -> Unit = {}): Flow<ByteArray> = channelFlow {
        val subId = session.nextStreamId()
        val pump = session.events()
            .filterIsInstance<AgentEvent.Notification>()
            .filter { it.subId == subId }
            .onEach { send(it.value) }
            .launchIn(this)
        session.request(Op.ObserveStart(subId, handle, char), timeouts.op).orThrow()
        onSubscription()
        awaitClose {
            pump.cancel()
            session.fireAndForget(Op.ObserveStop(subId))
        }
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
