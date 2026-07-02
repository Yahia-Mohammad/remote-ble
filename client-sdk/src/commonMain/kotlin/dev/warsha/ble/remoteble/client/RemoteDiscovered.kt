package dev.warsha.ble.remoteble.client

import dev.warsha.ble.remoteble.protocol.ServiceNode
import com.juul.kable.Characteristic
import com.juul.kable.DiscoveredCharacteristic
import com.juul.kable.DiscoveredDescriptor
import com.juul.kable.DiscoveredService
import com.juul.kable.ExperimentalApi
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Kable `Discovered*` tree built from the agent's [ServiceNode] discovery result, so
 * app code can navigate `peripheral.services` exactly as it would for a local peripheral.
 */
@OptIn(ExperimentalUuidApi::class)
internal class RemoteDiscoveredDescriptor(
    override val serviceUuid: Uuid,
    override val characteristicUuid: Uuid,
    override val descriptorUuid: Uuid,
) : DiscoveredDescriptor

@OptIn(ExperimentalUuidApi::class, ExperimentalApi::class)
internal class RemoteDiscoveredCharacteristic(
    override val serviceUuid: Uuid,
    override val characteristicUuid: Uuid,
    override val descriptors: List<DiscoveredDescriptor>,
    override val properties: Characteristic.Properties,
) : DiscoveredCharacteristic

@OptIn(ExperimentalUuidApi::class)
internal class RemoteDiscoveredService(
    override val serviceUuid: Uuid,
    override val characteristics: List<DiscoveredCharacteristic>,
) : DiscoveredService

@OptIn(ExperimentalUuidApi::class, ExperimentalApi::class)
internal fun ServiceNode.toDiscoveredService(): DiscoveredService {
    val serviceUuid = parseBleUuid(uuid)
    return RemoteDiscoveredService(
        serviceUuid = serviceUuid,
        characteristics = characteristics.map { charNode ->
            val charUuid = parseBleUuid(charNode.uuid)
            RemoteDiscoveredCharacteristic(
                serviceUuid = serviceUuid,
                characteristicUuid = charUuid,
                descriptors = charNode.descriptors.map { descriptorUuid ->
                    RemoteDiscoveredDescriptor(serviceUuid, charUuid, parseBleUuid(descriptorUuid))
                },
                properties = Characteristic.Properties(charNode.properties),
            )
        },
    )
}
