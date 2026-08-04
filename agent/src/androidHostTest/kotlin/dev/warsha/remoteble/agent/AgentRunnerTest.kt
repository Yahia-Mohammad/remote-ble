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
    fun aBindFailureIsNamedSoTheUserCanActOnIt() = runBlocking {
        // The generic message above is right for an unknown failure, but a held port is the one
        // start failure a user can actually fix — and it used to kill the process rather than
        // produce any result at all (Rig B follow-up 16).
        val graph = TestGraph(onStart = { throw AgentBindException("0.0.0.0", 8080, null) })
        val runner = AgentRunner({ graph }, Unit)

        val failure = assertIs<AgentStartResult.Failed>(runner.start(AgentConfig()))
        // Names the port (certain) without asserting a cause (not certain): a hardware run against
        // an unassignable address produced this path while nothing held the port.
        assertTrue(
            failure.message.contains("8080"),
            "expected the port to be named, got: ${failure.message}",
        )
        assertFalse(
            failure.message.contains("is already in use"),
            "the message must not assert a cause the bind failure does not establish",
        )
        assertEquals(AgentRunnerState.FAILED, runner.state.value)
        assertFalse(runner.running.value)
        graph.dispose()
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

    /**
     * Rig B case 4 regression (`docs/rig-b-evidence.md` finding 8): `stop()` must actually stop
     * the WebSocket server, not merely close the Koin graph.
     *
     * On hardware this defect left the agent listening *and authenticating* after the user tapped
     * Stop — while the UI showed the `Start` button — and made the next Start abort the process on
     * `EADDRINUSE`. Nothing here caught it because the suite only ever asserted the lease half of
     * teardown; `serverStopped` was derived from `graphClosed`, so it read `true` no matter what.
     */
    @Test
    fun stopStopsTheServerAndDoesNotInferTheResultFromTheGraphClose() = runBlocking {
        val graph = TestGraph()
        val runner = AgentRunner({ graph }, Unit)
        assertEquals(AgentStartResult.Started, runner.start(AgentConfig()))

        val result = runner.stop()

        assertEquals(1, graph.stopServerCalls, "stop() must stop the WebSocket server")
        assertTrue(result.serverStopped)
        assertTrue(result.graphClosed)
        graph.dispose()
    }

    /**
     * The other half of the same finding: a server that fails to stop must be *reported* as such.
     * Deriving `serverStopped` from `graphClosed` made the result structurally incapable of saying
     * "the port is still open", which is exactly what the operator needed to know.
     */
    @Test
    fun aServerThatFailsToStopIsReportedRatherThanAssumedStopped() = runBlocking {
        val graph = TestGraph(onStopServer = { error("port still bound") })
        val runner = AgentRunner({ graph }, Unit)
        assertEquals(AgentStartResult.Started, runner.start(AgentConfig()))

        val result = runner.stop()

        assertEquals(1, graph.stopServerCalls)
        assertFalse(result.serverStopped, "a failed server stop must not report success")
        // Teardown is still best-effort: one failing step must not strand the graph or the state.
        assertTrue(result.graphClosed)
        assertFalse(runner.running.value)
        assertEquals(AgentRunnerState.STOPPED, runner.state.value)
        graph.dispose()
    }

    private class TestGraph(
        private val onStart: () -> Unit = {},
        private val onStopServer: () -> Unit = {},
    ) : AgentRunnerGraph {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        override val monitor = AgentMonitor()
        override val registry = PeripheralRegistry(scope)
        var startCalls = 0
        var closeCalls = 0
        var stopServerCalls = 0
        val disconnects = mutableListOf<DeviceHandle>()

        override suspend fun start() {
            startCalls++
            onStart()
        }

        override suspend fun disconnect(handle: DeviceHandle) {
            disconnects += handle
        }

        override fun stopServer() {
            stopServerCalls++
            onStopServer()
        }

        override fun close() {
            closeCalls++
        }

        fun dispose() = scope.cancel()
    }
}
