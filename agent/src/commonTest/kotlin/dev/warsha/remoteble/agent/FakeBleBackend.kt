package dev.warsha.remoteble.agent

import dev.warsha.remoteble.protocol.AdvertisementDto
import dev.warsha.remoteble.protocol.BleBondState
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
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow

/** Deterministic [BleBackend] for testing [BleAgent] routing without a radio. */
class FakeBleBackend(
    var services: List<ServiceNode> = DEFAULT_SERVICES,
    var readValue: ByteArray = byteArrayOf(0x42, 0x07),
    var advertisements: List<AdvertisementDto> = DEFAULT_ADVERTISEMENTS,
    var notifications: List<ByteArray> = DEFAULT_NOTIFICATIONS,
    var failConnectFor: Set<String> = emptySet(),
    var characteristicNotFound: Boolean = false,
    var descriptorValue: ByteArray = byteArrayOf(0x01, 0x00),
    // When set, overrides checkLiveness's result regardless of isConnected — lets tests
    // simulate a link the cached state still calls "connected" but an active probe would not.
    var livenessOverride: Boolean? = null,
    // The fake implements every optional capability; tests can override to simulate a
    // narrower backend.
    override val capabilities: Set<String> =
        setOf(Capabilities.DESCRIPTORS, Capabilities.PAIRING, Capabilities.CONN_PRIORITY, Capabilities.CONN_PARAMS),
    private val emitInterval: Duration = 10.milliseconds,
) : BleBackend {

    // Lets tests drive the native unsolicited-drop stream (see BleBackend.connectionDrops).
    val connectionDropSignals = MutableSharedFlow<ConnectionDrop>(extraBufferCapacity = 8)

    val connectCalls = mutableListOf<DeviceHandle>()
    val disconnectCalls = mutableListOf<DeviceHandle>()
    val pairCalls = mutableListOf<DeviceHandle>()
    val unpairCalls = mutableListOf<DeviceHandle>()
    var lastWrite: Triple<CharRef, ByteArray, Boolean>? = null
    var lastDescriptorWrite: Pair<DescRef, ByteArray>? = null
    var lastConnectionPriority: ConnPriority? = null
    var lastConnParams: Pair<ConnProfile, ConnParamHint?>? = null

    override fun connectionDrops(): Flow<ConnectionDrop> = connectionDropSignals

    override fun scan(filters: List<ScanFilter>): Flow<AdvertisementDto> = flow {
        var i = 0
        while (true) {
            emit(advertisements[i % advertisements.size])
            i++
            kotlinx.coroutines.delay(emitInterval)
        }
    }

    override suspend fun connect(device: DeviceHandle) {
        if (device.value in failConnectFor) bleError(ErrorKind.CONNECTION_FAILED, message = "fake connect failure")
        connectCalls += device
    }

    override suspend fun disconnect(device: DeviceHandle) {
        disconnectCalls += device
    }

    override fun isConnected(device: DeviceHandle): Boolean =
        (device in connectCalls) && (device !in disconnectCalls)

    override suspend fun checkLiveness(device: DeviceHandle): Boolean =
        livenessOverride ?: isConnected(device)

    override suspend fun discover(device: DeviceHandle): List<ServiceNode> = services

    override suspend fun read(device: DeviceHandle, char: CharRef): ByteArray {
        if (characteristicNotFound) bleError(ErrorKind.CHARACTERISTIC_NOT_FOUND)
        return readValue
    }

    override suspend fun write(device: DeviceHandle, char: CharRef, value: ByteArray, withResponse: Boolean) {
        lastWrite = Triple(char, value, withResponse)
    }

    override fun observe(device: DeviceHandle, char: CharRef): Flow<ByteArray> = flow {
        var i = 0
        while (true) {
            emit(notifications[i % notifications.size])
            i++
            kotlinx.coroutines.delay(emitInterval)
        }
    }

    override suspend fun requestMtu(device: DeviceHandle, mtu: Int): Int = mtu

    override suspend fun readDescriptor(device: DeviceHandle, desc: DescRef): ByteArray {
        if (characteristicNotFound) bleError(ErrorKind.CHARACTERISTIC_NOT_FOUND)
        return descriptorValue
    }

    override suspend fun writeDescriptor(device: DeviceHandle, desc: DescRef, value: ByteArray) {
        lastDescriptorWrite = desc to value
    }

    override suspend fun pair(device: DeviceHandle): BleBondState {
        pairCalls += device
        return BleBondState.BONDED
    }

    override suspend fun unpair(device: DeviceHandle) {
        unpairCalls += device
    }

    override suspend fun requestConnectionPriority(device: DeviceHandle, priority: ConnPriority) {
        lastConnectionPriority = priority
    }

    override suspend fun setConnParams(device: DeviceHandle, profile: ConnProfile, hint: ConnParamHint?) {
        lastConnParams = profile to hint
    }

    companion object {
        val DEFAULT_SERVICES: List<ServiceNode> = listOf(
            ServiceNode(
                uuid = "0000180d-0000-1000-8000-00805f9b34fb",
                characteristics = listOf(
                    CharNode(uuid = "00002a37-0000-1000-8000-00805f9b34fb", properties = 0x10),
                ),
            ),
        )
        val DEFAULT_ADVERTISEMENTS: List<AdvertisementDto> = listOf(
            AdvertisementDto(device = DeviceHandle("FA:KE:0A"), name = "Fake A", rssi = -55),
            AdvertisementDto(device = DeviceHandle("FA:KE:0B"), name = "Fake B", rssi = -70),
        )
        val DEFAULT_NOTIFICATIONS: List<ByteArray> = listOf(
            byteArrayOf(0x00, 0x48),
            byteArrayOf(0x00, 0x49),
        )
    }
}

/**
 * A [BleBackend] that implements none of the optional capabilities (descriptors,
 * pairing), leaving the interface defaults in place — used to verify the agent answers
 * `UNSUPPORTED` rather than crashing when a backend can't service a capability it never
 * advertised.
 */
class MinimalBackend : BleBackend {
    override fun scan(filters: List<ScanFilter>): Flow<AdvertisementDto> = emptyFlow()
    override suspend fun connect(device: DeviceHandle) {}
    override suspend fun disconnect(device: DeviceHandle) {}
    override suspend fun discover(device: DeviceHandle): List<ServiceNode> = emptyList()
    override suspend fun read(device: DeviceHandle, char: CharRef): ByteArray = byteArrayOf()
    override suspend fun write(device: DeviceHandle, char: CharRef, value: ByteArray, withResponse: Boolean) {}
    override fun observe(device: DeviceHandle, char: CharRef): Flow<ByteArray> = emptyFlow()
    override suspend fun requestMtu(device: DeviceHandle, mtu: Int): Int = mtu
    // readDescriptor / writeDescriptor intentionally left as the BleBackend defaults.
}
