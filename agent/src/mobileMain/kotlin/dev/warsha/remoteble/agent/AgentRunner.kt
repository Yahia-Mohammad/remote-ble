package dev.warsha.remoteble.agent

import dev.warsha.remoteble.agent.di.AgentConfig
import dev.warsha.remoteble.agent.di.agentModule
import dev.warsha.remoteble.protocol.DeviceHandle
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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

    /**
     * Suspends until the server is actually listening, throwing if it is not — so
     * [AgentRunner.start] can report a bind failure as [AgentStartResult.Failed] instead of
     * returning `Started` and letting the failure abort the process from a CIO worker.
     */
    suspend fun start()

    suspend fun disconnect(handle: DeviceHandle)

    /**
     * Stops the WebSocket server. Separate from [close] because closing the Koin graph does **not**
     * stop it: Koin only runs a definition's `onClose` callback when dropping it, the module
     * declares none, and [AgentWebSocketServer] is not `AutoCloseable`, so nothing would ever call
     * its `stop()`. Leaving it running kept the agent listening *and authenticating* after the user
     * tapped Stop, and made the next Start abort the process on `EADDRINUSE` — see Rig B case 4
     * (`docs/pr8-rig-b-evidence.md`, findings 8 and 10). `Main.kt` never had this bug because the
     * desktop shutdown hook calls `server.stop()` explicitly; this is the mobile equivalent.
     */
    fun stopServer()

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

    override suspend fun start() {
        server.start()
        watcher.start()
    }

    override suspend fun disconnect(handle: DeviceHandle) = backend.disconnect(handle)
    override fun stopServer() = server.stop()
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
            //
            // A bind failure is called out by name because it is the one start failure a user can
            // actually act on, and because it used to be unreportable entirely: the bind happened
            // asynchronously after start() had returned Started, so on Kotlin/Native it aborted the
            // process instead. The port is not a secret — the UI shows the address on every run.
            _state.value = AgentRunnerState.FAILED
            if (t is AgentBindException) {
                // Deliberately "may be": a bind can fail for reasons other than a port conflict
                // (an unassignable address, a sandbox denial), and the earlier wording asserted
                // "is already in use" — which read as a confident diagnosis in a hardware run
                // where nothing held the port at all. Name what is certain (the port did not
                // open), suggest the likely cause, claim neither.
                AgentStartResult.Failed("Could not open port ${t.port}. Another app may be using it.")
            } else {
                AgentStartResult.Failed("Unable to start the agent; check the local log.")
            }
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
        //
        // Stop the server *before* closing the graph, matching the order `Main.kt`'s shutdown hook
        // uses (disconnect leases, then `server.stop()`), and report the two outcomes separately:
        // this used to derive `serverStopped` from `graphClosed`, which asserted a teardown that
        // never happened.
        //
        // Off the caller's dispatcher, because both of these *block*: Ktor's `stop()` waits out its
        // grace + timeout (600ms by default) and `Koin.close()` is synchronous. The UI calls this
        // straight from the composition scope (`AgentApp`'s `onStop`), which is `Dispatchers.Main`
        // — blocking there would freeze the screen for the whole teardown, and on Kotlin/Native
        // Main is the one dispatcher worth keeping free.
        val (serverStopped, graphClosed) = withContext(Dispatchers.Default) {
            val stopped = runCatchingNonCancellation { currentGraph.stopServer() }.isSuccess
            val closed = runCatchingNonCancellation { currentGraph.close() }.isSuccess
            stopped to closed
        }
        // Flipped last (not inside the lock) so a concurrent start() early-returns on the still-true
        // `running` guard for the whole teardown window rather than racing a half-torn-down graph.
        _running.value = false
        _state.value = AgentRunnerState.STOPPED
        AgentStopResult(
            wasRunning = true,
            disconnectAttempts = leases.size,
            disconnectFailures = disconnectFailures,
            serverStopped = serverStopped,
            graphClosed = graphClosed,
        )
    }
}
