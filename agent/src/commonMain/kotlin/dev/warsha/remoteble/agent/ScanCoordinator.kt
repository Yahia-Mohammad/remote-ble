package dev.warsha.remoteble.agent

import dev.warsha.remoteble.log.Logger
import dev.warsha.remoteble.protocol.AdvertisementDto
import dev.warsha.remoteble.protocol.ScanFilter
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Stable identity of a logical scan. Connection generation is deliberately not part of this key. */
data class LogicalScanKey(val clientKey: String, val scanId: Long)

/** Fencing token for delayed stop and grace-expiry actions. */
data class ScanRegistration(val key: LogicalScanKey, val connectionGeneration: Long)

sealed interface ScanAdmission {
    data class Accepted(val registration: ScanRegistration, val advertisements: Flow<AdvertisementDto>) : ScanAdmission
    data object SingleOccupied : ScanAdmission
}

/**
 * Agent-lifetime coordinator for the two guaranteed scan modes. It intentionally always runs an
 * unfiltered physical scan in multiplexed mode: service-union narrowing is an optimisation, not a
 * correctness requirement, and an unfiltered plan safely covers every logical predicate.
 */
class ScanCoordinator(
    private val backend: BleBackend,
    private val scope: CoroutineScope,
    val mode: ScanConcurrencyMode,
    private val transportGrace: Duration,
    private val maxActiveScans: Int = BleAgent.MAX_ACTIVE_SCANS,
    private val replayWindow: Duration = 30.seconds,
    private val replayCap: Int = 256,
    private val mailboxCapacity: Int = 64,
) {
    private data class CachedAdvertisement(val advertisement: AdvertisementDto, val observed: TimeMark)

    private data class LogicalScan(
        var registration: ScanRegistration,
        var filters: List<ScanFilter>,
        var mailbox: Channel<AdvertisementDto>?,
        var grace: Job? = null,
    )

    private val lock = Mutex()
    private val scans = linkedMapOf<LogicalScanKey, LogicalScan>()
    private val cache = linkedMapOf<String, CachedAdvertisement>()
    private var physicalScan: Job? = null
    private var physicalPlan: List<ScanFilter>? = null
    private var physicalPlanIsUnfiltered = false
    private var physicalGeneration: Long = 0

    suspend fun startOrReplace(
        key: LogicalScanKey,
        generation: Long,
        filters: List<ScanFilter>,
    ): ScanAdmission = lock.withLock {
        check(mode != ScanConcurrencyMode.UNCONTROLLED) { "uncontrolled scans do not use ScanCoordinator" }
        val current = scans[key]
        if (current == null) {
            if (mode == ScanConcurrencyMode.SINGLE && scans.isNotEmpty()) return@withLock ScanAdmission.SingleOccupied
            if (scans.keys.count { it.clientKey == key.clientKey } >= maxActiveScans) {
                throw ScanLimitExceeded(maxActiveScans)
            }
        }

        val registration = ScanRegistration(key, generation)
        val mailbox = newMailbox()
        val logical = current ?: LogicalScan(registration, filters, mailbox)
        if (current != null) {
            logical.grace?.cancel()
            logical.grace = null
            logical.mailbox?.close()
            logical.registration = registration
            logical.filters = filters
            logical.mailbox = mailbox
        }
        scans[key] = logical

        val candidatePlan = widenedPhysicalPlanLocked()
        if (physicalPlan != candidatePlan || physicalScan?.isActive != true) {
            replacePhysicalScanLocked(candidatePlan)
        }
        evictExpiredLocked()
        cache.values.map { it.advertisement }
            .filter { scanMatches(filters, it) }
            .forEach { mailbox.trySend(it) }
        ScanAdmission.Accepted(registration, mailbox.receiveAsFlow())
    }

    suspend fun stop(registration: ScanRegistration) = lock.withLock {
        val logical = scans[registration.key] ?: return@withLock
        if (logical.registration != registration) return@withLock
        removeLocked(registration.key, logical)
    }

    /** Detaches one retired socket while retaining its logical scans through transport grace. */
    fun detachConnectionAsync(generation: Long) {
        scope.launch { detachConnection(generation) }
    }

    suspend fun detachConnection(generation: Long) = lock.withLock {
        scans.values.filter { it.registration.connectionGeneration == generation }.forEach { logical ->
            logical.mailbox?.close()
            logical.mailbox = null
            logical.grace?.cancel()
            val registration = logical.registration
            logical.grace = scope.launch {
                delay(transportGrace)
                expire(registration)
            }
        }
    }

    private suspend fun expire(registration: ScanRegistration) = lock.withLock {
        val logical = scans[registration.key] ?: return@withLock
        if (logical.registration == registration) removeLocked(registration.key, logical)
    }

    private fun removeLocked(key: LogicalScanKey, logical: LogicalScan) {
        logical.grace?.cancel()
        logical.mailbox?.close()
        scans.remove(key)
        if (scans.isEmpty()) {
            physicalScan?.cancel()
            physicalScan = null
            physicalPlan = null
            physicalPlanIsUnfiltered = false
            cache.clear()
        }
    }

    private fun newMailbox(): Channel<AdvertisementDto> =
        Channel(mailboxCapacity, onBufferOverflow = BufferOverflow.DROP_LATEST)

    /**
     * Native service filtering is only a prefilter.  The logical matcher remains authoritative,
     * and a broad predicate makes an unfiltered physical scan mandatory.  Once widened, a plan is
     * deliberately never narrowed before the last logical scan stops.
     */
    private fun widenedPhysicalPlanLocked(): List<ScanFilter> {
        if (physicalPlan != null && physicalPlanIsUnfiltered) return emptyList()
        val allServiceCoverable = scans.values.all { logical ->
            logical.filters.isNotEmpty() && logical.filters.all { it.service != null }
        }
        if (!allServiceCoverable) {
            physicalPlanIsUnfiltered = true
            return emptyList()
        }
        val requestedServices = scans.values.flatMap { logical -> logical.filters.mapNotNull { it.service } }
            .map(::canonicalServiceUuid).distinct()
        val previous = physicalPlan
        if (previous == null) return requestedServices.map { ScanFilter(service = it) }
        return (previous.mapNotNull { it.service } + requestedServices)
            .map(::canonicalServiceUuid)
            .distinct()
            .map { ScanFilter(service = it) }
    }

    private fun replacePhysicalScanLocked(plan: List<ScanFilter>) {
        physicalScan?.cancel()
        physicalPlan = plan
        val generation = ++physicalGeneration
        physicalScan = scope.launch {
            try {
                backend.scan(plan)
                    .catch { error -> Logger.warn(LogTags.ENGINE) { "multiplexed physical scan ended: ${error.message}" } }
                    .collect { advertisement -> fanOut(generation, advertisement) }
            } finally {
                lock.withLock {
                    if (physicalGeneration == generation) physicalScan = null
                }
            }
        }
    }

    private suspend fun fanOut(generation: Long, raw: AdvertisementDto) = lock.withLock {
        if (generation != physicalGeneration) return@withLock
        val merged = mergeIdentityLocked(raw)
        cache.remove(merged.device.value)
        cache[merged.device.value] = CachedAdvertisement(merged, TimeSource.Monotonic.markNow())
        evictExpiredLocked()
        while (cache.size > replayCap) cache.remove(cache.keys.first())
        scans.values.forEach { logical ->
            logical.mailbox?.takeIf { scanMatches(logical.filters, merged) }?.trySend(merged)
        }
    }

    private fun mergeIdentityLocked(advertisement: AdvertisementDto): AdvertisementDto {
        val previous = cache[advertisement.device.value]?.advertisement
        return AdvertisementDto(
            device = advertisement.device,
            name = advertisement.name ?: previous?.name,
            rssi = advertisement.rssi,
            serviceUuids = advertisement.serviceUuids.ifEmpty { previous?.serviceUuids.orEmpty() },
            manufacturerData = advertisement.manufacturerData,
        )
    }

    private fun evictExpiredLocked() {
        val expired = cache.filterValues { it.observed.elapsedNow() > replayWindow }.keys
        expired.forEach(cache::remove)
    }

}

internal fun scanMatches(filters: List<ScanFilter>, advertisement: AdvertisementDto): Boolean =
    filters.isEmpty() || filters.any { filter ->
        val service = filter.service
        (filter.name == null || filter.name == advertisement.name) &&
            (service == null || advertisement.serviceUuids.any { sameServiceUuid(it, service) })
    }

private fun sameServiceUuid(left: String, right: String): Boolean =
    canonicalServiceUuid(left) == canonicalServiceUuid(right)

private fun canonicalServiceUuid(value: String): String {
    val raw = value.lowercase()
    return when {
        raw.matches(Regex("[0-9a-f]{4}")) -> "0000$raw-0000-1000-8000-00805f9b34fb"
        raw.matches(Regex("[0-9a-f]{8}")) -> "$raw-0000-1000-8000-00805f9b34fb"
        else -> raw
    }
}

class ScanLimitExceeded(max: Int) : IllegalStateException("at most $max active scans are allowed")
