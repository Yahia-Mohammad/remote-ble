package dev.warsha.remoteble.agent

import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Wall-clock milliseconds since epoch — `System.currentTimeMillis()` has no Kotlin/Native equivalent. */
@OptIn(ExperimentalTime::class)
private fun epochMillis(): Long = Clock.System.now().toEpochMilliseconds()

/**
 * Live, in-memory snapshot of what the agent is doing, fed by [AgentObserver] hooks
 * (devices) and the WebSocket server (clients). Serves the status dashboard's
 * `/api/state`, and — via [snapshot] — the Compose status UI on Android/iOS (which polls
 * it directly in-process instead of over HTTP). Thread-safe: every mutation happens under
 * [lock] and every read takes a consistent snapshot the same way.
 */
class AgentMonitor : AgentObserver {

    private val lock = SynchronizedObject()

    /** When this agent process began serving, as epoch milliseconds. Also the base of [uptimeMs]. */
    val startedAtMs: Long = epochMillis()

    /** How long this agent has been serving. What `agent.status` reports. */
    fun uptimeMs(): Long = epochMillis() - startedAtMs

    /** How many client sessions are connected right now, across every principal. */
    fun connectedClients(): Int = synchronized(lock) { clients.size }

    /** The last advertised name seen for [handle], or null if the agent has never scanned it. */
    fun nameOf(handle: String): String? = synchronized(lock) { names[handle] }
    private val clients = LinkedHashMap<Long, ClientRow>()
    private val devices = LinkedHashMap<String, DeviceRow>() // handle -> row (connected only)
    private val names = HashMap<String, String>()            // handle -> last-seen advertised name
    private val logs = ArrayDeque<LogEntry>()
    private var nextLogId = 0L

    // --- server-driven (client lifecycle) ---

    fun clientConnected(id: Long, address: String) = synchronized(lock) {
        clients[id] = ClientRow(id, address, epochMillis())
        append("client #$id connected from $address")
    }

    fun clientDisconnected(id: Long): Unit = synchronized(lock) {
        clients.remove(id)
        // Drop hardware this client had connected — it's no longer owned by a live session.
        val dropped = devices.values.filter { it.ownerId == id }.map { it.handle }
        dropped.forEach { devices.remove(it) }
        append("client #$id disconnected" + if (dropped.isNotEmpty()) " (released ${dropped.size} device(s))" else "")
    }

    // --- AgentObserver (device lifecycle, from BleAgent) ---

    override fun onClientLog(clientId: Long, message: String) = synchronized(lock) {
        append("client #$clientId: $message")
    }

    override fun onDeviceConnected(clientId: Long, handle: String) = synchronized(lock) {
        devices[handle] = DeviceRow(handle, names[handle], clientId, epochMillis())
    }

    override fun onDeviceDisconnected(clientId: Long, handle: String): Unit = synchronized(lock) {
        devices.remove(handle)
    }

    override fun onDeviceSeen(handle: String, name: String?) {
        if (name.isNullOrBlank()) return
        synchronized(lock) {
            names[handle] = name
            devices[handle]?.let { devices[handle] = it.copy(name = name) } // relabel if connected
        }
    }

    /**
     * A consistent snapshot. [leases] is the registry's authoritative ownership view (owner,
     * exclusive flag, in-grace), labelled here with last-seen names; [settings] are the
     * process-level ownership knobs (grace windows, default exclusivity).
     */
    fun snapshot(
        leases: List<PeripheralRegistry.LeaseInfo> = emptyList(),
        settings: PeripheralRegistry.Settings? = null,
    ): Snapshot = synchronized(lock) {
        Snapshot(
            nowMs = epochMillis(),
            startedAtMs = startedAtMs,
            clients = clients.values.map { ClientDto(it.id, it.address, it.connectedAtMs) },
            devices = devices.values.map { DeviceDto(it.handle, it.name, it.ownerId, it.connectedAtMs) },
            leases = leases.map { LeaseDto(it.handle, names[it.handle], it.owner, it.connected, it.inGrace, it.exclusive) },
            settings = settings?.let { SettingsDto(it.leaseGraceMs, it.transportGraceMs, it.defaultExclusive) },
            logs = logs.toList(),
        )
    }

    /** [snapshot] pre-encoded as JSON — what the HTML dashboard's `/api/state` serves. */
    fun snapshotJson(
        leases: List<PeripheralRegistry.LeaseInfo> = emptyList(),
        settings: PeripheralRegistry.Settings? = null,
    ): String = json.encodeToString(snapshot(leases, settings))

    private fun append(message: String) {
        logs.addLast(LogEntry(nextLogId++, epochMillis(), message))
        while (logs.size > MAX_LOGS) logs.removeFirst()
    }

    private data class ClientRow(val id: Long, val address: String, val connectedAtMs: Long)
    private data class DeviceRow(val handle: String, val name: String?, val ownerId: Long, val connectedAtMs: Long)

    @Serializable data class Snapshot(
        val nowMs: Long,
        val startedAtMs: Long,
        val clients: List<ClientDto>,
        val devices: List<DeviceDto>,
        val leases: List<LeaseDto>,
        val settings: SettingsDto?,
        val logs: List<LogEntry>,
    )

    @Serializable data class ClientDto(val id: Long, val address: String, val connectedAtMs: Long)
    @Serializable data class DeviceDto(val handle: String, val name: String?, val ownerId: Long, val connectedAtMs: Long)
    @Serializable data class LeaseDto(
        val handle: String,
        val name: String?,
        val owner: String,
        val connected: Boolean,
        val inGrace: Boolean,
        val exclusive: Boolean,
    )
    @Serializable data class SettingsDto(
        val leaseGraceMs: Long,
        val transportGraceMs: Long,
        val defaultExclusive: Boolean,
    )
    @Serializable data class LogEntry(val id: Long, val atMs: Long, val message: String)

    private companion object {
        const val MAX_LOGS = 500
        val json = Json { encodeDefaults = true }
    }
}
