package dev.warsha.ble.remoteble.agent.di

import dev.warsha.ble.remoteble.agent.AgentBackend
import dev.warsha.ble.remoteble.agent.AgentMonitor
import dev.warsha.ble.remoteble.agent.AgentWebSocketServer
import dev.warsha.ble.remoteble.agent.BleAgent
import dev.warsha.ble.remoteble.agent.BleAgentBackend
import dev.warsha.ble.remoteble.agent.BleBackend
import dev.warsha.ble.remoteble.agent.ConnectionWatcher
import dev.warsha.ble.remoteble.agent.EngineBleBackend
import dev.warsha.ble.remoteble.agent.PeripheralRegistry
import dev.warsha.ble.remoteble.agent.DefaultDispatcherProvider
import dev.warsha.ble.remoteble.agent.DispatcherProvider
import dev.warsha.ble.remoteble.protocol.DeviceHandle
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import org.koin.core.module.Module
import org.koin.dsl.module

/** Runtime configuration for the agent process (CLI args / environment). */
data class AgentConfig(
    val port: Int = DEFAULT_PORT,
    val authToken: String? = null,
    val maxConnections: Int = BleAgent.DEFAULT_MAX_CONNECTIONS,
    /** Whether a connected peripheral is exclusive to its client (the per-peripheral default). */
    val exclusiveByDefault: Boolean = true,
    /** How long a peripheral may stay BLE-disconnected before its lease is released. */
    val leaseGrace: Duration = 10.seconds,
    /** How long a dropped client's links are kept warm before its leases are released. */
    val transportGrace: Duration = 10.seconds,
    /** How often [ConnectionWatcher] runs its active (real GATT round-trip) liveness probe. */
    val livenessProbeInterval: Duration = 15.seconds,
) {
    companion object {
        const val DEFAULT_PORT: Int = 8080
    }
}

/**
 * Composition-root wiring for the runnable agent. Assembles the same object graph
 * `Main.kt` used to nest by hand — a Blue-Falcon macOS engine behind the real
 * [BleAgent] op handler, hosted by [AgentWebSocketServer]. The agent's classes keep
 * their plain constructors; this module just resolves them via `get()`.
 */
fun agentModule(config: AgentConfig): Module = module {
    single { config }
    single<DispatcherProvider> { DefaultDispatcherProvider }
    single { AgentMonitor() }
    // Agent-lifetime scope: survives any single connection so leases, grace timers, and
    // post-disconnect teardown outlive the per-WebSocket scope.
    single<CoroutineScope>(qualifier = org.koin.core.qualifier.named("agent")) {
        CoroutineScope(SupervisorJob() + get<DispatcherProvider>().default)
    }
    single {
        val backend = get<BleBackend>()
        PeripheralRegistry(
            scope = get(qualifier = org.koin.core.qualifier.named("agent")),
            leaseGrace = config.leaseGrace,
            transportGrace = config.transportGrace,
            defaultExclusive = config.exclusiveByDefault,
            onRelease = { handle -> backend.disconnect(DeviceHandle(handle)) },
        )
    }
    single<BleBackend> { EngineBleBackend() }
    single<AgentBackend> {
        BleAgentBackend(
            backend = get(),
            registry = get(),
            lifecycleScope = get(qualifier = org.koin.core.qualifier.named("agent")),
            maxConnections = config.maxConnections,
            observer = get<AgentMonitor>(),
            // capabilities defaults to the backend's own (EngineBleBackend → descriptors).
            // btleplug exposes no bonding/MTU control, so pairing and conn.priority stay off.
            agentInfo = "kable/${System.getProperty("os.name") ?: "jvm"}",
        )
    }
    single {
        ConnectionWatcher(
            registry = get<PeripheralRegistry>(),
            backend = get<BleBackend>(),
            scope = get<CoroutineScope>(qualifier = org.koin.core.qualifier.named("agent")),
            livenessInterval = config.livenessProbeInterval,
        )
    }
    single { AgentWebSocketServer(config.port, backend = get(), authToken = config.authToken, monitor = get<AgentMonitor>(), registry = get()) }
}
