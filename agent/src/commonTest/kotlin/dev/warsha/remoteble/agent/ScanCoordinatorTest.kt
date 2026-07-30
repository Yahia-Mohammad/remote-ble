package dev.warsha.remoteble.agent

import dev.warsha.remoteble.protocol.AdvertisementDto
import dev.warsha.remoteble.protocol.DeviceHandle
import dev.warsha.remoteble.protocol.ScanFilter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class ScanCoordinatorTest {

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
}
