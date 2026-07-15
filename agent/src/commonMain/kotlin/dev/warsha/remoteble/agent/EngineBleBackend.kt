package dev.warsha.remoteble.agent

import dev.warsha.remoteble.log.Logger
import dev.warsha.remoteble.protocol.AdvertisementDto
import dev.warsha.remoteble.protocol.AgentError
import dev.warsha.remoteble.protocol.Capabilities
import dev.warsha.remoteble.protocol.CharNode
import dev.warsha.remoteble.protocol.CharRef
import dev.warsha.remoteble.protocol.ConnParamHint
import dev.warsha.remoteble.protocol.ConnPriority
import dev.warsha.remoteble.protocol.ConnProfile
import dev.warsha.remoteble.protocol.DescRef
import dev.warsha.remoteble.protocol.DeviceHandle
import dev.warsha.remoteble.protocol.ErrorKind
import dev.warsha.remoteble.protocol.ScanFilter
import dev.warsha.remoteble.protocol.ServiceNode
import com.juul.kable.DiscoveredCharacteristic
import com.juul.kable.DiscoveredDescriptor
import com.juul.kable.DiscoveredService
import com.juul.kable.ExperimentalApi
import com.juul.kable.Filter
import com.juul.kable.Peripheral
import com.juul.kable.Scanner
import com.juul.kable.State
import com.juul.kable.WriteType
import com.juul.kable.read
import com.juul.kable.toIdentifier
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The real [BleBackend], built over Kable's native `Peripheral`/`Scanner` API — Kable's
 * JVM (btleplug) backend on macOS/Linux, Android's own platform BLE stack on Android, and
 * CoreBluetooth on iOS. This class only ever calls Kable's common, platform-agnostic API,
 * so one implementation drives all three.
 *
 * Kable is connection-oriented: a [Peripheral] is a long-lived object that owns its radio
 * connection (on its own [Peripheral.scope]) until [Peripheral.disconnect]/[Peripheral.close].
 * One is cached per [DeviceHandle] so ops resolve back to the same connection; it is closed
 * and evicted on [disconnect] to release its native handles (Kable recommends discarding a
 * peripheral's references after disconnect — a fresh one is created on the next [connect]).
 *
 * Failures from Kable are mapped to [ErrorKind]s and thrown as `AgentException` (via
 * [bleError]); [CancellationException] is always allowed to propagate so structured
 * cancellation is preserved.
 */
@OptIn(ExperimentalUuidApi::class)
class EngineBleBackend(
    // Agent-lifetime scope for the per-connection state watchers (see [connect]). Defaults to a
    // standalone scope so plain `EngineBleBackend()` (tests) works; the DI graph injects the shared
    // agent scope so the watchers are torn down with the process, not leaked.
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : BleBackend {

    override val capabilities: Set<String> = buildSet {
        add(Capabilities.DESCRIPTORS) // Kable exposes descriptor read/write on every platform.
        // Connected RSSI is a live read only on Kable's Android/Apple backends; the JVM/btleplug
        // backend returns cached advertisement RSSI (or Int.MIN_VALUE), so advertise it only where
        // readRssi() below is genuinely a connected read.
        if (agentRssiSupported()) add(Capabilities.RSSI)
        // conn.params and its coarse conn.priority alias are both driven by the same platform
        // binding (Android's requestConnectionPriority) — advertise both from one predicate.
        if (agentConnParamsSupported()) {
            add(Capabilities.CONN_PARAMS)
            add(Capabilities.CONN_PRIORITY)
        }
    }

    // Plain map guarded by a multiplatform lock (kotlinx-atomicfu — java.util.concurrent has no
    // Kotlin/Native equivalent). resolve()/isConnected() are called from non-suspend contexts, so
    // this stays a synchronous lock rather than a suspend-only Mutex.
    private val lock = SynchronizedObject()
    private val peripherals = mutableMapOf<DeviceHandle, Peripheral>()

    // Native unsolicited-drop stream (see [connectionDrops]). DROP_OLDEST + a small buffer so a slow
    // or absent collector can never suspend the per-connection watcher that emits here; the polling
    // ConnectionWatcher is the backstop if a rare overflow drops a signal.
    private val drops = MutableSharedFlow<ConnectionDrop>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    override fun connectionDrops(): Flow<ConnectionDrop> = drops

    /**
     * Resolves [device] to a cached (or freshly created) [Peripheral]. `toIdentifier()` throws
     * on a malformed handle — reachable with hostile input from a client — so that failure is
     * mapped to a typed [ErrorKind] here
     * rather than surfacing as a raw throwable on the `observe()` path, which isn't wrapped in
     * [bleOp]. [ErrorKind.UNKNOWN_DEVICE] ("never reached the radio") is the accurate fit: a handle
     * that won't even parse identifies no device the agent could reach.
     */
    private fun resolve(device: DeviceHandle): Peripheral = synchronized(lock) {
        peripherals.getOrPut(device) {
            val identifier = try {
                device.value.toIdentifier()
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                bleError(ErrorKind.UNKNOWN_DEVICE, message = "malformed device handle: ${t.message}")
            }
            peripheralByIdentifier(identifier)
        }
    }

    override fun scan(filters: List<ScanFilter>): Flow<AdvertisementDto> = channelFlow {
        val scanner = Scanner {
            filters {
                for (filter in filters) {
                    match {
                        filter.service?.let { services = listOf(Uuid.parse(it)) }
                        filter.name?.let { name = Filter.Name.Exact(it) }
                    }
                }
            }
        }
        // `send` (not `trySend`) applies backpressure so advertisements aren't dropped; the
        // collect ends when this channelFlow's scope is cancelled, which stops the scan.
        scanner.advertisements.collect { advertisement ->
            send(
                AdvertisementDto(
                    device = DeviceHandle(advertisement.identifier.toString()),
                    name = advertisement.name,
                    rssi = advertisement.rssi,
                ),
            )
        }
    }

    override suspend fun connect(device: DeviceHandle) {
        val peripheral = resolve(device)
        bleOp(ErrorKind.CONNECTION_FAILED) { peripheral.connect() }
        Logger.debug(LogTags.ENGINE) { "Kable connect ok [dev=${device.value}]" }
        scope.launch { watchForUnsolicitedDrop(device, peripheral) }
    }

    /**
     * Suspends until [peripheral] transitions to [State.Disconnected], then emits a
     * [ConnectionDrop] — UNLESS this was an explicit [disconnect], which removes and closes the
     * peripheral first, so it's no longer the active instance for [device]. Only a drop the radio
     * initiated on its own leaves the peripheral still mapped here, which is exactly the unsolicited
     * case [connectionDrops] promises.
     */
    private suspend fun watchForUnsolicitedDrop(device: DeviceHandle, peripheral: Peripheral) {
        val disconnected = peripheral.state.first { it is State.Disconnected } as State.Disconnected
        val stillActive = synchronized(lock) { peripherals[device] === peripheral }
        if (stillActive) {
            Logger.debug(LogTags.ENGINE) { "Kable state → Disconnected [dev=${device.value} reason=${disconnected.status}]: unsolicited drop" }
            drops.tryEmit(ConnectionDrop(device, disconnected.reason()))
        }
    }

    /** Maps Kable's disconnect [State.Disconnected.Status] to a wire [AgentError] cause. */
    private fun State.Disconnected.reason(): AgentError =
        AgentError(ErrorKind.DISCONNECTED, message = status?.let { it::class.simpleName } ?: "peer disconnected")

    override suspend fun disconnect(device: DeviceHandle) {
        val peripheral = synchronized(lock) { peripherals.remove(device) } ?: return
        try {
            peripheral.disconnect()
            Logger.debug(LogTags.ENGINE) { "Kable disconnect ok [dev=${device.value}]" }
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            Logger.warn(LogTags.ENGINE) { "Kable disconnect error (best-effort) [dev=${device.value}]: ${t.message}" }
        } finally {
            runCatchingNonCancellation { peripheral.close() }
        }
    }

    override fun isConnected(device: DeviceHandle): Boolean =
        synchronized(lock) { peripherals[device] }?.state?.value is State.Connected

    override suspend fun checkLiveness(device: DeviceHandle): Boolean {
        val peripheral = synchronized(lock) { peripherals[device] } ?: return false
        if (peripheral.state.value !is State.Connected) return false
        // Not discovered yet (client connected but hasn't discovered): nothing to probe with; the
        // cached Connected state is the best we have.
        val characteristics = peripheral.services.value?.flatMap { it.characteristics } ?: return true

        // Force a real GATT round-trip so a peripheral that vanished without a clean BLE-level
        // teardown is caught even while Kable/the native stack still reports it Connected (see the
        // kdoc on BleBackend.checkLiveness). Reads only, never writes: side-effect-free by GATT
        // convention, unlike a write. Prefer a readable characteristic; failing that (a
        // notify/indicate-only peripheral) read its CCCD descriptor — always readable per spec and
        // present on every notify/indicate characteristic — so those devices are actively probed
        // too instead of being silently trusted. Only a peripheral exposing neither is un-probable.
        val probe: (suspend () -> ByteArray)? =
            characteristics.firstOrNull { it.properties.read }?.let { c -> suspend { peripheral.read(c) } }
                ?: characteristics.firstNotNullOfOrNull { it.cccd() }?.let { d -> suspend { peripheral.read(d) } }
        probe ?: return true // nothing safe to probe with; trust the cached state

        return try {
            withTimeoutOrNull(LIVENESS_PROBE_TIMEOUT) { probe() } != null
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            false
        }
    }

    /** The Client Characteristic Configuration descriptor (0x2902), if this characteristic has one. */
    private fun DiscoveredCharacteristic.cccd(): DiscoveredDescriptor? =
        descriptors.firstOrNull { it.descriptorUuid == CCCD_UUID }

    override suspend fun discover(device: DeviceHandle): List<ServiceNode> {
        val peripheral = resolve(device)
        requireConnected(peripheral)
        // Kable discovers services automatically during connect(), so they're present once
        // the peripheral reaches Connected.
        val services = peripheral.services.value
            ?: bleError(ErrorKind.GATT_ERROR, message = "services not discovered")
        return services.map { it.toNode() }
    }

    override suspend fun read(device: DeviceHandle, char: CharRef): ByteArray {
        val peripheral = connectedPeripheral(device)
        val characteristic = peripheral.findCharacteristic(char)
        return bleOp(ErrorKind.READ_FAILED) { peripheral.read(characteristic) }
    }

    override suspend fun write(device: DeviceHandle, char: CharRef, value: ByteArray, withResponse: Boolean) {
        val peripheral = connectedPeripheral(device)
        val characteristic = peripheral.findCharacteristic(char)
        val writeType = if (withResponse) WriteType.WithResponse else WriteType.WithoutResponse
        bleOp(ErrorKind.WRITE_FAILED) { peripheral.write(characteristic, value, writeType) }
    }

    override fun observe(device: DeviceHandle, char: CharRef): Flow<ByteArray> {
        val peripheral = connectedPeripheral(device)
        return peripheral.observe(peripheral.findCharacteristic(char))
    }

    override suspend fun requestMtu(device: DeviceHandle, mtu: Int): Int {
        // btleplug exposes no MTU-negotiation API, so the agent doesn't advertise an MTU
        // capability. Rather than echo the request (which would let a client believe a large
        // MTU was negotiated and oversize its writes), report the ATT default minimum — the
        // only value guaranteed safe for a client sizing payloads against the result.
        return DEFAULT_ATT_MTU
    }

    @OptIn(ExperimentalApi::class)
    override suspend fun readRssi(device: DeviceHandle): Int {
        val peripheral = connectedPeripheral(device)
        val rssi = bleOp(ErrorKind.READ_FAILED) { peripheral.rssi() }
        // Defensive: Kable's btleplug backend returns Int.MIN_VALUE when it has no cached value. This
        // backend only advertises the `rssi` capability where rssi() is a real connected read
        // (agentRssiSupported()), so this guards a stub/edge case rather than a normal path — surface
        // it as UNSUPPORTED so the sentinel never reaches the client as a real reading.
        if (rssi == Int.MIN_VALUE) bleError(ErrorKind.UNSUPPORTED, message = "connected RSSI unavailable")
        return rssi
    }

    override suspend fun requestConnectionPriority(device: DeviceHandle, priority: ConnPriority) {
        val peripheral = connectedPeripheral(device)
        val accepted = applyConnParams(peripheral, priority.toConnProfile(), hint = null)
        if (!accepted) bleError(ErrorKind.WRITE_FAILED, message = "connection priority request rejected")
    }

    override suspend fun setConnParams(device: DeviceHandle, profile: ConnProfile, hint: ConnParamHint?) {
        val peripheral = connectedPeripheral(device)
        val accepted = applyConnParams(peripheral, profile, hint)
        if (!accepted) bleError(ErrorKind.WRITE_FAILED, message = "conn-params request rejected")
    }

    private fun ConnPriority.toConnProfile(): ConnProfile = when (this) {
        ConnPriority.LOW_POWER -> ConnProfile.LOW_POWER
        ConnPriority.BALANCED -> ConnProfile.BALANCED
        ConnPriority.HIGH -> ConnProfile.LOW_LATENCY
    }

    override suspend fun readDescriptor(device: DeviceHandle, desc: DescRef): ByteArray {
        val peripheral = connectedPeripheral(device)
        val descriptor = peripheral.findDescriptor(desc)
        return bleOp(ErrorKind.READ_FAILED) { peripheral.read(descriptor) }
    }

    override suspend fun writeDescriptor(device: DeviceHandle, desc: DescRef, value: ByteArray) {
        val peripheral = connectedPeripheral(device)
        val descriptor = peripheral.findDescriptor(desc)
        bleOp(ErrorKind.WRITE_FAILED) { peripheral.write(descriptor, value) }
    }

    // --- helpers ---

    /** Runs a Kable op, mapping its failure to [failure] while letting cancellation propagate. */
    private suspend inline fun <T> bleOp(failure: ErrorKind, block: () -> T): T =
        try {
            block()
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            bleError(failure, message = t.message)
        }

    /** Resolves [device] and fails with [ErrorKind.NOT_CONNECTED] unless it is connected. */
    private fun connectedPeripheral(device: DeviceHandle): Peripheral =
        resolve(device).also(::requireConnected)

    private fun requireConnected(peripheral: Peripheral) {
        if (peripheral.state.value !is State.Connected) bleError(ErrorKind.NOT_CONNECTED)
    }

    private fun Peripheral.discoveredService(uuid: Uuid): DiscoveredService {
        val services = services.value ?: bleError(ErrorKind.CHARACTERISTIC_NOT_FOUND)
        return services.find { it.serviceUuid == uuid } ?: bleError(ErrorKind.CHARACTERISTIC_NOT_FOUND)
    }

    private fun Peripheral.findCharacteristic(char: CharRef): DiscoveredCharacteristic {
        val characteristicUuid = Uuid.parse(char.characteristic)
        return discoveredService(Uuid.parse(char.service))
            .characteristics
            .filter { it.characteristicUuid == characteristicUuid }
            .getOrNull(char.instance)
            ?: bleError(ErrorKind.CHARACTERISTIC_NOT_FOUND)
    }

    private fun Peripheral.findDescriptor(desc: DescRef): DiscoveredDescriptor {
        val descriptorUuid = Uuid.parse(desc.descriptor)
        return findCharacteristic(CharRef(desc.service, desc.characteristic))
            .descriptors
            .filter { it.descriptorUuid == descriptorUuid }
            .getOrNull(desc.instance)
            ?: bleError(ErrorKind.CHARACTERISTIC_NOT_FOUND, message = "descriptor not found")
    }

    private fun DiscoveredService.toNode(): ServiceNode = ServiceNode(
        uuid = serviceUuid.toString(),
        characteristics = characteristics.map { it.toNode() },
    )

    // internal (not private): EngineBleBackendJvmTest exercises this directly against a fake
    // DiscoveredCharacteristic to regression-test that property bits survive the mapping on every
    // engine, without needing a real radio connection.
    internal fun DiscoveredCharacteristic.toNode(): CharNode = CharNode(
        uuid = characteristicUuid.toString(),
        properties = properties.value,
        descriptors = descriptors.map { it.descriptorUuid.toString() },
    )

    private companion object {
        /** ATT default minimum MTU (bytes); always safe to advertise when none was negotiated. */
        const val DEFAULT_ATT_MTU = 23

        /** How long [checkLiveness] waits for its probe read before treating the link as dead. */
        val LIVENESS_PROBE_TIMEOUT = 5.seconds

        /** Client Characteristic Configuration Descriptor UUID — the [checkLiveness] probe of last resort. */
        val CCCD_UUID = Uuid.parse("00002902-0000-1000-8000-00805f9b34fb")
    }
}
