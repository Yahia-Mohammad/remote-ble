package dev.warsha.remoteble.agent

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A declared, radio-less GATT world for the JVM agent's CI mode. This is deliberately separate
 * from the wire codec: profile evolution is versioned by [schemaVersion], not by protocol v1.
 */
@Serializable
data class SimulationProfile(
    val schemaVersion: Int,
    val seed: Long = 0,
    val peripherals: List<SimulationPeripheral>,
) {
    /** Decodes and validates the v1 profile before the agent opens a listener. */
    companion object {
        const val CURRENT_SCHEMA_VERSION: Int = 1
        const val MAX_PERIPHERALS: Int = 32
        const val MAX_SERVICES_PER_PERIPHERAL: Int = 32
        const val MAX_CHARACTERISTICS_PER_SERVICE: Int = 64
        const val MAX_VALUE_BYTES: Int = 512
        const val MIN_INTERVAL_MS: Long = 50
        private const val MAX_DELAY_MS: Long = 60_000L
        private val IDENTIFIER = Regex("[A-Za-z0-9._-]{1,128}")

        private val json = Json {
            ignoreUnknownKeys = false
            isLenient = false
        }

        fun decode(raw: String): SimulationProfile = try {
            json.decodeFromString<SimulationProfile>(raw).validated()
        } catch (error: IllegalArgumentException) {
            throw error
        } catch (error: Throwable) {
            throw IllegalArgumentException("invalid simulation profile: ${error.message}", error)
        }
    }

    fun validated(): SimulationProfile {
        require(schemaVersion == CURRENT_SCHEMA_VERSION) {
            "unsupported simulation schemaVersion $schemaVersion; expected $CURRENT_SCHEMA_VERSION"
        }
        require(peripherals.isNotEmpty()) { "simulation profile must contain at least one peripheral" }
        require(peripherals.size <= MAX_PERIPHERALS) { "simulation profile exceeds $MAX_PERIPHERALS peripherals" }
        require(peripherals.map { it.id }.toSet().size == peripherals.size) { "simulation peripheral ids must be unique" }

        peripherals.forEach { peripheral ->
            require(peripheral.id.matches(IDENTIFIER)) { "invalid simulation peripheral id '${peripheral.id}'" }
            require(peripheral.advertisement.name == null || peripheral.advertisement.name.length <= 128) {
                "advertisement name for '${peripheral.id}' exceeds 128 characters"
            }
            require(peripheral.advertisement.rssi in -127..20 && peripheral.advertisement.rssiJitter in 0..127) {
                "advertisement RSSI/jitter for '${peripheral.id}' is outside the supported range"
            }
            require(peripheral.advertisement.intervalMs in MIN_INTERVAL_MS..MAX_DELAY_MS) {
                "advertisement interval for '${peripheral.id}' must be ${MIN_INTERVAL_MS}..${MAX_DELAY_MS}ms"
            }
            val advertisedServices = peripheral.advertisement.serviceUuids.map { it.canonicalUuid("advertisement service") }
            require(advertisedServices.toSet().size == advertisedServices.size) {
                "peripheral '${peripheral.id}' repeats an advertisement service UUID"
            }
            require(peripheral.connect.latencyMs in 0..MAX_DELAY_MS) {
                "connect latency for '${peripheral.id}' must be 0..${MAX_DELAY_MS}ms"
            }
            require(peripheral.connect.failFirst >= 0) { "connect failFirst for '${peripheral.id}' cannot be negative" }
            peripheral.connect.dropAfterMs?.let { delay ->
                require(delay >= MIN_INTERVAL_MS && delay <= MAX_DELAY_MS) {
                    "dropAfterMs for '${peripheral.id}' must be ${MIN_INTERVAL_MS}..${MAX_DELAY_MS}ms"
                }
            }
            require(peripheral.services.isNotEmpty()) { "peripheral '${peripheral.id}' must expose a service" }
            require(peripheral.services.size <= MAX_SERVICES_PER_PERIPHERAL) {
                "peripheral '${peripheral.id}' exceeds $MAX_SERVICES_PER_PERIPHERAL services"
            }
            val services = peripheral.services.map { it.uuid.canonicalUuid("service") }
            require(services.toSet().size == services.size) { "peripheral '${peripheral.id}' repeats a service UUID" }
            peripheral.services.forEach { service ->
                require(service.characteristics.isNotEmpty()) {
                    "service '${service.uuid}' on '${peripheral.id}' must expose a characteristic"
                }
                require(service.characteristics.size <= MAX_CHARACTERISTICS_PER_SERVICE) {
                    "service '${service.uuid}' on '${peripheral.id}' exceeds $MAX_CHARACTERISTICS_PER_SERVICE characteristics"
                }
                val characteristics = service.characteristics.map { it.uuid.canonicalUuid("characteristic") }
                require(characteristics.toSet().size == characteristics.size) {
                    "service '${service.uuid}' on '${peripheral.id}' repeats a characteristic UUID"
                }
                service.characteristics.forEach { characteristic -> characteristic.validate(peripheral.id) }
            }
        }
        return this
    }

}

