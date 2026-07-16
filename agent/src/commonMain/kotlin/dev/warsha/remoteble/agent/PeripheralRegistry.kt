package dev.warsha.remoteble.agent

import dev.warsha.remoteble.log.Logger
import dev.warsha.remoteble.protocol.AgentError
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Authoritative, cross-client ownership of peripherals. A single instance is shared by
 * every per-connection [BleAgent] (the radio is shared, so ownership must be too).
 *
 * BLE allows only one central↔peripheral link, so a peripheral is owned by **one** client at
 * a time when exclusive (the default). Ownership is keyed by a *stable client id* (sent on the
 * handshake — see `CLIENT_ID_HEADER`) so a client that drops and reconnects is recognised and
 * **resumes** its leases rather than colliding with itself.
 *
 * The lease model is uniform: anything that means "the owner is (temporarily) gone" schedules a
 * per-lease release timer; anything that means "the owner is back" cancels it. The only
 * difference between causes is the delay and whether the radio link is kept warm meanwhile:
 *  - **Unsolicited BLE disconnect**: release after [leaseGrace]; the link is already down, so a
 *    brief reconnect by the owner can retain ownership.
 *  - **Explicit disconnect**: release immediately; it is never resumable.
 *  - **Transport (WebSocket) drop**: release after [transportGrace]; the radio link is left
 *    **warm**, so a quick reconnect resumes with no re-pair/rediscover. On expiry [onRelease]
 *    tears the warm link down.
 *
 * The 0.9.0 release is exclusive-only. A multi-participant shared lease requires independent
 * participant, stream, and grace tracking, so configuration that attempts to enable it is rejected
 * rather than granting invisible guests. Engine-free and pure: timers run on [scope] so it
 * unit-tests with virtual time; the physical disconnect is the injected [onRelease] callback.
 *
 * [registerClient]/[onUnsolicitedDisconnect] exist because an unsolicited BLE drop is detected
 * out-of-band, by [ConnectionWatcher] — a component with no live connection to any particular
 * client. An explicit `Disconnect` op's [BleAgent] already knows to emit its own event; this is
 * the only path an *unsolicited* one reaches the owning client's wire connection.
 */
