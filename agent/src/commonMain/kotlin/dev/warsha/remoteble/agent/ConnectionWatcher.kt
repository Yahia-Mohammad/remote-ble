package dev.warsha.remoteble.agent

import dev.warsha.remoteble.protocol.DeviceHandle
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Detects unsolicited BLE drops so the [PeripheralRegistry] can start its release grace even
 * when no client asked to disconnect (the peripheral powered off or went out of range).
 *
 * It ticks every [interval] and, for each leased+connected peripheral, checks if it is still
 * connected via [BleBackend.isConnected] — a cheap, cached-state check. Every [livenessInterval]
 * it instead runs [BleBackend.checkLiveness], an active probe: a peripheral that vanished
 * without a clean BLE-level teardown (crashed, force-stopped, walked out of range) can leave
 * [isConnected] reporting `true` until an LL supervision timeout that's tens of seconds or
 * effectively unbounded, so the fast per-[interval] check alone would never catch it. Either way,
 * once a drop is found [PeripheralRegistry.onUnsolicitedDisconnect] both starts the release grace
 * and — since nothing else would — tells the owning client's live connection about it.
 */
class ConnectionWatcher(
    private val registry: PeripheralRegistry,
    private val backend: BleBackend,
    private val scope: CoroutineScope,
    private val interval: Duration = 1.seconds,
    private val livenessInterval: Duration = 15.seconds,
) {
    fun start(): Job = scope.launch {
        var sinceLiveness = Duration.ZERO
        while (isActive) {
            delay(interval)
            sinceLiveness += interval
            val deepCheck = sinceLiveness >= livenessInterval
            if (deepCheck) sinceLiveness = Duration.ZERO

            for (lease in registry.snapshot()) {
                if (!lease.connected) continue
                // This loop is the agent's single, shared liveness watchdog: one lease's probe or
                // disconnect handling must never be able to terminate it, or every other
                // peripheral would silently stop being monitored. Contain any failure per-lease
                // (rethrowing cancellation so the scope can still stop us).
                try {
                    val handle = DeviceHandle(lease.handle)
                    val alive = if (deepCheck) backend.checkLiveness(handle) else backend.isConnected(handle)
                    if (!alive) registry.onUnsolicitedDisconnect(lease.handle, lease.owner)
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    // Best-effort: skip this lease this tick and keep watching the rest.
                }
            }
        }
    }
}
