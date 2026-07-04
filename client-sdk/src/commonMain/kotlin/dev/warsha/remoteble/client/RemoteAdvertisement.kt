package dev.warsha.remoteble.client

import dev.warsha.remoteble.protocol.AdvertisementDto
import dev.warsha.remoteble.protocol.DeviceHandle
import com.juul.kable.Advertisement
import com.juul.kable.Identifier
import com.juul.kable.ManufacturerData
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Adapts a remote [AdvertisementDto] to Kable's [Advertisement]. Carries the
 * agent-scoped [handle] so it can be fed into [RemotePeripheral].
 */
@OptIn(ExperimentalUuidApi::class)
public class RemoteAdvertisement internal constructor(
    internal val dto: AdvertisementDto,
) : Advertisement {

    /** The agent-minted handle for [RemotePeripheral] / a remote factory. */
    public val handle: DeviceHandle get() = dto.device

    override val name: String? get() = dto.name
    override val peripheralName: String? get() = dto.name
    // Use the agent handle directly (see deviceHandleToIdentifier): Kable's Android
    // toIdentifier() would reject the agent's UUID handle as a malformed MAC.
    override val identifier: Identifier by lazy { deviceHandleToIdentifier(dto.device.value) }
    override val isConnectable: Boolean? get() = null
    override val rssi: Int get() = dto.rssi
    override val txPower: Int? get() = null
    override val uuids: List<Uuid> = dto.serviceUuids.map(::parseBleUuid)

    override fun serviceData(uuid: Uuid): ByteArray? = null

    override fun manufacturerData(companyIdentifierCode: Int): ByteArray? =
        dto.manufacturerData[companyIdentifierCode]

    // The aggregate single-company view isn't modeled on the wire; per-code lookup
    // is available via manufacturerData(code) above.
    override val manufacturerData: ManufacturerData? get() = null
}
