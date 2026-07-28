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
        // Consecutive failed *deep* probes per handle; see [LIVENESS_FAILURES_BEFORE_DROP].
        val deepFailures = mutableMapOf<String, Int>()
        while (isActive) {
            delay(interval)
            sinceLiveness += interval
            val deepCheck = sinceLiveness >= livenessInterval
            if (deepCheck) sinceLiveness = Duration.ZERO

            val leases = registry.snapshot()
            // Drop counters for handles no longer leased, so this can't grow with process lifetime.
            deepFailures.keys.retainAll(leases.mapTo(mutableSetOf()) { it.handle })

            for (lease in leases) {
                if (!lease.connected) continue
                // This loop is the agent's single, shared liveness watchdog: one lease's probe or
                // disconnect handling must never be able to terminate it, or every other
                // peripheral would silently stop being monitored. Contain any failure per-lease
                // (rethrowing cancellation so the scope can still stop us).
                try {
                    val handle = DeviceHandle(lease.handle)
                    val alive = if (deepCheck) backend.checkLiveness(handle) else backend.isConnected(handle)
                    when {
                        // Only a *deep* success clears the count. A shallow tick reads the platform's
                        // cached state, which is exactly what goes stale when a peripheral vanishes —
                        // letting it reset the counter would mean the deep probe could never reach
                        // the threshold, since shallow ticks run between every pair of deep ones.
                        alive && deepCheck -> deepFailures.remove(lease.handle)
                        alive -> Unit
                        // A cached-state check is the platform's own answer, not an I/O attempt: if
                        // it says disconnected, it is. Act immediately, as before.
                        !deepCheck -> {
                            Logger.warn(LogTags.WATCHER) {
                                "device reported disconnected [dev=${lease.handle}] — declaring unsolicited disconnect"
                            }
                            registry.onUnsolicitedDisconnect(lease.handle, lease.owner)
                        }
                        // A deep probe does real I/O, so a single failure is weak evidence: it can
                        // mean the peripheral is gone, or merely that this round trip did not come
                        // back in time. Confirmed on hardware (Rig A case 3, 2026-07-28) — a probe
                        // read of an encrypted characteristic blocked on a macOS pairing dialog,
                        // timed out, and tore down a perfectly healthy connection. Requiring
                        // consecutive failures costs one extra interval on a genuine drop, which the
                        // native `connectionDrops` stream usually reports long before this loop
                        // anyway (measured at 145ms on Rig A), and removes that whole class of
                        // false positive.
                        else -> {
                            val consecutive = (deepFailures[lease.handle] ?: 0) + 1
                            deepFailures[lease.handle] = consecutive
                            if (consecutive >= LIVENESS_FAILURES_BEFORE_DROP) {
                                Logger.warn(LogTags.WATCHER) {
                                    "liveness probe failed $consecutive time(s) in a row [dev=${lease.handle}] — declaring unsolicited disconnect"
                                }
                                deepFailures.remove(lease.handle)
                                registry.onUnsolicitedDisconnect(lease.handle, lease.owner)
                            } else {
                                Logger.info(LogTags.WATCHER) {
                                    "liveness probe failed [dev=${lease.handle}] ($consecutive/$LIVENESS_FAILURES_BEFORE_DROP) — not declaring a drop yet"
                                }
                            }
                        }
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

    companion object {
        /**
         * Consecutive failed *deep* liveness probes before a device is declared dropped.
         *
         * A deep probe is a real GATT round trip, so one failure does not distinguish "the
         * peripheral is gone" from "this round trip did not return in time". The second reading is
         * not hypothetical: on Rig A (2026-07-28) a probe read of an encrypted characteristic
         * blocked on a macOS pairing dialog until [EngineBleBackend.LIVENESS_PROBE_TIMEOUT] and the
         * watchdog tore down a healthy connection. The probe cannot avoid that by choosing a safer
         * characteristic — encryption is a GATT *security permission*, not a property bit, so it is
         * not visible in the discovered table.
         *
         * Two is deliberately the smallest value that removes single-stall false positives. The cost
         * is one extra `livenessInterval` before a genuine silent drop is declared; the backend's
         * native [BleBackend.connectionDrops] stream normally reports a real drop far sooner
         * (measured at 145ms on Rig A), so this loop is the backstop, not the primary detector.
         */
        const val LIVENESS_FAILURES_BEFORE_DROP = 2
    }
}
