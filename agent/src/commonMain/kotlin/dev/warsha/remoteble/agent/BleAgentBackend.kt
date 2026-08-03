package dev.warsha.remoteble.agent

import dev.warsha.remoteble.protocol.DeviceHandle
import dev.warsha.remoteble.protocol.Capabilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow

/**
 * Hosts the real [BleAgent] over a [BleBackend] behind [AgentWebSocketServer]. Every
 * connection's [BleAgent] shares the one [registry] (cross-client peripheral ownership). The
 * registry defaults to a private agent-lifetime one whose warm-link teardown disconnects via
 * [backend]; the composition root injects a shared instance so the dashboard and watcher see
 * the same leases.
 */
class BleAgentBackend(
    private val backend: BleBackend,
    private val lifecycleScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val registry: PeripheralRegistry =
        PeripheralRegistry(lifecycleScope, onRelease = { backend.disconnect(DeviceHandle(it)) }),
    private val maxConnections: Int = BleAgent.DEFAULT_MAX_CONNECTIONS,
    private val observer: AgentObserver = AgentObserver.None,
    private val scanCoordinator: ScanCoordinator? = null,
    // Optional features advertised in the handshake: the backend's own (radio-dependent,
    // e.g. descriptors/pairing) unioned with the agent-level ones (radio-independent, e.g.
    // connection-slot events). Derived so the advertised set can't drift from what's wired.
    private val capabilities: Set<String> = advertisedCapabilities(backend.capabilities, scanCoordinator?.mode),
    private val agentInfo: String? = null,
    // Shared identifier strict-mode switch (capability `identifier.translate`), flipped from the
    // dashboard. One instance across all connections so a toggle applies agent-wide.
    private val strictMode: StrictModeState = StrictModeState(),
) : AgentBackend {
    override fun serve(incoming: Flow<ByteArray>, outgoing: suspend (ByteArray) -> Unit, scope: CoroutineScope, connectionId: Long, clientKey: String): Job =
        BleAgent(
            incoming, outgoing, scope, backend,
            maxConnections = maxConnections,
            clientId = connectionId,
            observer = observer,
            registry = registry,
            clientKey = clientKey,
            capabilities = capabilities,
            agentInfo = agentInfo,
            strictMode = strictMode,
            scanCoordinator = scanCoordinator,
        ).start()
}

private val SCAN_CONCURRENCY_CAPABILITIES = setOf(
    Capabilities.SCAN_CONCURRENCY_MULTIPLEXED,
    Capabilities.SCAN_CONCURRENCY_SINGLE,
    Capabilities.SCAN_CONCURRENCY_UNCONTROLLED,
)

internal fun advertisedCapabilities(
    backendCapabilities: Set<String>,
    scanMode: ScanConcurrencyMode?,
): Set<String> = (backendCapabilities + BleAgent.AGENT_CAPABILITIES)
    .filterNot { it in SCAN_CONCURRENCY_CAPABILITIES }
    .toSet() + setOfNotNull(scanMode?.capability)
