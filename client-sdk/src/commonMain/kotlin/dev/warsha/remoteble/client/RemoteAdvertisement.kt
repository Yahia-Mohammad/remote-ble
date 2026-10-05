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
 *
 * [serviceData], [txPower], [isConnectable] and a [peripheralName] of its own arrive only from an
 * agent that negotiated `scan.fields`, and then only where its platform reports them; otherwise they
 * read as absent, and [peripheralName] as the advertised [name].
 */
@OptIn(ExperimentalUuidApi::class)
public class RemoteAdvertisement internal constructor(
    internal val dto: AdvertisementDto,
) : Advertisement {

    /** The agent-minted handle for [RemotePeripheral] / a remote factory. */
    public val handle: DeviceHandle get() = dto.device

    override val name: String? get() = dto.name
    override val peripheralName: String? get() = dto.peripheralName ?: dto.name
    // Use the agent handle directly (see deviceHandleToIdentifier): Kable's Android
    // toIdentifier() would reject the agent's UUID handle as a malformed MAC.
    override val identifier: Identifier by lazy { deviceHandleToIdentifier(dto.device.value) }
    override val isConnectable: Boolean? get() = dto.isConnectable
    override val rssi: Int get() = dto.rssi
    override val txPower: Int? get() = dto.txPower
    override val uuids: List<Uuid> = dto.serviceUuids.map(::parseBleUuid)

    // Keyed by parsed UUID, so a lookup matches however the agent spelled it (case, short form).
    private val serviceDataByUuid: Map<Uuid, ByteArray> by lazy {
        dto.serviceData.entries.mapNotNull { (key, value) -> runCatching { parseBleUuid(key) }.getOrNull()?.let { it to value } }.toMap()
    }

    override fun serviceData(uuid: Uuid): ByteArray? = serviceDataByUuid[uuid]

    override fun manufacturerData(companyIdentifierCode: Int): ByteArray? =
        dto.manufacturerData[companyIdentifierCode]

    // Kable's single-company view: the first entry, in the order the agent sent them. Every entry
    // stays available through manufacturerData(code) above.
    override val manufacturerData: ManufacturerData?
        get() = dto.manufacturerData.entries.firstOrNull()?.let { (code, data) -> ManufacturerData(code, data) }
}