@Serializable
data class SimulationPeripheral(
    val id: String,
    val advertisement: SimulationAdvertisement,
    val connect: SimulationConnect = SimulationConnect(),
    val services: List<SimulationService>,
)

@Serializable
data class SimulationAdvertisement(
    val name: String? = null,
    val serviceUuids: List<String> = emptyList(),
    val rssi: Int = -50,
    val rssiJitter: Int = 0,
    val intervalMs: Long = 100,
)

@Serializable
data class SimulationConnect(
    val latencyMs: Long = 0,
    val failFirst: Int = 0,
    val dropAfterMs: Long? = null,
)

@Serializable
data class SimulationService(
    val uuid: String,
    val characteristics: List<SimulationCharacteristic>,
)

@Serializable
data class SimulationCharacteristic(
    val uuid: String,
    val properties: Set<String>,
    val read: SimulationValue? = null,
    val notify: SimulationNotify? = null,
    val write: SimulationWrite? = null,
) {
    internal fun validate(peripheralId: String) {
        val normalizedProperties = properties.map { it.lowercase() }.toSet()
        require(normalizedProperties.size == properties.size && normalizedProperties.all { it in SUPPORTED_PROPERTIES }) {
            "characteristic '$uuid' on '$peripheralId' has unsupported or duplicate properties"
        }
        require((read == null || "read" in normalizedProperties) && (read != null || "read" !in normalizedProperties)) {
            "characteristic '$uuid' on '$peripheralId' must pair read property with a read value"
        }
        require((notify == null || "notify" in normalizedProperties || "indicate" in normalizedProperties) &&
            (notify != null || ("notify" !in normalizedProperties && "indicate" !in normalizedProperties))) {
            "characteristic '$uuid' on '$peripheralId' must pair notify/indicate property with notify behavior"
        }
        require((write == null || "write" in normalizedProperties || "writeWithoutResponse" in normalizedProperties) &&
            (write != null || ("write" !in normalizedProperties && "writeWithoutResponse" !in normalizedProperties))) {
            "characteristic '$uuid' on '$peripheralId' must pair write property with write behavior"
        }
        read?.validate("read value for '$uuid'")
        notify?.let {
            require(it.intervalMs in SimulationProfile.MIN_INTERVAL_MS..MAX_DELAY_MS) {
                "notify interval for '$uuid' must be ${SimulationProfile.MIN_INTERVAL_MS}..${MAX_DELAY_MS}ms"
            }
            it.values.validate("notify values for '$uuid'")
        }
    }

    internal companion object {
        val SUPPORTED_PROPERTIES = setOf("read", "write", "writewithoutresponse", "notify", "indicate")
        const val MAX_DELAY_MS = 60_000L
    }
}

@Serializable
data class SimulationNotify(
    val intervalMs: Long,
    val values: SimulationValue,
)

@Serializable
data class SimulationWrite(
    val accept: Boolean = true,
    val storesValue: Boolean = false,
)

/** Exactly one source is selected: fixed hex bytes, a looping sequence, or an integer counter. */
@Serializable
data class SimulationValue(
    @SerialName("static") val staticHex: String? = null,
    val sequence: List<String> = emptyList(),
    val counter: SimulationCounter? = null,
) {
    internal fun validate(label: String) {
        val sources = (if (staticHex != null) 1 else 0) + (if (sequence.isNotEmpty()) 1 else 0) + (if (counter != null) 1 else 0)
        require(sources == 1) { "$label must declare exactly one of static, sequence, or counter" }
        staticHex?.decodeHex(label)
        sequence.forEach { it.decodeHex(label) }
        counter?.let {
            require(it.widthBytes in 1..4) { "$label counter widthBytes must be 1..4" }
        }
    }
}

@Serializable
data class SimulationCounter(
    val start: Long = 0,
    val step: Long = 1,
    val widthBytes: Int = 1,
)

internal fun String.canonicalUuid(label: String): String {
    val raw = trim().lowercase()
    val expanded = when {
        raw.matches(Regex("[0-9a-f]{4}")) -> "0000$raw-0000-1000-8000-00805f9b34fb"
        raw.matches(Regex("[0-9a-f]{8}")) -> "$raw-0000-1000-8000-00805f9b34fb"
        raw.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) -> raw
        else -> throw IllegalArgumentException("invalid $label UUID '$this'")
    }
    return expanded
}

internal fun String.decodeHex(label: String): ByteArray {
    require(length % 2 == 0 && length <= SimulationProfile.MAX_VALUE_BYTES * 2 && all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
        "$label must be even-length hexadecimal up to ${SimulationProfile.MAX_VALUE_BYTES} bytes"
    }
    return ByteArray(length / 2) { index -> substring(index * 2, index * 2 + 2).toInt(16).toByte() }
}
