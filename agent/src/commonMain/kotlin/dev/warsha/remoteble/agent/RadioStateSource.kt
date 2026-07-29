package dev.warsha.remoteble.agent

import dev.warsha.remoteble.protocol.BleRadioState
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow

/**
 * This host's Bluetooth radio state, or `null` where the platform gives the agent no way to observe
 * it. Non-null is what makes [EngineBleBackend] advertise `radio.state`, so returning a constant
 * would be worse than returning `null`: it would promise a signal that never moves.
 *
 * - Android → `BluetoothAdapter.isEnabled` seeded, then `ACTION_STATE_CHANGED` broadcasts.
 * - iOS → `CBCentralManager.state` via its delegate.
 * - JVM → `null`. Kable's btleplug backend surfaces no adapter-state API, and the underlying
 *   BlueZ/CoreBluetooth adapter events are not exposed through it. Rig D is the place to revisit
 *   this on Linux, where BlueZ's D-Bus `PropertiesChanged` would give a real signal if we ever
 *   went around Kable.
 *
 * Deliberately **not** built on Kable's own `Bluetooth.availability`: as of Kable 0.43.1 that API
 * is `@Deprecated` — *"has inconsistent behavior across platforms. Will be removed in a future
 * release"* (JuulLabs/kable#737) — so it is neither a stable nor a trustworthy foundation for a
 * signal whose whole purpose is to be believed.
 *
 * [scope] bounds the underlying platform subscription (a `BroadcastReceiver`, a
 * `CBCentralManager`); it is the agent-lifetime scope, so the registration dies with the agent.
 */
internal expect fun agentRadioStateSource(scope: CoroutineScope): StateFlow<BleRadioState>?

/**
 * The one radio observer per process, shared by the backend and the agent UI.
 *
 * Both need it and their lifetimes don't nest: the backend exists only while the agent is
 * *running*, while the UI wants to warn that Bluetooth is off precisely when it is **not** running
 * — that being the moment a user is about to press Start and wonder why nothing is found. Creating
 * one source per consumer would mean two `CBCentralManager`s and two `BroadcastReceiver`s
 * observing a single radio.
 *
 * Owns a process-lifetime scope rather than borrowing a caller's, so the first caller cannot
 * accidentally decide when the shared observer dies — a composition scope would take the
 * subscription down on the next recomposition that drops it.
 */
internal object AgentRadio {
    private val lock = SynchronizedObject()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var cached: StateFlow<BleRadioState>? = null

    /**
     * `null` on platforms with no observable radio state — see [agentRadioStateSource].
     *
     * Only a *successful* resolution is memoised. Android's needs [androidAgentContext] to have
     * been set, so caching a null would silently make "whoever asked first" a permanent answer and
     * turn the launcher's init ordering into a load-bearing invariant; retrying instead costs one
     * null check per call on the platforms that will never have a source.
     */
    fun source(): StateFlow<BleRadioState>? = synchronized(lock) {
        cached ?: agentRadioStateSource(scope)?.also { cached = it }
    }
}
