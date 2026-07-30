package dev.warsha.remoteble.agent

import dev.warsha.remoteble.protocol.AdvertisementDto
import dev.warsha.remoteble.protocol.AgentEvent
import dev.warsha.remoteble.protocol.DeviceHandle
import kotlin.test.Test
import kotlin.test.assertEquals
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
        first.events.send(result(1, "a")); first.events.send(result(1, "b"))
        second.events.send(result(2, "c")); second.events.send(result(2, "d"))
        arbiter.signal()
        runCurrent()
        assertEquals(listOf(1L, 2L, 1L, 2L), delivered)
        arbiter.close()
    }
}
