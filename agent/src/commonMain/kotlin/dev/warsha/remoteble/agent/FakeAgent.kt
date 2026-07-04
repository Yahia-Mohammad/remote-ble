package dev.warsha.remoteble.agent

import dev.warsha.remoteble.protocol.AdvertisementDto
import dev.warsha.remoteble.protocol.AgentEvent
import dev.warsha.remoteble.protocol.BleBondState
import dev.warsha.remoteble.protocol.BleConnState
import dev.warsha.remoteble.protocol.CborProtocolCodec
import dev.warsha.remoteble.protocol.CharNode
import dev.warsha.remoteble.protocol.ClientHello
import dev.warsha.remoteble.protocol.Command
import dev.warsha.remoteble.protocol.DeviceHandle
import dev.warsha.remoteble.protocol.Event
import dev.warsha.remoteble.protocol.Op
import dev.warsha.remoteble.protocol.OpResult
import dev.warsha.remoteble.protocol.PROTOCOL_VERSION
import dev.warsha.remoteble.protocol.ProtocolCodec
import dev.warsha.remoteble.protocol.Reply
import dev.warsha.remoteble.protocol.ResultPayload
import dev.warsha.remoteble.protocol.ServerHello
import dev.warsha.remoteble.protocol.ServiceNode
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * A canned agent backend with no real radio. Consumes [Command] frames and emits
 * [Reply]/[Event] frames over an opaque byte link. Used to exercise the session
 * and adapters end-to-end (Phase 2 in-memory, Phase 3 behind a WebSocket server)
 * before the real Kable-backed engine arrives in Phase 4.
 *
 * Scans and subscriptions emit periodically (cycling the configured values) for
 * as long as they are active, which also sidesteps hot-flow subscription races.
 */
