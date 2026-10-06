package dev.warsha.remoteble.agent

import com.juul.kable.Advertisement
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * An advertisement's service data and manufacturer data, keyed as [dev.warsha.remoteble.protocol.AdvertisementDto]
 * carries them: service data by full 128-bit UUID, manufacturer data by company identifier.
 */
internal class AdvertisementPayload(
    val serviceData: Map<String, ByteArray>,
    val manufacturerData: Map<Int, ByteArray>,
)

/**
 * Everything the host lets the agent read of [advertisement]'s payload. Kable's common API can only
 * look service data up by UUID and returns one manufacturer entry, so a platform that exposes the raw
 * advertising record (Android) parses that instead and misses nothing.
 */
internal expect fun advertisementPayload(advertisement: Advertisement): AdvertisementPayload

/**
 * The payload through Kable's common lookups: service data for the advertised service UUIDs only,
 * since there is no way to ask what else is present, and the first manufacturer entry.
 */
@OptIn(ExperimentalUuidApi::class)
internal fun lookedUpPayload(advertisement: Advertisement): AdvertisementPayload = AdvertisementPayload(
    serviceData = advertisement.uuids.mapNotNull { uuid -> advertisement.serviceData(uuid)?.let { uuid.toString() to it } }.toMap(),
    manufacturerData = advertisement.manufacturerData?.let { mapOf(it.code to it.data) }.orEmpty(),
)

/**
 * Service and manufacturer data from a raw advertising record (AD structures: length, type, data),
 * the advertisement and scan response together as Android reports them. A malformed structure ends
 * the parse; what was read before it is kept. Null when the record holds neither.
 */
@OptIn(ExperimentalUuidApi::class)
internal fun parseAdvertisingRecord(record: ByteArray): AdvertisementPayload? {
    val serviceData = LinkedHashMap<String, ByteArray>()
    val manufacturerData = LinkedHashMap<Int, ByteArray>()
    var index = 0
    while (index < record.size) {
        val length = record[index].toInt() and 0xFF
        // A zero length is the padding Android leaves after the last structure; a length running past
        // the end is a truncated structure.
        if (length == 0 || index + 1 + length > record.size) break
        val type = record[index + 1].toInt() and 0xFF
        val data = record.copyOfRange(index + 2, index + 1 + length)
        when (type) {
            SERVICE_DATA_16 -> if (data.size >= 2) serviceData[baseUuid(littleEndian(data, 2))] = data.copyOfRange(2, data.size)
            SERVICE_DATA_32 -> if (data.size >= 4) serviceData[baseUuid(littleEndian(data, 4))] = data.copyOfRange(4, data.size)
            SERVICE_DATA_128 -> if (data.size >= 16) {
                serviceData[Uuid.fromByteArray(data.copyOfRange(0, 16).reversedArray()).toString()] = data.copyOfRange(16, data.size)
            }
            MANUFACTURER_DATA -> if (data.size >= 2) manufacturerData[littleEndian(data, 2).toInt()] = data.copyOfRange(2, data.size)
        }
        index += 1 + length
    }
    return if (serviceData.isEmpty() && manufacturerData.isEmpty()) null else AdvertisementPayload(serviceData, manufacturerData)
}

/** [bytes] little-endian octets of [data], from its start. */
private fun littleEndian(data: ByteArray, bytes: Int): Long =
    (0 until bytes).fold(0L) { value, i -> value or ((data[i].toLong() and 0xFF) shl (8 * i)) }

/** A 16- or 32-bit SIG UUID in full, on the Bluetooth base UUID. */
private fun baseUuid(short: Long): String = short.toString(16).padStart(8, '0') + "-0000-1000-8000-00805f9b34fb"

private const val SERVICE_DATA_16 = 0x16
private const val SERVICE_DATA_32 = 0x20
private const val SERVICE_DATA_128 = 0x21
private const val MANUFACTURER_DATA = 0xFF
