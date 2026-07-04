package dev.warsha.remoteble.client

import dev.warsha.remoteble.protocol.AgentEvent
import dev.warsha.remoteble.protocol.BleBondState
import dev.warsha.remoteble.protocol.BleConnState
import dev.warsha.remoteble.protocol.CharRef
import dev.warsha.remoteble.protocol.ConnPriority
import dev.warsha.remoteble.protocol.DescRef
import dev.warsha.remoteble.protocol.DeviceHandle
import com.juul.kable.Characteristic
import com.juul.kable.Descriptor
import com.juul.kable.DiscoveredService
import com.juul.kable.ExperimentalApi
import com.juul.kable.Identifier
import com.juul.kable.Peripheral
import com.juul.kable.State
import com.juul.kable.WriteType
import kotlin.uuid.ExperimentalUuidApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach

/**
 * A Kable [Peripheral] backed by a remote agent. App code written against
 * [Peripheral] cannot tell this from a local one — switching local↔remote is a
 * factory choice (see [RemotePeripheralFactory]).
 *
 * Descriptor [read]/[write] are supported when the agent advertises the
 * `descriptors` capability (the op is otherwise answered with `UNSUPPORTED`).
 * Connected-RSSI ([rssi]) has no wire op and remains unsupported.
 * [maximumWriteValueLengthForType] reports the MTU the agent negotiated on connect
 * (requesting [requestedMtu]), falling back to the ATT default of 23.
 */
@OptIn(ExperimentalApi::class, ExperimentalUuidApi::class)
public class RemotePeripheral(
    private val handle: DeviceHandle,
    private val session: AgentSession,
    name: String? = null,
    private val requestedMtu: Int = DEFAULT_REQUESTED_MTU,
    timeouts: RemoteTimeouts = RemoteTimeouts(),
    dispatchers: DispatcherProvider = DefaultDispatcherProvider,
) : Peripheral {

    private val gatt = RemoteGattClient(handle, session, timeouts)

    override val scope: CoroutineScope = CoroutineScope(SupervisorJob() + dispatchers.default)

    private val _state = MutableStateFlow<State>(State.Disconnected(null))
    override val state: StateFlow<State> = _state.asStateFlow()

    private val _services = MutableStateFlow<List<DiscoveredService>?>(null)
    override val services: StateFlow<List<DiscoveredService>?> = _services.asStateFlow()

    // The MTU the agent negotiated on the last connect; ATT default while disconnected.
    private val negotiatedMtu = MutableStateFlow(DEFAULT_ATT_MTU)

    // Lazy: the agent-scoped handle is opaque and may not parse as the local
    // platform's Identifier; it's not needed to operate a remote peripheral. Use
    // deviceHandleToIdentifier so Android's MAC-format check doesn't reject UUID handles.
    override val identifier: Identifier by lazy { deviceHandleToIdentifier(handle.value) }

    @ExperimentalApi
    override val name: String? = name

    private var connectionScope: CoroutineScope? = null

    init {
        // React to physical-link drops reported by the agent (independent of any
        // IP-transport reconnect). Surfaces the drop into Kable's state machine.
        session.events()
            .filterIsInstance<AgentEvent.ConnectionState>()
            .filter { it.device == handle }
            .onEach { event ->
                if (event.state == BleConnState.DISCONNECTED && _state.value !is State.Disconnected) {
                    teardownConnection()
                    _state.value = State.Disconnected(null)
                }
            }
            .launchIn(scope)
    }

    override suspend fun connect(): CoroutineScope {
        try {
            initializeGattConnection()
            val connection = establishConnectionScope()
            _state.value = State.Connected(connection)
            return connection
        } catch (t: Throwable) {
            if (t !is CancellationException) {
                // A later step (discover/mtu) may have failed after the agent already
                // brought the link up; tell it to drop so we don't leak a half-open
                // connection that only the agent's release grace would eventually reap.
                runCatchingNonCancellation { gatt.disconnect() }
                teardownConnection()
                _state.value = State.Disconnected(null)
            }
            throw t
        }
    }

    private suspend fun initializeGattConnection() {
        _state.value = State.Connecting.Bluetooth
        gatt.connect()

        _state.value = State.Connecting.Services
        _services.value = gatt.discover().map { it.toDiscoveredService() }

        // Learn link MTU if supported by agent/platform; fall back to ATT default
        runCatchingNonCancellation {
            negotiatedMtu.value = gatt.requestMtu(requestedMtu)
        }

        _state.value = State.Connecting.Observes
    }

    private fun establishConnectionScope(): CoroutineScope =
        CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job])).also {
            connectionScope = it
        }

    override suspend fun disconnect() {
        _state.value = State.Disconnecting
        runCatchingNonCancellation { gatt.disconnect() }
        teardownConnection()
        _state.value = State.Disconnected(null)
    }

    override suspend fun read(characteristic: Characteristic): ByteArray =
        gatt.read(characteristic.toCharRef())

    override suspend fun write(characteristic: Characteristic, data: ByteArray, writeType: WriteType) {
        gatt.write(characteristic.toCharRef(), data, withResponse = writeType == WriteType.WithResponse)
    }

    override fun observe(characteristic: Characteristic, onSubscriptionAction: suspend () -> Unit): Flow<ByteArray> =
        gatt.observe(characteristic.toCharRef(), onSubscriptionAction)

    override suspend fun maximumWriteValueLengthForType(writeType: WriteType): Int =
        negotiatedMtu.value - ATT_HEADER_SIZE

    // --- Pairing (beyond Kable's Peripheral surface; requires the agent's `pairing` capability) ---

    /** Bonds with the peripheral, returning the resulting state. */
    public suspend fun pair(): BleBondState = gatt.pair()

    /** Removes the bond with the peripheral. */
    public suspend fun unpair(): Unit = gatt.unpair()

    /** Requests a link connection priority (requires the agent's `conn.priority` capability). */
    public suspend fun requestConnectionPriority(priority: ConnPriority): Unit =
        gatt.requestConnectionPriority(priority)

    /** Bond-state transitions reported by the agent (pair/unpair and OS-initiated changes). */
    public val bondState: Flow<BleBondState> = session.events()
        .filterIsInstance<AgentEvent.BondState>()
        .filter { it.device == handle }
        .map { it.state }

    @ExperimentalApi
    override suspend fun rssi(): Int =
        throw UnsupportedOperationException("Connected RSSI is not part of the remote protocol (v1).")

    override suspend fun read(descriptor: Descriptor): ByteArray =
        gatt.readDescriptor(descriptor.toDescRef())

    override suspend fun write(descriptor: Descriptor, data: ByteArray): Unit =
        gatt.writeDescriptor(descriptor.toDescRef(), data)

    override fun close() {
        scope.cancel()
    }

    private fun teardownConnection() {
        connectionScope?.cancel()
        connectionScope = null
        _services.value = null
        negotiatedMtu.value = DEFAULT_ATT_MTU
    }

    private fun Characteristic.toCharRef(): CharRef =
        CharRef(service = serviceUuid.toString(), characteristic = characteristicUuid.toString())

    private fun Descriptor.toDescRef(): DescRef =
        DescRef(
            service = serviceUuid.toString(),
            characteristic = characteristicUuid.toString(),
            descriptor = descriptorUuid.toString(),
        )

    public companion object {
        private const val DEFAULT_ATT_MTU = 23
        private const val ATT_HEADER_SIZE = 3

        /** Requested on connect; the agent replies with what it actually negotiated. */
        public const val DEFAULT_REQUESTED_MTU: Int = 247
    }
}
