package dev.warsha.remoteble.agent

import dev.warsha.remoteble.agent.di.AgentConfig
import dev.warsha.remoteble.agent.di.agentModule
import dev.warsha.remoteble.protocol.DeviceHandle
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.cancellation.CancellationException
import org.koin.core.KoinApplication
import org.koin.dsl.koinApplication

/** Observable mobile-runner lifecycle; [RUNNING] is the only state with a live server graph. */
enum class AgentRunnerState { STOPPED, STARTING, RUNNING, STOPPING, FAILED }

/** The deterministic result of an attempted [AgentRunner.start]. */
sealed interface AgentStartResult {
    data object Started : AgentStartResult
    data object AlreadyRunning : AgentStartResult
    data class Failed(val message: String) : AgentStartResult
}

/** Best-effort cleanup facts returned by [AgentRunner.stop]. */
data class AgentStopResult(
    val wasRunning: Boolean,
    val disconnectAttempts: Int,
    val disconnectFailures: Int,
    val serverStopped: Boolean,
    val graphClosed: Boolean,
)

/** Test seam for the mobile runner; production uses the private Koin-backed implementation. */
internal interface AgentRunnerGraph {
    val monitor: AgentMonitor
    val registry: PeripheralRegistry
    fun start()
    suspend fun disconnect(handle: DeviceHandle)
    fun close()
}

private class KoinAgentRunnerGraph(config: AgentConfig) : AgentRunnerGraph {
    private val application = koinApplication { modules(agentModule(config)) }
    private val koin = application.koin
    override val monitor: AgentMonitor = koin.get()
    override val registry: PeripheralRegistry = koin.get()
    private val server: AgentWebSocketServer = koin.get()
    private val watcher: ConnectionWatcher = koin.get()
    private val backend: BleBackend = koin.get()

    override fun start() {
        server.start()
        watcher.start()
    }

    override suspend fun disconnect(handle: DeviceHandle) = backend.disconnect(handle)
    override fun close() = application.close()
}

/**
 * Mobile composition root: the Android/iOS equivalent of `Main.kt`'s CLI wiring, but
 * restartable from the UI (a phone app starts/stops the agent interactively, unlike the
 * desktop CLI's run-until-killed model) and built on a private [KoinApplication] rather than
 * the process-global `startKoin` so start/stop can't collide with any other Koin usage in the
 * host app.
 *
 * [start]/[stop] can run on different threads (e.g. the Activity's `LaunchedEffect` on Main vs.
 * `AgentViewModel.onCleared()`'s best-effort teardown on `Dispatchers.Default`), and [monitor]/
 * [registry] are polled from a third (a `LaunchedEffect` in
 * [dev.warsha.remoteble.agent.ui.AgentApp]). All of `app`/`server`/[monitor]/[registry]/
 * [config] are therefore guarded by [lock] — the same atomicfu `SynchronizedObject` pattern
 * [EngineBleBackend] uses, since Kotlin/Native has no `synchronized`.
 */
class AgentRunner private constructor(
    private val graphFactory: (AgentConfig) -> AgentRunnerGraph,
) {
    constructor() : this(::KoinAgentRunnerGraph)
    internal constructor(graphFactory: (AgentConfig) -> AgentRunnerGraph, testOnly: Unit = Unit) : this(graphFactory)

    private val lock = SynchronizedObject()
    // This is the one lifecycle owner. Do not use [lock] for lifecycle sequencing: startup and
    // teardown call external code and must not expose a half-built graph to a competing caller.
    private val lifecycleMutex = Mutex()
    private var graph: AgentRunnerGraph? = null

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _state = MutableStateFlow(AgentRunnerState.STOPPED)
    val state: StateFlow<AgentRunnerState> = _state.asStateFlow()

    private var _monitor: AgentMonitor? = null
    val monitor: AgentMonitor? get() = synchronized(lock) { _monitor }

    private var _registry: PeripheralRegistry? = null
    val registry: PeripheralRegistry? get() = synchronized(lock) { _registry }

    private var _config: AgentConfig? = null
    val config: AgentConfig? get() = synchronized(lock) { _config }

    suspend fun start(config: AgentConfig): AgentStartResult = lifecycleMutex.withLock {
        if (_state.value == AgentRunnerState.RUNNING) return@withLock AgentStartResult.AlreadyRunning
        _state.value = AgentRunnerState.STARTING
        var newGraph: AgentRunnerGraph? = null
        try {
            newGraph = graphFactory(config)
            newGraph.start()
            synchronized(lock) {
                graph = newGraph
                _config = config
                _monitor = newGraph.monitor
                _registry = newGraph.registry
            }
            _running.value = true
            _state.value = AgentRunnerState.RUNNING
            AgentStartResult.Started
        } catch (t: Throwable) {
            runCatchingNonCancellation { newGraph?.close() }
            _running.value = false
            if (t is CancellationException) {
                _state.value = AgentRunnerState.STOPPED
                throw t
            }
            // Exception messages may include endpoint/configuration values. Publish a stable,
            // non-sensitive state instead; detailed diagnostics remain in the local log.
            _state.value = AgentRunnerState.FAILED
            AgentStartResult.Failed("Unable to start the agent; check the local log.")
        }
    }

    /** Disconnects any live peripherals, stops the server, and tears the Koin graph down. */
    suspend fun stop(): AgentStopResult = lifecycleMutex.withLock {
        _state.value = AgentRunnerState.STOPPING
        // Capture *and* clear the graph atomically. Two teardowns can race (e.g. AgentService's
        // onTaskRemoved on Main vs. AgentViewModel.onCleared() on Dispatchers.Default); doing this
        // in one locked step means only the caller that reads a non-null `app` proceeds, so the
        // Koin graph is never disconnected/closed twice.
        val (currentGraph, currentRegistry) = synchronized(lock) {
            val captured = graph to _registry
            graph = null
            _monitor = null
            _registry = null
            _config = null
            captured
        }
        if (currentGraph == null) {
            _running.value = false
            _state.value = AgentRunnerState.STOPPED
            return@withLock AgentStopResult(false, 0, 0, serverStopped = true, graphClosed = true)
        }
        val leases = currentRegistry?.snapshot()?.filter { it.connected }.orEmpty()
        var disconnectFailures = 0
        leases.forEach { lease ->
            if (runCatchingNonCancellation { currentGraph.disconnect(DeviceHandle(lease.handle)) }.isFailure) {
                disconnectFailures++
            }
        }
        // Best-effort: teardown must complete even if a step throws (e.g. a double-close slipping
        // through, or a server already stopped) so `running` still flips and callers don't see an
        // uncaught throwable on a fire-and-forget teardown scope.
        val graphClosed = runCatchingNonCancellation { currentGraph.close() }.isSuccess
        // Flipped last (not inside the lock) so a concurrent start() early-returns on the still-true
        // `running` guard for the whole teardown window rather than racing a half-torn-down graph.
        _running.value = false
        _state.value = AgentRunnerState.STOPPED
        AgentStopResult(
            wasRunning = true,
            disconnectAttempts = leases.size,
            disconnectFailures = disconnectFailures,
            serverStopped = graphClosed,
            graphClosed = graphClosed,
        )
    }
}