class PeripheralRegistry(
    private val scope: CoroutineScope,
    private val leaseGrace: Duration = 10.seconds,
    private val transportGrace: Duration = 10.seconds,
    private val defaultExclusive: Boolean = true,
    private val onRelease: suspend (handle: String) -> Unit = {},
) {
    init {
        require(defaultExclusive) {
            "Shared peripheral mode is unavailable in RemoteBLE 0.9.0; use exclusive ownership"
        }
    }

    private val mutex = Mutex()
    private val leases = mutableMapOf<String, Lease>()
    // clientKey -> how to push a disconnect notification to that client's live connection (with the
    // drop's reason, when known). Registered by BleAgent.start(); a reconnect's fresh registration
    // simply replaces the old.
    private val clientNotifiers =
        mutableMapOf<String, suspend (handle: String, reason: AgentError?) -> Unit>()

    private class Lease(val owner: String, var connected: Boolean, var graceJob: Job?)

    sealed interface Acquisition {
        /** This client now owns (or already owned) the peripheral. */
        data object Granted : Acquisition

        /** The peripheral is exclusively owned by [owner] (a stable client id). */
        data class Denied(val owner: String) : Acquisition
    }

    /**
     * Result of authorizing a device-bearing operation for one stable client identity.
     *
     * A handle is routing data, not an authorization credential: knowing a scanned handle must not
     * let another connection operate the peripheral. `Granted` means this client owns the lease and
     * the physical link is live; callers may then invoke the backend without holding [mutex].
     */
    sealed interface Authorization {
        data object Granted : Authorization

        /** Another client owns the live or grace-window lease. */
        data object PeripheralBusy : Authorization

        /** No lease exists for this client, or its BLE link is not currently connected. */
        data object NotConnected : Authorization
    }

    /** A consistent view of one peripheral's ownership, for the status dashboard. */
    data class LeaseInfo(
        val handle: String,
        val owner: String,
        val connected: Boolean,
        val inGrace: Boolean,
        val exclusive: Boolean,
    )

    /** Process-level configuration, surfaced read-only on the dashboard. */
    data class Settings(
        val leaseGraceMs: Long,
        val transportGraceMs: Long,
        val defaultExclusive: Boolean,
    )

    fun settings(): Settings = Settings(
        leaseGraceMs = leaseGrace.inWholeMilliseconds,
        transportGraceMs = transportGrace.inWholeMilliseconds,
        defaultExclusive = defaultExclusive,
    )

    /**
     * Reserves [handle] for [clientKey]. Grants if the peripheral is free, already owned by this
     * client; otherwise denies with the current owner. Re-acquiring as the
     * owner cancels any pending release (this is how a reconnecting client resumes its lease).
     */
    suspend fun acquire(handle: String, clientKey: String): Acquisition = mutex.withLock {
        val lease = leases[handle]
        when {
            lease == null -> {
                leases[handle] = Lease(clientKey, connected = false, graceJob = null)
                Logger.info(LogTags.REGISTRY) { "lease acquired [dev=$handle owner=$clientKey]" }
                Acquisition.Granted
            }
            lease.owner == clientKey -> {
                lease.cancelGrace()
                Logger.info(LogTags.REGISTRY) { "lease resumed [dev=$handle owner=$clientKey]" }
                Acquisition.Granted
            }
            else -> Acquisition.Denied(lease.owner)
        }
    }

    /**
     * Authorizes a device-bearing operation that requires an active BLE connection.
     *
     * This is deliberately a registry query rather than a per-[BleAgent] `connected` check: the
     * registry is the cross-client ownership authority. It also rejects the pre-0.9 shared-mode
     * "guest" path, whose participant lifecycle is not represented safely yet.
     */
    suspend fun authorizeConnected(handle: String, clientKey: String): Authorization = mutex.withLock {
        val lease = leases[handle] ?: return@withLock Authorization.NotConnected
        when {
            lease.owner != clientKey -> Authorization.PeripheralBusy
            !lease.connected -> Authorization.NotConnected
            else -> Authorization.Granted
        }
    }

    /** Marks [handle] physically connected under [clientKey]; cancels any pending release. */
    suspend fun onConnected(handle: String, clientKey: String) {
        onConnected(handle, clientKey) { true }
    }

    /**
     * Commits [handle] as physically connected under [clientKey] — but only while [stillLive] reports
     * the owning connection is live. A slow `connect()` can complete *after* its WebSocket was retired
     * and [onTransportDropped] scheduled the lease's transport grace; marking it connected and
     * cancelling that grace here would leak an abandoned link with no release timer (the race the
     * Rust agent guards with `connection_live`). When the connection is already retired this is a
     * no-op that returns false, deliberately leaving the lease to the transport-grace path — whose
     * [onRelease] tears the radio link down on the registry scope, so teardown survives the retiring
     * command coroutine's cancellation. The liveness check and the commit share the registry lock, so
     * a grace scheduled by [onTransportDropped] is either seen here (→ leave it) or scheduled after
     * this commit (→ warm lease, released on expiry). Returns true when the lease was committed live.
     */
    suspend fun onConnected(handle: String, clientKey: String, stillLive: () -> Boolean): Boolean = mutex.withLock {
        val lease = leases[handle]?.takeIf { it.owner == clientKey } ?: return@withLock false
        if (!stillLive()) return@withLock false
        lease.connected = true
        lease.cancelGrace()
        true
    }

    /**
     * Marks [handle] *unsolicitedly* BLE-disconnected under [clientKey] and schedules release after [leaseGrace].
     * Idempotent: a release already scheduled (e.g. an in-flight transport grace) is left as-is.
     */
    suspend fun onDisconnected(handle: String, clientKey: String) = mutex.withLock {
        val lease = leases[handle]?.takeIf { it.owner == clientKey } ?: return@withLock
        lease.connected = false
        scheduleRelease(handle, lease, leaseGrace)
    }

    /**
     * Registers how to reach [clientKey]'s live wire connection, for [onUnsolicitedDisconnect].
     * Called once from [BleAgent.start]; overwritten wholesale by a reconnect's fresh [BleAgent].
     */
    suspend fun registerClient(
        clientKey: String,
        onDisconnect: suspend (handle: String, reason: AgentError?) -> Unit,
    ) = mutex.withLock { clientNotifiers[clientKey] = onDisconnect }

    /**
     * Undoes [registerClient], but only if [onDisconnect] is still the current registration for
     * [clientKey] — a reconnect's newer [BleAgent] may have already registered its own by the
     * time the old connection's teardown gets here, and that must not be clobbered. Non-suspend
     * (called from [Job.invokeOnCompletion], which isn't a suspend context) mirrors
     * [onTransportDropped]'s own launch-internally pattern.
     */
    fun unregisterClient(
        clientKey: String,
        onDisconnect: suspend (handle: String, reason: AgentError?) -> Unit,
    ) {
        scope.launch {
            mutex.withLock {
                if (clientNotifiers[clientKey] === onDisconnect) clientNotifiers.remove(clientKey)
            }
        }
    }

    /** The [clientKey] currently leasing [handle], or `null` if it isn't leased. */
    suspend fun ownerOf(handle: String): String? = mutex.withLock { leases[handle]?.owner }

    /**
     * The real handles of every lease [clientKey] currently holds — live or in a grace window.
     * A reconnecting client's fresh connection uses this to re-seed its handle translations
     * (see `BleAgent.respondHello`): the warm leases are exactly the handles whose translated
     * forms the client may replay.
     */
    suspend fun heldBy(clientKey: String): Set<String> = mutex.withLock {
        leases.filterValues { it.owner == clientKey }.keys.toSet()
    }

    /**
     * An unsolicited BLE drop — reported by [ConnectionWatcher], either from the backend's native
     * [BleBackend.connectionDrops] stream (fast, carries a [reason]) or from its cached-state /
     * active-liveness polling (fallback). Never an explicit `Disconnect` op (that already emits its
     * own event). Starts the same release grace as [onDisconnected], then — unlike that method —
     * also pushes a disconnect notification (with [reason]) to the owning client's live connection
     * if one is registered, since nothing else tells it this happened.
     *
     * Two detectors (native stream + polling) or an explicit `Disconnect` racing them can make more
     * than one path emit `ConnectionState(DISCONNECTED)` for the same handle. That's tolerated by
     * design: the window is narrow ([onDisconnected] flips `connected` under the mutex, so the
     * watcher skips it on the next tick) and the client SDK treats a repeat DISCONNECTED as idempotent.
     */
    suspend fun onUnsolicitedDisconnect(handle: String, clientKey: String, reason: AgentError? = null) {
        onDisconnected(handle, clientKey)
        val notify = mutex.withLock { clientNotifiers[clientKey] }
        notify?.invoke(handle, reason)
    }

    /**
     * The client's transport (WebSocket) dropped. Keep its peripherals' radio links **warm** and
     * schedule each lease for release after [transportGrace] — a reconnect within the window
     * resumes (re-acquire cancels the timer); otherwise [onRelease] tears the warm link down.
     * Non-suspending so it can run from a job-completion callback; work runs on [scope].
     */
    fun onTransportDropped(clientKey: String) {
        scope.launch {
            mutex.withLock {
                leases.filterValues { it.owner == clientKey }
                    .forEach { (handle, lease) -> scheduleRelease(handle, lease, transportGrace) }
            }
        }
    }

    /** Drops [handle]'s lease at once if held by [clientKey] — e.g. a connect attempt failed. */
    suspend fun releaseNow(handle: String, clientKey: String) = mutex.withLock {
        leases[handle]?.takeIf { it.owner == clientKey }?.let {
            it.cancelGrace()
            leases.remove(handle)
        }
        Unit
    }

    /** A consistent snapshot of all current leases, for status/monitoring. */
    suspend fun snapshot(): List<LeaseInfo> = mutex.withLock {
        leases.map { (handle, lease) ->
            LeaseInfo(
                handle = handle,
                owner = lease.owner,
                connected = lease.connected,
                inGrace = lease.graceJob?.isActive == true,
                exclusive = true,
            )
        }
    }

    // Caller must hold [mutex]. Schedules a one-shot release unless one is already pending.
    private fun scheduleRelease(handle: String, lease: Lease, after: Duration) {
        if (lease.graceJob?.isActive == true) return
        lease.graceJob = scope.launch {
            delay(after)
            val released = mutex.withLock {
                // Only release if this is still the same lease (a re-acquire would have replaced
                // or cancelled it). Cancellation already pre-empts this on resume.
                if (leases[handle] === lease) {
                    leases.remove(handle)
                    true
                } else {
                    false
                }
            }
            if (released) {
                Logger.info(LogTags.REGISTRY) { "lease grace expired → released [dev=$handle]" }
                runCatchingNonCancellation { onRelease(handle) } // best-effort warm-link teardown
            }
        }
    }

    // Caller must hold [mutex].
    private fun Lease.cancelGrace() {
        graceJob?.cancel()
        graceJob = null
    }
}
