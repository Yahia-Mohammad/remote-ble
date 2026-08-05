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
import dev.warsha.remoteble.agent.ScanConcurrencyMode
import dev.warsha.remoteble.agent.ScanCoordinator
import dev.warsha.remoteble.agent.SimulatedBleBackend
import dev.warsha.remoteble.agent.SimulationProfile
import dev.warsha.remoteble.agent.StrictModeState
import dev.warsha.remoteble.agent.WritePolicy
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
    /**
     * Whether the status dashboard answers requests from off-device. Off by default — the dashboard
     * exposes cross-client information over unencrypted HTTP, so it is loopback-only (reach it from the
     * device itself, or tunnel: `adb forward` / `iproxy`) unless deliberately opened up.
     */
    val allowRemoteDashboard: Boolean = false,
    val maxConnections: Int = BleAgent.DEFAULT_MAX_CONNECTIONS,
    val exclusiveByDefault: Boolean = true,
    /**
     * How long a lease survives an *unsolicited BLE disconnect* — the radio link is already down, so
     * holding it only reserves the peripheral for a returning owner. Short on purpose.
     */
    val leaseGrace: Duration = 10.seconds,
    /**
     * How long a lease survives the *client's transport* dropping, with the radio link left warm.
     *
     * Two minutes because the binding case is a client whose process is short-lived: a CLI, a script,
     * or a coding agent that runs one command per process and expects the next command to resume the
     * same connection. Ten seconds is shorter than the gap between two commands a human types, so it
     * silently paid a full reconnect and rediscovery on nearly every step. The cost of the longer
     * window is contention — a peripheral stays leased for up to two minutes after its holder walks
     * away — so an operator running a shared rig should lower it via `REMOTE_BLE_TRANSPORT_GRACE_MS`.
     */
    val transportGrace: Duration = 120.seconds,
    val livenessProbeInterval: Duration = 15.seconds,
    /** Scan isolation policy advertised for this process lifetime. */
    val scanConcurrency: ScanConcurrencyMode = ScanConcurrencyMode.MULTIPLEXED,
    /**
     * Whether a device whose writes have stopped completing rejects further writes immediately
     * instead of waiting out `EngineBleBackend.GATT_OP_TIMEOUT` on each one. Same error either way
     * — this changes latency, not semantics. See `EngineBleBackend.markWriteDegraded` for the
     * backend defect it works around, and turn it off (`REMOTE_BLE_WRITE_FAIL_FAST=false`) to run
     * without the workaround.
     */
    val failFastOnDegradedWrites: Boolean = true,
    /** Non-null only for the JVM's explicit radio-less simulation mode. */
    val simulationProfile: SimulationProfile? = null,
    /** Per-principal write allowlist (U7). Permissive by default: no existing consumer breaks. */
    val writePolicy: WritePolicy = WritePolicy.permissive(),
) {
    companion object {
        const val DEFAULT_BIND_HOST: String = "127.0.0.1"
        const val DEFAULT_PORT: Int = 8080
    }
}

/**
 * Composition-root wiring for the runnable agent. Assembles the selected real or simulated
 * [BleBackend] behind the real [BleAgent] op handler, hosted by [AgentWebSocketServer]. The
 * agent's classes keep their plain constructors; this module just resolves them via `get()`.
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
            maxSlots = config.maxConnections,
            onRelease = { handle -> backend.disconnect(DeviceHandle(handle)) },
        )
    }
    single<BleBackend> {
        val scope = get<CoroutineScope>(qualifier = org.koin.core.qualifier.named("agent"))
        config.simulationProfile?.let { SimulatedBleBackend(it, scope) }
            ?: EngineBleBackend(scope = scope, failFastOnDegradedWrites = config.failFastOnDegradedWrites)
    }
    single {
        ScanCoordinator(
            backend = get(),
            scope = get(qualifier = org.koin.core.qualifier.named("agent")),
            mode = config.scanConcurrency,
            transportGrace = config.transportGrace,
        )
    }
    single<AgentBackend> {
        BleAgentBackend(
            backend = get(),
            registry = get(),
            lifecycleScope = get(qualifier = org.koin.core.qualifier.named("agent")),
            maxConnections = config.maxConnections,
            observer = get<AgentMonitor>(),
            agentInfo = "RemoteBLE Agent 0.10.0 (kable/${platformName()})",
            strictMode = get(),
            scanCoordinator = get(),
            writePolicy = config.writePolicy,
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
            allowRemoteDashboard = config.allowRemoteDashboard,
            monitor = get<AgentMonitor>(),
            registry = get(),
            strictMode = get(),
        )
    }
}
