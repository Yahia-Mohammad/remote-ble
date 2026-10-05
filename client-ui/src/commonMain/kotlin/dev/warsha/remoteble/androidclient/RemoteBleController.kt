package dev.warsha.remoteble.androidclient

import dev.warsha.remoteble.androidclient.ble.AgentConnection
import dev.warsha.remoteble.androidclient.ble.PeripheralSession
import dev.warsha.remoteble.androidclient.model.DiscoveredDevice
import dev.warsha.remoteble.androidclient.model.UiState
import dev.warsha.remoteble.androidclient.model.sortedNamedFirst
import dev.warsha.remoteble.androidclient.model.PairingOffer
import dev.warsha.remoteble.protocol.AgentFingerprint
import dev.warsha.remoteble.protocol.AgentPairing
import dev.warsha.remoteble.protocol.DeviceHandle
import com.juul.kable.DiscoveredCharacteristic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.uuid.ExperimentalUuidApi

/**
 * Orchestrates scanning, the agent link, and the active peripheral, exposing one
 * [UiState] for the UI to render.
 *
 * Platform-agnostic: takes its [CoroutineScope] as a constructor argument instead of owning one
 * itself (Android wraps this in a `ViewModel` and passes `viewModelScope`; iOS's
 * `MainViewController` builds its own `CoroutineScope(SupervisorJob() + Dispatchers.Main)`), and
 * exposes an explicit [close] instead of overriding `ViewModel.onCleared`.
 *
 * This class owns only the scanner-side state ([local]); the agent connection and the active
 * [PeripheralSession] each expose their own flow, and [uiState] is simply where the three are
 * [combine]d. Actions are thin: they drive the data layer and fold outcomes back into [local].
 *
 * [connectionFactory] is a seam for tests; production uses the default [AgentConnection].
 */
