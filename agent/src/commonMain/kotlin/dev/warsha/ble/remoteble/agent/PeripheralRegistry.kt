package dev.warsha.ble.remoteble.agent

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
 *  - **BLE disconnect** (explicit or unsolicited): release after [leaseGrace]; the link is
 *    already down, so this just frees ownership.
 *  - **Transport (WebSocket) drop**: release after [transportGrace]; the radio link is left
 *    **warm**, so a quick reconnect resumes with no re-pair/rediscover. On expiry [onRelease]
 *    tears the warm link down.
 *
 * Exclusivity is switchable per peripheral (operator-controlled; clients cannot change it),
 * defaulting to [defaultExclusive]. Engine-free and pure: timers run on [scope] so it
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
    private val mutex = Mutex()
    private val leases = mutableMapOf<String, Lease>()
    private val exclusiveOverride = mutableMapOf<String, Boolean>()
    // clientKey -> how to push a disconnect notification to that client's live connection.
    // Registered by BleAgent.start(); a reconnect's fresh registration simply replaces the old.
    private val clientNotifiers = mutableMapOf<String, suspend (handle: String) -> Unit>()

    private class Lease(val owner: String, var connected: Boolean, var graceJob: Job?)

    sealed interface Acquisition {
        /** This client now owns (or already owned) the peripheral. */
        data object Granted : Acquisition

        /** The peripheral is exclusively owned by [owner] (a stable client id). */
        data class Denied(val owner: String) : Acquisition
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
     * client, or non-exclusive; otherwise denies with the current owner. Re-acquiring as the
     * owner cancels any pending release (this is how a reconnecting client resumes its lease).
     */
    suspend fun acquire(handle: String, clientKey: String): Acquisition = mutex.withLock {
        val lease = leases[handle]
        when {
            lease == null -> {
                leases[handle] = Lease(clientKey, connected = false, graceJob = null)
                Acquisition.Granted
            }
            lease.owner == clientKey -> {
                lease.cancelGrace()
                Acquisition.Granted
            }
            !exclusiveFor(handle) -> Acquisition.Granted // shared: ownership is not enforced
            else -> Acquisition.Denied(lease.owner)
        }
    }

    /** Marks [handle] physically connected under [clientKey]; cancels any pending release. */
    suspend fun onConnected(handle: String, clientKey: String) = mutex.withLock {
        leases[handle]?.takeIf { it.owner == clientKey }?.let {
            it.connected = true
            it.cancelGrace()
        }
        Unit
    }

    /**
     * Marks [handle] BLE-disconnected under [clientKey] and schedules release after [leaseGrace].
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
    suspend fun registerClient(clientKey: String, onDisconnect: suspend (handle: String) -> Unit) =
        mutex.withLock { clientNotifiers[clientKey] = onDisconnect }

    /**
     * Undoes [registerClient], but only if [onDisconnect] is still the current registration for
     * [clientKey] — a reconnect's newer [BleAgent] may have already registered its own by the
     * time the old connection's teardown gets here, and that must not be clobbered. Non-suspend
     * (called from [Job.invokeOnCompletion], which isn't a suspend context) mirrors
     * [onTransportDropped]'s own launch-internally pattern.
     */
    fun unregisterClient(clientKey: String, onDisconnect: suspend (handle: String) -> Unit) {
        scope.launch {
            mutex.withLock {
                if (clientNotifiers[clientKey] === onDisconnect) clientNotifiers.remove(clientKey)
            }
        }
    }

    /**
     * An unsolicited BLE drop — [ConnectionWatcher]'s cached-state or active-liveness checks,
     * never an explicit `Disconnect` op (that already emits its own event). Starts the same
     * release grace as [onDisconnected], then — unlike that method — also pushes a disconnect
     * notification to the owning client's live connection if one is registered, since nothing
     * else tells it this happened.
     *
     * An explicit `Disconnect` racing the watcher can make both this and the op's own path emit
     * `ConnectionState(DISCONNECTED)` for the same handle. That's tolerated by design: the window
     * is narrow ([onDisconnected] flips `connected` under the mutex, so the watcher skips it on the
     * next tick) and the client SDK treats a repeat DISCONNECTED as idempotent.
     */
    suspend fun onUnsolicitedDisconnect(handle: String, clientKey: String) {
        onDisconnected(handle, clientKey)
        val notify = mutex.withLock { clientNotifiers[clientKey] }
        notify?.invoke(handle)
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

    /** Operator switch: open ([exclusive] = false) or block a peripheral to multiple clients. */
    suspend fun setExclusive(handle: String, exclusive: Boolean) = mutex.withLock {
        exclusiveOverride[handle] = exclusive
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
                exclusive = exclusiveFor(handle),
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
            if (released) runCatchingNonCancellation { onRelease(handle) } // best-effort warm-link teardown
        }
    }

    // Caller must hold [mutex].
    private fun exclusiveFor(handle: String): Boolean = exclusiveOverride[handle] ?: defaultExclusive

    private fun Lease.cancelGrace() {
        graceJob?.cancel()
        graceJob = null
    }
}
