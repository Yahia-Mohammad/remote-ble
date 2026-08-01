package dev.warsha.remoteble.agent

import dev.warsha.remoteble.log.Logger
import dev.warsha.remoteble.protocol.AdvertisementDto
import dev.warsha.remoteble.protocol.ScanFilter
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Stable identity of a logical scan. Connection generation is deliberately not part of this key. */
data class LogicalScanKey(val clientKey: String, val scanId: Long)

/** Published reference limits for retained scan replay. */
internal const val SCAN_REPLAY_CACHE_CAP: Int = 256
internal val SCAN_REPLAY_WINDOW: Duration = 30.seconds

/**
 * Upper bound on waiting for a physical collector to unwind. Not a tuning knob: it is the ceiling
 * on how long one uncooperative backend teardown may hold the agent-wide coordinator lock.
 */
internal val PHYSICAL_SCAN_TEARDOWN_TIMEOUT: Duration = 10.seconds

private fun monotonicScanClock(): () -> Duration {
    val origin = TimeSource.Monotonic.markNow()
    return { origin.elapsedNow() }
}

/**
 * Fencing token for delayed stop, grace-expiry, and same-connection replacement actions.
 *
 * [connectionGeneration] distinguishes a reconnect; [revision] distinguishes two starts of the
 * same logical key that race on one live connection.
 */
data class ScanRegistration(
    val key: LogicalScanKey,
    val connectionGeneration: Long,
    val revision: Long,
)

sealed interface ScanAdmission {
    data class Accepted(val registration: ScanRegistration, val advertisements: Flow<AdvertisementDto>) : ScanAdmission
    data object SingleOccupied : ScanAdmission
}

/**
 * Agent-lifetime coordinator for the two guaranteed scan modes. It uses the widest safe physical
 * plan (a service union where possible, otherwise unfiltered); logical matching is authoritative.
 */
class ScanCoordinator(
    private val backend: BleBackend,
    private val scope: CoroutineScope,
    val mode: ScanConcurrencyMode,
    private val transportGrace: Duration,
    private val maxActiveScans: Int = BleAgent.MAX_ACTIVE_SCANS,
    private val replayWindow: Duration = SCAN_REPLAY_WINDOW,
    private val replayCap: Int = SCAN_REPLAY_CACHE_CAP,
    private val mailboxCapacity: Int = ScanOutboundArbiter.DEFAULT_MAILBOX_CAPACITY,
    private val clock: () -> Duration = monotonicScanClock(),
) {
    private data class CachedAdvertisement(val advertisement: AdvertisementDto, val observed: Duration)

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
    private var registrationRevision: Long = 0

    /**
     * A logical scan's **single** bounded reservation: full replay plus steady-state headroom.
     * Everything downstream of it suspends rather than reserving a second copy, so this is the
     * one place scan delivery is bounded and the one place it drops.
     */
    internal val deliveryMailboxCapacity: Int get() = mailboxCapacity + replayCap

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

        val registration = ScanRegistration(key, generation, ++registrationRevision)
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

    private suspend fun removeLocked(key: LogicalScanKey, logical: LogicalScan) {
        logical.grace?.cancel()
        logical.mailbox?.close()
        scans.remove(key)
        if (scans.isEmpty()) {
            // Bounded for the same reason as replacePhysicalScanLocked: this join holds the
            // agent-wide lock, so a backend that is slow to unwind must not block every other
            // client's scan admission. A straggler is fenced by physicalGeneration.
            physicalScan?.let { previous ->
                if (withTimeoutOrNull(PHYSICAL_SCAN_TEARDOWN_TIMEOUT) { previous.cancelAndJoin() } == null) {
                    Logger.warn(LogTags.ENGINE) {
                        "physical scan did not stop within $PHYSICAL_SCAN_TEARDOWN_TIMEOUT on teardown"
                    }
                }
            }
            physicalScan = null
            physicalPlan = null
            physicalPlanIsUnfiltered = false
            cache.clear()
        }
    }

    // Sized for a full replay burst (up to replayCap entries, delivered synchronously at
    // admission before the consumer has necessarily started collecting) plus steady-state
    // headroom, so the spec's "every retained matching entry" MUST isn't silently truncated by
    // DROP_LATEST at admission time.
    //
    // DROP_LATEST rather than SUSPEND because this is where the physical fan-out writes: one
    // stalled connection must never be able to slow the radio for every other client. It is also
    // the only hop that drops — the collector downstream suspends, so overload backpressures into
    // this reservation and is shed here, once.
    private fun newMailbox(): Channel<AdvertisementDto> =
        Channel(deliveryMailboxCapacity, onBufferOverflow = BufferOverflow.DROP_LATEST)

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

    private suspend fun replacePhysicalScanLocked(plan: List<ScanFilter>) {
        // Kable's Scanner owns a process-wide CoreBluetooth scan on Apple. Cancellation must have
        // completed before the replacement starts: merely requesting cancellation lets an older
        // scanner call stopScan after the newer scanner has started.
        //
        // Bounded, because this join runs inside NonCancellable *and* inside the agent-wide
        // coordinator lock: teardown latency belongs to Kable and, on Apple, to a process-wide
        // CentralManager.stopScan(). An unbounded wait here would not fail one scan, it would
        // wedge scan admission for every client and stall connection teardown (and with it lease
        // release). Same reasoning as EngineBleBackend's GATT_OP_TIMEOUT. If the old collector
        // does outlive its deadline, physicalGeneration already fences whatever it delivers late,
        // so proceeding is safe — the ordering guarantee is best-effort, not the correctness one.
        withContext(NonCancellable) {
            physicalScan?.let { previous ->
                if (withTimeoutOrNull(PHYSICAL_SCAN_TEARDOWN_TIMEOUT) { previous.cancelAndJoin() } == null) {
                    Logger.warn(LogTags.ENGINE) {
                        "physical scan did not stop within $PHYSICAL_SCAN_TEARDOWN_TIMEOUT; " +
                            "starting its replacement anyway (stale deliveries are fenced)"
                    }
                }
            }
            physicalPlan = plan
            val generation = ++physicalGeneration
            physicalScan = scope.launch {
                backend.scan(plan)
                    .catch { error -> Logger.warn(LogTags.ENGINE) { "multiplexed physical scan ended: ${error.message}" } }
                    .collect { advertisement -> fanOut(generation, advertisement) }
            }
        }
    }

    private suspend fun fanOut(generation: Long, raw: AdvertisementDto) = lock.withLock {
        if (generation != physicalGeneration) return@withLock
        val merged = mergeIdentityLocked(raw)
        cache.remove(merged.device.value)
        cache[merged.device.value] = CachedAdvertisement(merged, clock())
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
        val now = clock()
        val expired = cache.filterValues { now - it.observed > replayWindow }.keys
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
