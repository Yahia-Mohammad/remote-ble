package dev.warsha.remoteble.agent

import dev.warsha.remoteble.log.Logger
import dev.warsha.remoteble.protocol.DeviceHandle
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
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
        // Native fast-path: forward drops the backend detects itself — promptly and with a reason —
        // running concurrently with the polling fallback below. Backends with no native signal
        // return an empty stream, leaving only the poll loop.
        launch { collectNativeDrops() }

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
                    if (!alive) {
                        Logger.warn(LogTags.WATCHER) {
                            "liveness probe failed [dev=${lease.handle} deepCheck=$deepCheck] — declaring unsolicited disconnect"
                        }
                        registry.onUnsolicitedDisconnect(lease.handle, lease.owner)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    Logger.warn(LogTags.WATCHER) { "probe tick failed for ${lease.handle}: ${t.message}" }
                }
            }
        }
    }

    /**
     * Forwards the backend's native [BleBackend.connectionDrops] to the registry. Resolves the
     * dropped handle's current owner (the native signal only knows the handle) and routes it through
     * the same [PeripheralRegistry.onUnsolicitedDisconnect] path as the poll loop — but immediately
     * and with the drop's [ConnectionDrop.reason]. Each drop is contained so one failure (or one
     * unleased handle) can't tear down the stream.
     */
    private suspend fun collectNativeDrops() {
        backend.connectionDrops().collect { drop ->
            try {
                val owner = registry.ownerOf(drop.device.value) ?: return@collect // not (any longer) leased
                registry.onUnsolicitedDisconnect(drop.device.value, owner, drop.reason)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Logger.warn(LogTags.WATCHER) { "native drop handler failed for ${drop.device.value}: ${t.message}" }
            }
        }
    }
}
