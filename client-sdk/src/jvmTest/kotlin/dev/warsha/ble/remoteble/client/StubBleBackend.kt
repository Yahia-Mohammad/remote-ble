package dev.warsha.ble.remoteble.client

import dev.warsha.ble.remoteble.agent.BleBackend
import dev.warsha.ble.remoteble.protocol.AdvertisementDto
import dev.warsha.ble.remoteble.protocol.AgentError
import dev.warsha.ble.remoteble.protocol.AgentException
import dev.warsha.ble.remoteble.protocol.CharNode
import dev.warsha.ble.remoteble.protocol.CharRef
import dev.warsha.ble.remoteble.protocol.DeviceHandle
import dev.warsha.ble.remoteble.protocol.ErrorKind
import dev.warsha.ble.remoteble.protocol.ScanFilter
import dev.warsha.ble.remoteble.protocol.ServiceNode
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * A radio-free [BleBackend] for driving the production agent path in tests.
 *
 * [failWrites]/[failReads] inject "radio said no" failures (the agent maps the thrown
 * [AgentException] to an `OpResult.Err`) so error-path coverage can assert the client
 * surfaces the right [ErrorKind] and stays usable afterwards.
 */
class StubBleBackend(
    private val failWrites: Boolean = false,
    private val failReads: Boolean = false,
) : BleBackend {
    // A valid UUID, as a real macOS agent would mint from a CBPeripheral identifier
    // (Kable's JVM `Identifier` parses this).
    companion object {
        const val DEVICE: String = "f47ac10b-58cc-4372-a567-0e02b2c3d479"
    }

    override fun scan(filters: List<ScanFilter>): Flow<AdvertisementDto> = flow {
        while (true) {
            emit(AdvertisementDto(device = DeviceHandle(DEVICE), name = "Stub", rssi = -50))
            delay(20.milliseconds)
        }
    }

    override suspend fun connect(device: DeviceHandle) {}
    override suspend fun disconnect(device: DeviceHandle) {}
    override suspend fun discover(device: DeviceHandle): List<ServiceNode> = listOf(
        ServiceNode(
            uuid = "0000180d-0000-1000-8000-00805f9b34fb",
            characteristics = listOf(
                CharNode(uuid = "00002a37-0000-1000-8000-00805f9b34fb", properties = 0x10),
            ),
        ),
    )

    override suspend fun read(device: DeviceHandle, char: CharRef): ByteArray {
        if (failReads) throw AgentException(AgentError(ErrorKind.READ_FAILED, message = "stub read failure"))
        return byteArrayOf(0x11, 0x22)
    }

    override suspend fun write(device: DeviceHandle, char: CharRef, value: ByteArray, withResponse: Boolean) {
        if (failWrites) throw AgentException(AgentError(ErrorKind.WRITE_FAILED, message = "stub write failure"))
    }
    override fun observe(device: DeviceHandle, char: CharRef): Flow<ByteArray> = flow {
        while (true) {
            emit(byteArrayOf(0x01))
            delay(20.milliseconds)
        }
    }

    override suspend fun requestMtu(device: DeviceHandle, mtu: Int): Int = mtu
}
