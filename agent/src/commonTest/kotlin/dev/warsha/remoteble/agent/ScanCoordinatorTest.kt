package dev.warsha.remoteble.agent

import dev.warsha.remoteble.protocol.AdvertisementDto
import dev.warsha.remoteble.protocol.Capabilities
import dev.warsha.remoteble.protocol.DeviceHandle
import dev.warsha.remoteble.protocol.ScanFilter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext

@OptIn(ExperimentalCoroutinesApi::class)
class ScanCoordinatorTest {

    private class ControlledBackend : BleBackend by FakeBleBackend() {
        val advertisements = MutableSharedFlow<AdvertisementDto>(extraBufferCapacity = 512)
        val started = mutableListOf<List<ScanFilter>>()

        override fun scan(filters: List<ScanFilter>) = flow<AdvertisementDto> {
            started += filters
            advertisements.collect { emit(it) }
        }
    }

    private class CancellationBlockingBackend : BleBackend by FakeBleBackend() {
        val collectorCancellationEntered = CompletableDeferred<Unit>()
        val releaseCollectorCancellation = CompletableDeferred<Unit>()
        var activeCollectors = 0

        override fun scan(filters: List<ScanFilter>) = flow<AdvertisementDto> {
            activeCollectors++
            try {
                awaitCancellation()
            } finally {
                collectorCancellationEntered.complete(Unit)
                withContext(NonCancellable) { releaseCollectorCancellation.await() }
                activeCollectors--
            }
        }
    }

    private fun backend() = FakeBleBackend(
        advertisements = listOf(
            AdvertisementDto(DeviceHandle("A"), "HRM", -55, serviceUuids = listOf("180d")),
            AdvertisementDto(DeviceHandle("B"), "Battery", -65, serviceUuids = listOf("180f")),
        ),
    )

    @Test
    fun multiplexedMatcherUsesOrAcrossFiltersAndAndWithinOneFilter() {
        val hrm = AdvertisementDto(DeviceHandle("A"), "HRM", -55, serviceUuids = listOf("180d"))
        val battery = AdvertisementDto(DeviceHandle("B"), "Battery", -65, serviceUuids = listOf("180f"))
        val filters = listOf(ScanFilter(service = "180d", name = "HRM"), ScanFilter(service = "180f"))
        assertEquals(true, scanMatches(filters, hrm))
        assertEquals(true, scanMatches(filters, battery))
        assertEquals(false, scanMatches(listOf(ScanFilter(service = "180d", name = "Battery")), hrm))
    }

    @Test
    fun singleRefusesDifferentKeyButRebindsItsIncumbent() = runTest {
        val coordinator = ScanCoordinator(backend(), backgroundScope, ScanConcurrencyMode.SINGLE, 10.seconds)
        val first = assertIs<ScanAdmission.Accepted>(
            coordinator.startOrReplace(LogicalScanKey("client-a", 1), 1, emptyList()),
        )
        assertIs<ScanAdmission.SingleOccupied>(
            coordinator.startOrReplace(LogicalScanKey("client-b", 1), 2, emptyList()),
        )
        val rebound = assertIs<ScanAdmission.Accepted>(
            coordinator.startOrReplace(LogicalScanKey("client-a", 1), 3, listOf(ScanFilter(name = "HRM"))),
        )
        assertEquals(3, rebound.registration.connectionGeneration)
        coordinator.stop(rebound.registration)
        coordinator.stop(first.registration) // fenced stale stop is a no-op
    }

    @Test
    fun sameGenerationReplacementHasItsOwnFence() = runTest {
        val coordinator = ScanCoordinator(backend(), backgroundScope, ScanConcurrencyMode.SINGLE, 10.seconds)
        val first = assertIs<ScanAdmission.Accepted>(
            coordinator.startOrReplace(LogicalScanKey("client-a", 1), 7, emptyList()),
        )
        val replacement = assertIs<ScanAdmission.Accepted>(
            coordinator.startOrReplace(LogicalScanKey("client-a", 1), 7, emptyList()),
        )
        assertTrue(first.registration != replacement.registration)

        coordinator.stop(first.registration)
        assertIs<ScanAdmission.SingleOccupied>(
            coordinator.startOrReplace(LogicalScanKey("client-b", 1), 8, emptyList()),
        )
        coordinator.stop(replacement.registration)
        assertIs<ScanAdmission.Accepted>(
            coordinator.startOrReplace(LogicalScanKey("client-b", 1), 8, emptyList()),
        )
    }

