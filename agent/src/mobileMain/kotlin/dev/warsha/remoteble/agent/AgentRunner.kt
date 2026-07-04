package dev.warsha.remoteble.agent

import dev.warsha.remoteble.agent.di.AgentConfig
import dev.warsha.remoteble.agent.di.agentModule
import dev.warsha.remoteble.protocol.DeviceHandle
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.koin.core.KoinApplication
import org.koin.dsl.koinApplication

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
class AgentRunner {
    private val lock = SynchronizedObject()
    private var app: KoinApplication? = null
    private var server: AgentWebSocketServer? = null

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private var _monitor: AgentMonitor? = null
    val monitor: AgentMonitor? get() = synchronized(lock) { _monitor }

    private var _registry: PeripheralRegistry? = null
    val registry: PeripheralRegistry? get() = synchronized(lock) { _registry }

    private var _config: AgentConfig? = null
    val config: AgentConfig? get() = synchronized(lock) { _config }

    fun start(config: AgentConfig) {
        if (_running.value) return
        val application = koinApplication { modules(agentModule(config)) }
        val koin = application.koin
        val newMonitor = koin.get<AgentMonitor>()
        val newRegistry = koin.get<PeripheralRegistry>()
        val newServer = koin.get<AgentWebSocketServer>().also { it.start() }
        koin.get<ConnectionWatcher>().start()
        synchronized(lock) {
            app = application
            _config = config
            _monitor = newMonitor
            _registry = newRegistry
            server = newServer
        }
        _running.value = true
    }

    /** Disconnects any live peripherals, stops the server, and tears the Koin graph down. */
    suspend fun stop() {
        // Capture *and* clear the graph atomically. Two teardowns can race (e.g. AgentService's
        // onTaskRemoved on Main vs. AgentViewModel.onCleared() on Dispatchers.Default); doing this
        // in one locked step means only the caller that reads a non-null `app` proceeds, so the
        // Koin graph is never disconnected/closed twice.
        val (application, currentRegistry, currentServer) = synchronized(lock) {
            val captured = Triple(app, _registry, server)
            app = null
            server = null
            _monitor = null
            _registry = null
            _config = null
            captured
        }
        if (application == null) return
        val backend = application.koin.get<BleBackend>()
        currentRegistry?.snapshot()?.filter { it.connected }?.forEach { lease ->
            runCatchingNonCancellation { backend.disconnect(DeviceHandle(lease.handle)) }
        }
        // Best-effort: teardown must complete even if a step throws (e.g. a double-close slipping
        // through, or a server already stopped) so `running` still flips and callers don't see an
        // uncaught throwable on a fire-and-forget teardown scope.
        runCatchingNonCancellation { currentServer?.stop() }
        runCatchingNonCancellation { application.close() }
        // Flipped last (not inside the lock) so a concurrent start() early-returns on the still-true
        // `running` guard for the whole teardown window rather than racing a half-torn-down graph.
        _running.value = false
    }
}
