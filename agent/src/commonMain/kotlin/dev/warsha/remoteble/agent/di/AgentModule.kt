package dev.warsha.remoteble.agent.di

import dev.warsha.remoteble.agent.AgentBackend
import dev.warsha.remoteble.agent.AgentMonitor
import dev.warsha.remoteble.agent.AgentWebSocketServer
import dev.warsha.remoteble.agent.BleAgent
import dev.warsha.remoteble.agent.BleAgentBackend
import dev.warsha.remoteble.agent.BleBackend
import dev.warsha.remoteble.agent.ConnectionWatcher
import dev.warsha.remoteble.agent.ClientCredentials
import dev.warsha.remoteble.agent.EngineBleBackend
import dev.warsha.remoteble.agent.PeripheralRegistry
import dev.warsha.remoteble.agent.StrictModeState
import dev.warsha.remoteble.agent.DefaultDispatcherProvider
import dev.warsha.remoteble.agent.DispatcherProvider
import dev.warsha.remoteble.agent.platformName
import dev.warsha.remoteble.protocol.DeviceHandle
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import org.koin.core.module.Module
import org.koin.dsl.module

/** Runtime configuration for the agent process (CLI args / environment). */
data class AgentConfig(
    val bindHost: String = DEFAULT_BIND_HOST,
    val port: Int = DEFAULT_PORT,
    val authToken: String? = null,
    val namedCredentials: Map<String, String> = emptyMap(),
    val operatorToken: String? = null,
    val maxConnections: Int = BleAgent.DEFAULT_MAX_CONNECTIONS,
    val exclusiveByDefault: Boolean = true,
    val leaseGrace: Duration = 10.seconds,
    val transportGrace: Duration = 10.seconds,
    val livenessProbeInterval: Duration = 15.seconds,
) {
    companion object {
        const val DEFAULT_BIND_HOST: String = "127.0.0.1"
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
    require(config.exclusiveByDefault) {
        "Shared peripheral mode is unavailable in RemoteBLE 0.9.0; use exclusive ownership"
    }
    single { config }
    single {
        require(ClientCredentials.DEFAULT_PRINCIPAL !in config.namedCredentials || config.authToken == null) {
            "REMOTE_BLE_TOKEN cannot be combined with a named credential called 'default'"
        }
        ClientCredentials.of(
            buildMap {
                putAll(config.namedCredentials)
                config.authToken?.takeIf { it.isNotBlank() }?.let { put(ClientCredentials.DEFAULT_PRINCIPAL, it) }
            },
        )
    }
    single<DispatcherProvider> { DefaultDispatcherProvider }
    single { AgentMonitor() }
    single { StrictModeState() }
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
            defaultExclusive = true,
            onRelease = { handle -> backend.disconnect(DeviceHandle(handle)) },
        )
    }
    single<BleBackend> {
        EngineBleBackend(scope = get(qualifier = org.koin.core.qualifier.named("agent")))
    }
    single<AgentBackend> {
        BleAgentBackend(
            backend = get(),
            registry = get(),
            lifecycleScope = get(qualifier = org.koin.core.qualifier.named("agent")),
            maxConnections = config.maxConnections,
            observer = get<AgentMonitor>(),
            agentInfo = "RemoteBLE Agent 0.9.1 (kable/${platformName()})",
            strictMode = get(),
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
    single {
        AgentWebSocketServer(
            port = config.port,
            host = config.bindHost,
            backend = get(),
            credentials = get(),
            operatorToken = config.operatorToken,
            monitor = get<AgentMonitor>(),
            registry = get(),
            strictMode = get(),
        )
    }
}
