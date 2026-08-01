package dev.warsha.remoteble.agent

import dev.warsha.remoteble.protocol.AdvertisementDto
import dev.warsha.remoteble.protocol.AgentEvent
import dev.warsha.remoteble.protocol.DeviceHandle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class ScanOutboundArbiterTest {
    @Test
    fun roundRobinPreventsOneLogicalScanFromMonopolisingOutput() = runTest {
        val delivered = mutableListOf<Long>()
        val arbiter = ScanOutboundArbiter(this, { event ->
            delivered += (event as AgentEvent.ScanResult).scanId
        })
        val first = arbiter.register(1)
        val second = arbiter.register(2)
        fun result(scanId: Long, suffix: String) = AgentEvent.ScanResult(
            scanId, AdvertisementDto(DeviceHandle(suffix), null, -50),
        )
        repeat(64) { index ->
            assertTrue(first.events.trySend(result(1, "a-$index")).isSuccess)
            assertTrue(second.events.trySend(result(2, "b-$index")).isSuccess)
        }
        arbiter.signal()
        runCurrent()
        assertEquals(List(128) { if (it % 2 == 0) 1L else 2L }, delivered)
        arbiter.close()
    }

    @Test
    fun replayReservationFitsTheWholeRetainedSetBeforeArbitration() = runTest {
        val arbiter = ScanOutboundArbiter(this, emit = {}, mailboxCapacity = 320)
        val sink = arbiter.register(1)
        repeat(256) { index ->
            assertTrue(
                sink.events.trySend(
                    AgentEvent.ScanResult(1, AdvertisementDto(DeviceHandle("replay-$index"), null, -50)),
                ).isSuccess,
                "the full retained replay must fit before the arbiter begins delivery",
            )
        }
        arbiter.unregister(sink)
        arbiter.close()
    }
}