class FakeAgent(
    private val incoming: Flow<ByteArray>,
    private val outgoing: suspend (ByteArray) -> Unit,
    private val scope: CoroutineScope,
    private val config: Config = Config(),
    private val codec: ProtocolCodec = CborProtocolCodec(),
) {
    class Config(
        val services: List<ServiceNode> = DEFAULT_SERVICES,
        val readValue: ByteArray = byteArrayOf(0x42, 0x07),
        val descriptorValue: ByteArray = byteArrayOf(0x01, 0x00),
        val advertisements: List<AdvertisementDto> = DEFAULT_ADVERTISEMENTS,
        val notificationValues: List<ByteArray> = DEFAULT_NOTIFICATIONS,
        val emitInterval: Duration = 50.milliseconds,
        /** Artificial delay before replying — used to hold a request in-flight. */
        val replyDelay: Duration = Duration.ZERO,
        /** Capabilities this fake advertises in its ServerHello (intersected with the client's). */
        val capabilities: Set<String> = emptySet(),
    )

    private val scanJobs = mutableMapOf<Long, Job>()
    private val notifyJobs = mutableMapOf<Long, Job>()

    /** Number of scans currently streaming results (for tests asserting teardown). */
    val activeScanCount: Int get() = scanJobs.size

    /** Number of subscriptions currently streaming notifications. */
    val activeNotifyCount: Int get() = notifyJobs.size

    fun start(): Job = scope.launch {
        incoming.collect { bytes ->
            when (val frame = codec.decode(bytes)) {
                is Command -> handle(frame)
                is ClientHello -> outgoing(
                    codec.encode(
                        ServerHello(
                            version = PROTOCOL_VERSION,
                            capabilities = frame.capabilities intersect config.capabilities,
                        ),
                    ),
                )
                else -> Unit
            }
        }
    }

    private suspend fun handle(cmd: Command) {
        if (config.replyDelay > Duration.ZERO) delay(config.replyDelay)
        when (val op = cmd.op) {
            is Op.Connect -> {
                reply(cmd.cid, OpResult.Ok())
                emit(AgentEvent.ConnectionState(op.device, BleConnState.CONNECTED))
            }
            is Op.Disconnect -> {
                reply(cmd.cid, OpResult.Ok())
                emit(AgentEvent.ConnectionState(op.device, BleConnState.DISCONNECTED))
            }
            is Op.Discover -> reply(cmd.cid, OpResult.Ok(ResultPayload.Services(config.services)))
            is Op.Read -> reply(cmd.cid, OpResult.Ok(ResultPayload.Bytes(config.readValue)))
            is Op.Write -> reply(cmd.cid, OpResult.Ok())
            is Op.RequestMtu -> reply(cmd.cid, OpResult.Ok(ResultPayload.Mtu(op.mtu)))
            is Op.ReadDescriptor -> reply(cmd.cid, OpResult.Ok(ResultPayload.Bytes(config.descriptorValue)))
            is Op.WriteDescriptor -> reply(cmd.cid, OpResult.Ok())
            is Op.Pair -> {
                reply(cmd.cid, OpResult.Ok(ResultPayload.Bond(BleBondState.BONDED)))
                emit(AgentEvent.BondState(op.device, BleBondState.BONDED))
            }
            is Op.Unpair -> {
                reply(cmd.cid, OpResult.Ok())
                emit(AgentEvent.BondState(op.device, BleBondState.NONE))
            }
            is Op.RequestConnectionPriority -> reply(cmd.cid, OpResult.Ok())
            is Op.ScanStart -> {
                reply(cmd.cid, OpResult.Ok())
                startScan(op.scanId)
            }
            is Op.ScanStop -> {
                stopScan(op.scanId)
                reply(cmd.cid, OpResult.Ok())
            }
            is Op.ObserveStart -> {
                reply(cmd.cid, OpResult.Ok())
                startNotify(op.subId)
            }
            is Op.ObserveStop -> {
                stopNotify(op.subId)
                reply(cmd.cid, OpResult.Ok())
            }
        }
    }

    private fun startScan(scanId: Long) {
        scanJobs.remove(scanId)?.cancel()
        scanJobs[scanId] = scope.launch {
            var i = 0
            while (isActive) {
                val ad = config.advertisements[i % config.advertisements.size]
                emit(AgentEvent.ScanResult(scanId, ad))
                i++
                delay(config.emitInterval)
            }
        }
    }

    private fun stopScan(scanId: Long) {
        scanJobs.remove(scanId)?.cancel()
    }

    private fun startNotify(subId: Long) {
        notifyJobs.remove(subId)?.cancel()
        notifyJobs[subId] = scope.launch {
            var i = 0
            while (isActive) {
                val value = config.notificationValues[i % config.notificationValues.size]
                emit(AgentEvent.Notification(subId, value))
                i++
                delay(config.emitInterval)
            }
        }
    }

    private fun stopNotify(subId: Long) {
        notifyJobs.remove(subId)?.cancel()
    }

    private suspend fun reply(cid: Long, result: OpResult) = outgoing(codec.encode(Reply(cid, result)))

    private suspend fun emit(event: AgentEvent) = outgoing(codec.encode(Event(event)))

    companion object {
        private val DEVICE_A = DeviceHandle("FA:KE:00:00:00:0A")
        private val DEVICE_B = DeviceHandle("FA:KE:00:00:00:0B")

        val DEFAULT_SERVICES: List<ServiceNode> = listOf(
            ServiceNode(
                uuid = "0000180d-0000-1000-8000-00805f9b34fb",
                characteristics = listOf(
                    CharNode(
                        uuid = "00002a37-0000-1000-8000-00805f9b34fb",
                        properties = 0x10, // notify
                        descriptors = listOf("00002902-0000-1000-8000-00805f9b34fb"),
                    ),
                    CharNode(uuid = "00002a38-0000-1000-8000-00805f9b34fb", properties = 0x02), // read
                ),
            ),
        )

        val DEFAULT_ADVERTISEMENTS: List<AdvertisementDto> = listOf(
            AdvertisementDto(device = DEVICE_A, name = "Fake HRM", rssi = -55, serviceUuids = listOf("180d")),
            AdvertisementDto(device = DEVICE_B, name = "Fake Battery", rssi = -70, serviceUuids = listOf("180f")),
        )

        val DEFAULT_NOTIFICATIONS: List<ByteArray> = listOf(
            byteArrayOf(0x00, 0x48),
            byteArrayOf(0x00, 0x49),
            byteArrayOf(0x00, 0x4a),
        )
    }
}