    @Test
    fun graceHeldKeyCountsAgainstStableClientCap() = runTest {
        val coordinator = ScanCoordinator(
            backend(), backgroundScope, ScanConcurrencyMode.MULTIPLEXED, 10.seconds, maxActiveScans = 1,
        )
        coordinator.startOrReplace(LogicalScanKey("client", 1), 1, emptyList())
        coordinator.detachConnection(1)
        assertFailsWith<ScanLimitExceeded> {
            coordinator.startOrReplace(LogicalScanKey("client", 2), 2, emptyList())
        }
        assertIs<ScanAdmission.Accepted>(
            coordinator.startOrReplace(LogicalScanKey("client", 1), 2, emptyList()),
        )
    }

    @Test
    fun graceExpiryReleasesTheSingleSlotForADifferentKey() = runTest {
        val coordinator = ScanCoordinator(backend(), backgroundScope, ScanConcurrencyMode.SINGLE, 10.seconds)
        coordinator.startOrReplace(LogicalScanKey("client-a", 1), 1, emptyList())
        coordinator.detachConnection(1)
        assertIs<ScanAdmission.SingleOccupied>(
            coordinator.startOrReplace(LogicalScanKey("client-b", 2), 2, emptyList()),
        )
        testScheduler.advanceTimeBy(10.seconds.inWholeMilliseconds)
        testScheduler.runCurrent()
        assertIs<ScanAdmission.Accepted>(
            coordinator.startOrReplace(LogicalScanKey("client-b", 2), 2, emptyList()),
        )
    }

    @Test
    fun physicalPlanWidensForServiceScansAndNeverNarrowsUntilIdle() = runTest {
        val backend = backend()
        val coordinator = ScanCoordinator(backend, backgroundScope, ScanConcurrencyMode.MULTIPLEXED, 10.seconds)
        coordinator.startOrReplace(LogicalScanKey("a", 1), 1, listOf(ScanFilter(service = "180d")))
        testScheduler.runCurrent()
        assertEquals(listOf("0000180d-0000-1000-8000-00805f9b34fb"), backend.scanFilters.last().mapNotNull { it.service })
        coordinator.startOrReplace(LogicalScanKey("b", 2), 2, listOf(ScanFilter(service = "180f")))
        testScheduler.runCurrent()
        assertEquals(2, backend.scanFilters.last().size)
        coordinator.startOrReplace(LogicalScanKey("c", 3), 3, emptyList())
        testScheduler.runCurrent()
        assertTrue(backend.scanFilters.last().isEmpty())
    }

    @Test
    fun physicalPlanReplacementWaitsForThePriorCollectorToStop() = runTest {
        val backend = backend()
        val coordinator = ScanCoordinator(backend, backgroundScope, ScanConcurrencyMode.MULTIPLEXED, 10.seconds)
        coordinator.startOrReplace(LogicalScanKey("a", 1), 1, listOf(ScanFilter(service = "180d")))
        testScheduler.runCurrent()
        coordinator.startOrReplace(LogicalScanKey("b", 2), 2, listOf(ScanFilter(service = "180f")))
        testScheduler.runCurrent()
        assertEquals(1, backend.maxConcurrentScans)
    }

    @Test
    fun cancelledReplacementStillInstallsANewPhysicalCollector() = runTest {
        val backend = CancellationBlockingBackend()
        val coordinator = ScanCoordinator(backend, backgroundScope, ScanConcurrencyMode.MULTIPLEXED, 10.seconds)
        coordinator.startOrReplace(LogicalScanKey("incumbent", 1), 1, listOf(ScanFilter(service = "180d")))
        testScheduler.runCurrent()
        assertEquals(1, backend.activeCollectors)

        val replacement = async {
            coordinator.startOrReplace(LogicalScanKey("joining", 2), 2, listOf(ScanFilter(service = "180f")))
        }
        backend.collectorCancellationEntered.await()
        replacement.cancel()
        backend.releaseCollectorCancellation.complete(Unit)
        testScheduler.runCurrent()
        runCatching { replacement.await() }
        testScheduler.runCurrent()

        assertEquals(
            1,
            backend.activeCollectors,
            "cancelling a joining command must not leave the incumbent without a physical collector",
        )
    }