@OptIn(ExperimentalUuidApi::class, ExperimentalCoroutinesApi::class)
class RemoteBleController(
    parentScope: CoroutineScope,
    connectionFactory: (CoroutineScope) -> AgentConnection = ::AgentConnection,
) {

    /** The slice of state this controller itself owns; the device/agent state come from flows. */
    private data class Local(
        val agentUrl: String = UiState.DEFAULT_AGENT_URL,
        val agentToken: String = "",
        // The pinned identity of the agent paired with, if any; only a pairing sets it.
        val agentFingerprint: AgentFingerprint? = null,
        // A pairing taken in by paste or link, shown for confirmation before it replaces anything.
        val pendingPairing: AgentPairing? = null,
        val status: String = "Idle.",
        val isScanning: Boolean = false,
        val discovered: List<DiscoveredDevice> = emptyList(),
        val hideUnnamed: Boolean = true,
    )

    // Routes failures from flows launched into the scope to the status line instead of crashing.
    private val crashGuard = CoroutineExceptionHandler { _, e ->
        local.update { it.copy(status = "Error: ${e.message}") }
    }
    private val scope = CoroutineScope(parentScope.coroutineContext + crashGuard)

    private val agent = connectionFactory(scope)

    private val local = MutableStateFlow(Local())
    private val active = MutableStateFlow<PeripheralSession?>(null)
    private var scanJob: Job? = null
    private var connectJob: Job? = null
    // A replacement/idle close must complete before the next agent operation can start.
    private var retirement: Job? = null

    val uiState: StateFlow<UiState> = combine(
        local,
        agent.state,
        active.flatMapLatest { it?.state ?: flowOf(null) },
    ) { local, agentState, device ->
        UiState(
            agentUrl = local.agentUrl,
            agentToken = local.agentToken,
            agentFingerprint = local.agentFingerprint?.toString(),
            pendingPairing = local.pendingPairing?.let(PairingOffer::of),
            agentState = agentState,
            isScanning = local.isScanning,
            status = local.status,
            // See sortedNamedFirst: named-first grouping, first-seen order within each group.
            // Re-sorting by the constantly-changing RSSI would make the whole list churn on
            // every advertisement, so RSSI still only updates in place per device.
            discovered = local.discovered.sortedNamedFirst(local.hideUnnamed),
            hideUnnamed = local.hideUnnamed,
            device = device,
        )
    }.stateIn(scope, SharingStarted.Eagerly, UiState())

    fun updateUrl(url: String) {
        // The pin belongs to the agent that was paired; an address typed by hand may be another.
        local.update { it.copy(agentUrl = url, agentFingerprint = it.agentFingerprint.takeIf { _ -> url == it.agentUrl }) }
    }

    /**
     * Takes in a pairing link, pasted or opened from a QR code, and holds it for [confirmPairing]:
     * a link can come from anywhere, so nothing changes until the user has seen where it points.
     * Returns whether [text] was a pairing at all.
     */
    fun offerPairing(text: String): Boolean {
        val pairing = runCatching { AgentPairing.parse(text) }.getOrElse { failure ->
            local.update { it.copy(status = "Not a pairing link: ${failure.message}") }
            return false
        }
        local.update { it.copy(pendingPairing = pairing) }
        return true
    }

    /** Applies the offered pairing: its address, token and pin replace the current ones. */
    fun confirmPairing() {
        val pairing = local.value.pendingPairing ?: return
        val oldScan = scanJob.also { it?.cancel() }
        scanJob = null
        val oldConnect = connectJob.also { it?.cancel() }
        connectJob = null
        val oldDevice = active.value
        active.value = null
        oldDevice?.close()
        local.update {
            it.copy(
                discovered = emptyList(),
                agentUrl = pairing.url,
                agentToken = pairing.token.orEmpty(),
                agentFingerprint = pairing.fingerprint,
                pendingPairing = null,
                isScanning = false,
                status = "Paired with ${pairing.host}:${pairing.port}" + if (pairing.encrypted) ", encrypted." else ", not encrypted.",
            )
        }
        // The next connection is a different agent, or the same one pinned now; never reuse the old.
        retireAgent {
            oldScan?.join()
            oldConnect?.join()
            oldDevice?.disconnect()
        }
    }

    private fun retireAgent(beforeClose: suspend () -> Unit = {}) {
        val previous = retirement
        retirement = scope.launch {
            previous?.join()
            try {
                beforeClose()
            } finally {
                agent.close()
            }
        }
    }

    fun dismissPairing() {
        local.update { it.copy(pendingPairing = null) }
    }

    fun updateToken(token: String) {
        local.update { it.copy(agentToken = token) }
    }

    fun setHideUnnamed(hide: Boolean) {
        local.update { it.copy(hideUnnamed = hide) }
    }

    fun startScan() {
        scanJob?.cancel()
        local.update { it.copy(isScanning = true, discovered = emptyList(), status = "Connecting to agent…") }
        val retiring = retirement
        scanJob = scope.launch {
            try {
                retiring?.join()
                val session = agent.connect(local.value.agentUrl, local.value.agentToken, local.value.agentFingerprint)
                local.update { it.copy(status = "Scanning via agent…") }
                agent.advertisements(session).collect { adv ->
                    val sighting = DiscoveredDevice.from(adv)
                    local.update { state ->
                        // Update an existing device in place (stable position) or append a new one.
                        val index = state.discovered.indexOfFirst { it.identifier == sighting.identifier }
                        val devices = if (index >= 0) {
                            state.discovered.toMutableList()
                                .also { it[index] = it[index].mergedWith(sighting) }
                        } else {
                            state.discovered + sighting
                        }
                        state.copy(discovered = devices, status = "Scanning — ${devices.size} device(s) found")
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                local.update { it.copy(isScanning = false, status = "Scan failed: ${t.message}") }
            }
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        scanJob = null
        local.update { it.copy(isScanning = false, status = "Scan stopped.") }
        // Release the socket when fully idle; keep it alive while a device is connected.
        if (active.value == null) retireAgent()
    }

    fun connectDevice(handle: DeviceHandle, name: String?) {
        scanJob?.cancel()
        scanJob = null
        local.update { it.copy(isScanning = false) }
        connectJob?.cancel()
        val retiring = retirement
        connectJob = scope.launch {
            try {
                retiring?.join()
                val session = agent.connect(local.value.agentUrl, local.value.agentToken, local.value.agentFingerprint)
                active.value?.close()
                active.value = PeripheralSession(agent.peripheral(session, handle, name), handle, name, scope)
                local.update { it.copy(status = "Connecting to ${name ?: handle.value}…") }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                local.update { it.copy(status = "Connection failed: ${t.message}") }
            }
        }
    }

    fun disconnectDevice() {
        val session = active.value ?: return
        active.value = null
        local.update { it.copy(status = "Disconnected.") }
        scope.launch {
            session.disconnect()
            session.close()
        }
    }

    fun readCharacteristic(characteristic: DiscoveredCharacteristic) {
        active.value?.read(characteristic)
    }

    fun writeCharacteristic(characteristic: DiscoveredCharacteristic, value: ByteArray) {
        val session = active.value ?: return
        scope.launch {
            val message = session.write(characteristic, value).fold(
                onSuccess = { "Wrote ${value.size} byte(s) to ${characteristic.characteristicUuid}" },
                onFailure = { "Write failed: ${it.message}" },
            )
            local.update { it.copy(status = message) }
        }
    }

    fun toggleSubscription(characteristic: DiscoveredCharacteristic) {
        active.value?.toggleSubscription(characteristic)
    }

    /** Releases the socket and peripheral. Callers cancel [parentScope] (and thus [scope]) separately. */
    fun close() {
        scanJob?.cancel()
        connectJob?.cancel()
        active.value?.close()
        active.value = null
        retireAgent()
    }
}
