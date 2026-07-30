package dev.warsha.remoteble.agent

import dev.warsha.remoteble.protocol.AdvertisementDto
import dev.warsha.remoteble.protocol.Capabilities
import dev.warsha.remoteble.protocol.CharNode
import dev.warsha.remoteble.protocol.CharRef
import dev.warsha.remoteble.protocol.DeviceHandle
import dev.warsha.remoteble.protocol.ErrorKind
import dev.warsha.remoteble.protocol.ScanFilter
import dev.warsha.remoteble.protocol.ServiceNode
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch

/**
 * Production radio-less [BleBackend] backed by a validated [SimulationProfile]. It owns only
 * simulation state; the normal Kable engine and the protocol/lease server remain unchanged.
 */
class SimulatedBleBackend(
    profile: SimulationProfile,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : BleBackend {
    private val profile = profile.validated()
    private val peripherals = this.profile.peripherals.associateBy { DeviceHandle(it.id) }
    private val lock = SynchronizedObject()
    private val connected = mutableSetOf<DeviceHandle>()
    private val attempts = mutableMapOf<DeviceHandle, Int>()
    private val storedValues = mutableMapOf<CharacteristicKey, ByteArray>()
    private val sourcePositions = mutableMapOf<ValueKey, Long>()
    private val drops = MutableSharedFlow<ConnectionDrop>(extraBufferCapacity = 32)

    // Simulated connected RSSI is derived from the declared advertisement, so it is truthful to
    // advertise. Other optional operations remain unsupported unless a profile model is added.
    override val capabilities: Set<String> = setOf(Capabilities.RSSI)

    override fun connectionDrops(): Flow<ConnectionDrop> = drops

    override fun scan(filters: List<ScanFilter>): Flow<AdvertisementDto> = flow {
        var round = 0L
        while (true) {
            for (peripheral in profile.peripherals) {
                if (peripheral.matches(filters)) {
                    val jitter = deterministicJitter(peripheral, round)
                    emit(
                        AdvertisementDto(
                            device = DeviceHandle(peripheral.id),
                            name = peripheral.advertisement.name,
                            rssi = peripheral.advertisement.rssi + jitter,
                            serviceUuids = peripheral.advertisement.serviceUuids,
                        ),
                    )
                }
            }
            round++
            delay(profile.peripherals.minOf { it.advertisement.intervalMs }.milliseconds)
        }
    }

    override suspend fun connect(device: DeviceHandle) {
        val peripheral = peripheral(device)
        val attempt = synchronized(lock) { (attempts[device] ?: 0) + 1 }.also { synchronized(lock) { attempts[device] = it } }
        delay(peripheral.connect.latencyMs.milliseconds)
        if (attempt <= peripheral.connect.failFirst) {
            bleError(ErrorKind.CONNECTION_FAILED, message = "simulated connect failure #$attempt for ${device.value}")
        }
        synchronized(lock) { connected += device }
        peripheral.connect.dropAfterMs?.let { after ->
            scope.launch {
                delay(after.milliseconds)
                val dropped = synchronized(lock) { connected.remove(device) }
                if (dropped) drops.tryEmit(ConnectionDrop(device))
            }
        }
    }

    override suspend fun disconnect(device: DeviceHandle) {
        peripheral(device)
        synchronized(lock) { connected.remove(device) }
    }

    override fun isConnected(device: DeviceHandle): Boolean = synchronized(lock) { device in connected }

    override suspend fun discover(device: DeviceHandle): List<ServiceNode> = peripheral(device).services.map { service ->
        ServiceNode(
            uuid = service.uuid.canonicalUuid("service"),
            characteristics = service.characteristics.map { characteristic ->
                CharNode(
                    uuid = characteristic.uuid.canonicalUuid("characteristic"),
                    properties = characteristic.properties.toPropertyBits(),
                )
            },
        )
    }

    override suspend fun read(device: DeviceHandle, char: CharRef): ByteArray {
        requireConnected(device)
        val characteristic = characteristic(device, char)
        val key = CharacteristicKey(device, char.normalized())
        synchronized(lock) { storedValues[key]?.copyOf() }?.let { return it }
        return characteristic.read?.next(key, "read", sourcePositions, lock)
            ?: bleError(ErrorKind.CHARACTERISTIC_NOT_FOUND, message = "characteristic is not readable")
    }

    override suspend fun write(device: DeviceHandle, char: CharRef, value: ByteArray, withResponse: Boolean) {
        requireConnected(device)
        val characteristic = characteristic(device, char)
        val properties = characteristic.properties.map { it.lowercase() }.toSet()
        if (withResponse && "write" !in properties) {
            bleError(ErrorKind.WRITE_FAILED, message = "characteristic does not accept write-with-response")
        }
        if (!withResponse && "writewithoutresponse" !in properties) {
            bleError(ErrorKind.WRITE_FAILED, message = "characteristic does not accept write-without-response")
        }
        val behavior = characteristic.write
            ?: bleError(ErrorKind.CHARACTERISTIC_NOT_FOUND, message = "characteristic is not writable")
        if (!behavior.accept) bleError(ErrorKind.WRITE_FAILED, message = "simulated write rejected")
        if (behavior.storesValue) synchronized(lock) { storedValues[CharacteristicKey(device, char.normalized())] = value.copyOf() }
    }

    override fun observe(device: DeviceHandle, char: CharRef): Flow<ByteArray> = flow {
        requireConnected(device)
        val behavior = characteristic(device, char).notify
            ?: bleError(ErrorKind.CHARACTERISTIC_NOT_FOUND, message = "characteristic is not observable")
        val key = CharacteristicKey(device, char.normalized())
        while (true) {
            emit(behavior.values.next(key, "notify", sourcePositions, lock))
            delay(behavior.intervalMs.milliseconds)
        }
    }

    override suspend fun requestMtu(device: DeviceHandle, mtu: Int): Int {
        requireConnected(device)
        return mtu
    }

    override suspend fun readRssi(device: DeviceHandle): Int {
        requireConnected(device)
        return peripheral(device).advertisement.rssi
    }

    private fun peripheral(device: DeviceHandle): SimulationPeripheral =
        peripherals[device] ?: bleError(ErrorKind.UNKNOWN_DEVICE, message = "unknown simulated device '${device.value}'")

    private fun requireConnected(device: DeviceHandle) {
        if (!isConnected(device)) bleError(ErrorKind.DISCONNECTED, message = "simulated device '${device.value}' is not connected")
    }

    private fun characteristic(device: DeviceHandle, ref: CharRef): SimulationCharacteristic {
        val normalized = ref.normalized()
        return peripheral(device).services.firstOrNull { it.uuid.canonicalUuid("service") == normalized.service }
            ?.characteristics?.firstOrNull { it.uuid.canonicalUuid("characteristic") == normalized.characteristic }
            ?: bleError(ErrorKind.CHARACTERISTIC_NOT_FOUND, message = "unknown simulated characteristic")
    }

    private fun deterministicJitter(peripheral: SimulationPeripheral, round: Long): Int {
        val magnitude = peripheral.advertisement.rssiJitter
        if (magnitude == 0) return 0
        val mixed = profile.seed xor peripheral.id.hashCode().toLong() xor round
        return ((mixed and Long.MAX_VALUE) % (magnitude * 2L + 1)).toInt() - magnitude
    }
}

private data class CharacteristicKey(val device: DeviceHandle, val ref: CharRef)
private data class ValueKey(val characteristic: CharacteristicKey, val source: String)

private fun CharRef.normalized(): CharRef = copy(
    service = service.canonicalUuid("service"),
    characteristic = characteristic.canonicalUuid("characteristic"),
)

private fun SimulationPeripheral.matches(filters: List<ScanFilter>): Boolean = filters.isEmpty() || filters.any { filter ->
    val requestedName = filter.name
    val requestedService = filter.service
    (requestedName == null || requestedName == advertisement.name) &&
        (requestedService == null || requestedService.canonicalUuid("scan filter service") in
            advertisement.serviceUuids.map { it.canonicalUuid("advertisement service") })
}

private fun Set<String>.toPropertyBits(): Int = fold(0) { bits, property ->
    bits or when (property.lowercase()) {
        "read" -> 0x02
        "writewithoutresponse" -> 0x04
        "write" -> 0x08
        "notify" -> 0x10
        "indicate" -> 0x20
        else -> error("profile validation should have rejected property '$property'")
    }
}

private fun SimulationValue.next(
    key: CharacteristicKey,
    source: String,
    positions: MutableMap<ValueKey, Long>,
    lock: SynchronizedObject,
): ByteArray = synchronized(lock) {
    val valueKey = ValueKey(key, source)
    val position = positions[valueKey] ?: 0
    positions[valueKey] = position + 1
    when {
        staticHex != null -> staticHex.decodeHex("static value")
        sequence.isNotEmpty() -> sequence[(position % sequence.size).toInt()].decodeHex("sequence value")
        counter != null -> counter.encode(position)
        else -> error("profile validation should have selected a value source")
    }
}

private fun SimulationCounter.encode(position: Long): ByteArray {
    var value = start + step * position
    return ByteArray(widthBytes) { index ->
        val shift = (widthBytes - index - 1) * 8
        (value shr shift).toByte()
    }
}