    @Test
    fun configuredModeIsTheOnlyAdvertisedScanConcurrencyCapability() {
        val advertised = advertisedCapabilities(
            setOf(
                Capabilities.SCAN_CONCURRENCY_MULTIPLEXED,
                Capabilities.SCAN_CONCURRENCY_SINGLE,
                "other",
            ),
            ScanConcurrencyMode.UNCONTROLLED,
        )
        assertEquals(
            setOf(Capabilities.SCAN_CONCURRENCY_UNCONTROLLED),
            advertised.intersect(
                setOf(
                    Capabilities.SCAN_CONCURRENCY_MULTIPLEXED,
                    Capabilities.SCAN_CONCURRENCY_SINGLE,
                    Capabilities.SCAN_CONCURRENCY_UNCONTROLLED,
                ),
            ),
        )
        assertTrue("other" in advertised)
    }

    @Test
    fun lateJoinAfterReplayWindowExpiryDoesNotReceiveStaleAdvertisements() = runTest {
        var now = Duration.ZERO
        val backend = ControlledBackend()
        val coordinator = ScanCoordinator(
            backend,
            backgroundScope,
            ScanConcurrencyMode.MULTIPLEXED,
            10.seconds,
            replayWindow = 30.seconds,
            mailboxCapacity = 8,
            clock = { now },
        )
        coordinator.startOrReplace(LogicalScanKey("incumbent", 1), 1, emptyList())
        testScheduler.runCurrent()
        backend.advertisements.emit(
            AdvertisementDto(DeviceHandle("stale"), "Stale", -50, serviceUuids = listOf("180d")),
        )
        testScheduler.runCurrent()

        now = 31.seconds
        val late = assertIs<ScanAdmission.Accepted>(
            coordinator.startOrReplace(LogicalScanKey("late", 1), 2, listOf(ScanFilter(service = "180d"))),
        )
        assertEquals(
            null,
            kotlinx.coroutines.withTimeoutOrNull(1.seconds) { late.advertisements.first() },
            "expired cache entries must not be replayed to a late joiner",
        )
    }

    @Test
    fun replayCacheEvictsOldestDeviceAtCapacity() = runTest {
        val backend = ControlledBackend()
        // Deliberately at production defaults (mailboxCapacity=64, replayCap=256): the mailbox
        // must be sized to hold a full replay burst on its own, not merely wide enough for this
        // test's numbers.
        val coordinator = ScanCoordinator(backend, backgroundScope, ScanConcurrencyMode.MULTIPLEXED, 10.seconds)
        coordinator.startOrReplace(LogicalScanKey("incumbent", 1), 1, emptyList())
        testScheduler.runCurrent()
        repeat(257) { index ->
            backend.advertisements.emit(
                AdvertisementDto(DeviceHandle("device-$index"), null, -50),
            )
        }
        testScheduler.runCurrent()

        val late = assertIs<ScanAdmission.Accepted>(
            coordinator.startOrReplace(LogicalScanKey("late", 1), 2, emptyList()),
        )
        val replayed = late.advertisements.take(256).toList()
        assertEquals(256, replayed.size)
        assertTrue(replayed.none { it.device.value == "device-0" })
        assertTrue(replayed.any { it.device.value == "device-256" })
    }

    @Test
    fun singleAdmissionIsLinearizableForConcurrentDifferentKeys() = runTest {
        val coordinator = ScanCoordinator(backend(), backgroundScope, ScanConcurrencyMode.SINGLE, 10.seconds)
        val results = listOf(
            async { coordinator.startOrReplace(LogicalScanKey("a", 1), 1, emptyList()) },
            async { coordinator.startOrReplace(LogicalScanKey("b", 1), 2, emptyList()) },
        ).awaitAll()
        assertEquals(1, results.count { it is ScanAdmission.Accepted })
        assertEquals(1, results.count { it is ScanAdmission.SingleOccupied })
    }
}
