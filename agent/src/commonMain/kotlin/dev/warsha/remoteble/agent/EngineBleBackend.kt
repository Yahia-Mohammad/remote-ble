package dev.warsha.remoteble.agent

import dev.warsha.remoteble.protocol.AdvertisementDto
import dev.warsha.remoteble.protocol.Capabilities
import dev.warsha.remoteble.protocol.CharNode
import dev.warsha.remoteble.protocol.CharRef
import dev.warsha.remoteble.protocol.DescRef
import dev.warsha.remoteble.protocol.DeviceHandle
import dev.warsha.remoteble.protocol.ErrorKind
import dev.warsha.remoteble.protocol.ScanFilter
import dev.warsha.remoteble.protocol.ServiceNode
import com.juul.kable.DiscoveredCharacteristic
import com.juul.kable.DiscoveredDescriptor
import com.juul.kable.DiscoveredService
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
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
class EngineBleBackend : BleBackend {

    override val capabilities: Set<String> = buildSet {
        add(Capabilities.DESCRIPTORS) // Kable exposes descriptor read/write on every platform.
    }

    // Plain map guarded by a multiplatform lock (kotlinx-atomicfu — java.util.concurrent has no
    // Kotlin/Native equivalent). resolve()/isConnected() are called from non-suspend contexts, so
    // this stays a synchronous lock rather than a suspend-only Mutex.
    private val lock = SynchronizedObject()
    private val peripherals = mutableMapOf<DeviceHandle, Peripheral>()

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
        bleOp(ErrorKind.CONNECTION_FAILED) { resolve(device).connect() }
    }

    override suspend fun disconnect(device: DeviceHandle) {
        val peripheral = synchronized(lock) { peripherals.remove(device) } ?: return
        try {
            peripheral.disconnect()
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            // Best-effort: the link is torn down regardless of how disconnect reports.
        } finally {
            // Always release the native peripheral; a reconnect recreates a fresh one.
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

    private fun DiscoveredCharacteristic.toNode(): CharNode = CharNode(
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
