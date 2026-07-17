package dev.warsha.remoteble.agent

import dev.warsha.remoteble.agent.di.AgentConfig
import dev.warsha.remoteble.protocol.DeviceHandle
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Host coverage for the shared Android/iOS mobile runner lifecycle. */
class AgentRunnerTest {
    @Test
    fun concurrentStartsAreSerializedAndBuildOneGraph() = runBlocking {
        val enteredStart = CountDownLatch(1)
        val releaseStart = CountDownLatch(1)
        val graph = TestGraph(onStart = {
            enteredStart.countDown()
            check(releaseStart.await(5, TimeUnit.SECONDS)) { "test did not release startup" }
        })
        val runner = AgentRunner({ graph }, Unit)

        val first = async(Dispatchers.Default) { runner.start(AgentConfig()) }
        assertTrue(enteredStart.await(5, TimeUnit.SECONDS), "startup was not entered")
        val second = async(Dispatchers.Default) { runner.start(AgentConfig()) }
        releaseStart.countDown()

        assertEquals(AgentStartResult.Started, first.await())
        assertEquals(AgentStartResult.AlreadyRunning, second.await())
        assertEquals(1, graph.startCalls)
        assertTrue(runner.running.value)
        assertEquals(AgentRunnerState.RUNNING, runner.state.value)

        runner.stop()
        graph.dispose()
    }

    @Test
    fun cancelledStartClosesItsUnpublishedGraphAndLeavesStopped() = runBlocking {
        val graph = TestGraph(onStart = { throw CancellationException("host cancelled") })
        val runner = AgentRunner({ graph }, Unit)

        try {
            runner.start(AgentConfig())
        } catch (_: CancellationException) {
            // The caller owns cancellation; the runner is only responsible for cleanup.
        }

        assertEquals(1, graph.closeCalls)
        assertFalse(runner.running.value)
        assertEquals(AgentRunnerState.STOPPED, runner.state.value)
        assertNull(runner.monitor)
        assertNull(runner.registry)
        graph.dispose()
    }

    @Test
    fun failedStartReportsSafeErrorClosesGraphAndCanBeRetried() = runBlocking {
        val failed = TestGraph(onStart = { error("port bind includes host details") })
        val succeeding = TestGraph()
        var attempts = 0
        val runner = AgentRunner({
            if (attempts++ == 0) failed else succeeding
        }, Unit)

        val failure = runner.start(AgentConfig(bindHost = "192.0.2.1"))
        assertIs<AgentStartResult.Failed>(failure)
        assertEquals("Unable to start the agent; check the local log.", failure.message)
        assertEquals(1, failed.closeCalls)
        assertEquals(AgentRunnerState.FAILED, runner.state.value)
        assertFalse(runner.running.value)

        assertEquals(AgentStartResult.Started, runner.start(AgentConfig()))
        assertTrue(runner.running.value)
        assertEquals(AgentRunnerState.RUNNING, runner.state.value)
        runner.stop()
        succeeding.dispose()
        failed.dispose()
    }

    @Test
    fun concurrentStopsDisconnectOnceAndCloseOneGraph() = runBlocking {
        val graph = TestGraph()
        val runner = AgentRunner({ graph }, Unit)
        assertEquals(AgentStartResult.Started, runner.start(AgentConfig()))
        graph.registry.acquire("device", "client")
        graph.registry.onConnected("device", "client")

        val first = async(Dispatchers.Default) { runner.stop() }
        val second = async(Dispatchers.Default) { runner.stop() }
        val results = withTimeout(5_000) { listOf(first.await(), second.await()) }

        assertEquals(1, results.count { it.wasRunning })
        assertEquals(1, graph.disconnects.size)
        assertEquals(DeviceHandle("device"), graph.disconnects.single())
        assertEquals(1, graph.closeCalls)
        assertFalse(runner.running.value)
        assertEquals(AgentRunnerState.STOPPED, runner.state.value)
        graph.dispose()
    }

    /**
     * SHUTDOWN-01: a lease whose transport just dropped (radio link kept warm, pending release
     * inside [PeripheralRegistry]'s grace window — see LEASE-GRACE-01) must still be torn down by
     * a concurrent `stop()`, and the result must report that cleanup happened rather than silently
     * skipping a peripheral that "looked" mid-teardown.
     */
    @Test
    fun shutdownDuringTransportLossStillDisconnectsAndReportsCleanup() = runBlocking {
        val graph = TestGraph()
        val runner = AgentRunner({ graph }, Unit)
        assertEquals(AgentStartResult.Started, runner.start(AgentConfig()))
        graph.registry.acquire("device", "client")
        graph.registry.onConnected("device", "client")

        // The client's WebSocket dropped: the radio link stays warm and pending release, not yet
        // torn down (well inside the default 10s transport grace).
        graph.registry.onTransportDropped("client")

        val result = runner.stop()

        assertTrue(result.wasRunning)
        assertEquals(1, result.disconnectAttempts)
        assertEquals(0, result.disconnectFailures)
        assertTrue(result.graphClosed)
        assertEquals(DeviceHandle("device"), graph.disconnects.single())
        assertEquals(AgentRunnerState.STOPPED, runner.state.value)
        graph.dispose()
    }

    private class TestGraph(
        private val onStart: () -> Unit = {},
    ) : AgentRunnerGraph {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        override val monitor = AgentMonitor()
        override val registry = PeripheralRegistry(scope)
        var startCalls = 0
        var closeCalls = 0
        val disconnects = mutableListOf<DeviceHandle>()

        override fun start() {
            startCalls++
            onStart()
        }

        override suspend fun disconnect(handle: DeviceHandle) {
            disconnects += handle
        }

        override fun close() {
            closeCalls++
        }

        fun dispose() = scope.cancel()
    }
}
