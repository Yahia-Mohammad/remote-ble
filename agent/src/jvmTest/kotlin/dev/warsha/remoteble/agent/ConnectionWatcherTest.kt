package dev.warsha.remoteble.agent

import dev.warsha.remoteble.protocol.AgentError
import dev.warsha.remoteble.protocol.DeviceHandle
import dev.warsha.remoteble.protocol.ErrorKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * Guards the unsolicited-drop detector. The watcher polls [BleBackend.isConnected] every tick; a
 * backend that doesn't implement it (the interface default returns `false`) would make the
 * watcher tear down every healthy lease — exactly the regression the first two tests exercise.
 * The third test guards the slower [BleBackend.checkLiveness] probe, which catches a link the
 * cached state still calls "connected" but that's actually dead.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionWatcherTest {

    private val handle = "FA:KE:01"
    private val owner = "client-a"
    private val tick = 50.milliseconds

    @Test
    fun unsolicitedDropStartsTheReleaseGrace() = runTest {
        val backend = FakeBleBackend()
        // Leased + marked connected by the registry, but the radio is NOT up in the backend
        // (no connect call) — i.e. an unsolicited drop the registry hasn't heard about yet.
        val registry = PeripheralRegistry(backgroundScope, leaseGrace = 10.seconds)
        registry.acquire(handle, owner)
        registry.onConnected(handle, owner)

        ConnectionWatcher(registry, backend, backgroundScope, interval = tick).start()
        advanceTimeBy(tick * 2)
        runCurrent()

        val lease = registry.snapshot().single { it.handle == handle }
        assertFalse(lease.connected, "watcher should mark the dropped peripheral disconnected")
        assertTrue(lease.inGrace, "watcher should have started the release grace")
    }

    @Test
    fun stillConnectedPeripheralIsLeftAlone() = runTest {
        val backend = FakeBleBackend()
        backend.connect(DeviceHandle(handle)) // radio is up: isConnected == true
        val registry = PeripheralRegistry(backgroundScope, leaseGrace = 10.seconds)
        registry.acquire(handle, owner)
        registry.onConnected(handle, owner)

        ConnectionWatcher(registry, backend, backgroundScope, interval = tick).start()
        advanceTimeBy(tick * 4)
        runCurrent()

        val lease = registry.snapshot().single { it.handle == handle }
        assertTrue(lease.connected, "a connected peripheral must not be disturbed")
        assertFalse(lease.inGrace, "no release grace should be scheduled for a live link")
        assertEquals(emptyList(), backend.disconnectCalls)
    }

    @Test
    fun deepLivenessProbeCatchesAStaleConnectedState() = runTest {
        val backend = FakeBleBackend()
        backend.connect(DeviceHandle(handle)) // isConnected() reports true throughout
        val registry = PeripheralRegistry(backgroundScope, leaseGrace = 10.seconds)
        registry.acquire(handle, owner)
        registry.onConnected(handle, owner)

        // livenessInterval = 3 ticks: the fast per-tick check (isConnected, always true here)
        // alone would never flag this — only the periodic active probe (checkLiveness) does.
        ConnectionWatcher(registry, backend, backgroundScope, interval = tick, livenessInterval = tick * 3).start()

        advanceTimeBy(tick * 2)
        runCurrent()
        assertTrue(
            registry.snapshot().single { it.handle == handle }.connected,
            "not yet due for the deep probe; the cached state alone must not disconnect it",
        )

        // The link is actually dead, but only an active probe (not the cached state) would know.
        backend.livenessOverride = false
        advanceTimeBy(tick * 2) // crosses the 3rd tick, where the deep probe runs
        runCurrent()
        assertTrue(
            registry.snapshot().single { it.handle == handle }.connected,
            "one failed probe is not enough — see LIVENESS_FAILURES_BEFORE_DROP",
        )

        advanceTimeBy(tick * 3) // the second consecutive deep-probe failure
        runCurrent()

        val lease = registry.snapshot().single { it.handle == handle }
        assertFalse(lease.connected, "the deep liveness probe should catch a stale 'connected' link")
        assertTrue(lease.inGrace, "watcher should have started the release grace")
    }

    /**
     * Regression (Rig A case 3, 2026-07-28): a *single* failed deep probe must not tear the
     * connection down. A probe read of an encrypted characteristic blocked on a macOS pairing
     * dialog, timed out, and the watchdog declared a drop on a link that was perfectly healthy.
     * The probe cannot dodge this by picking a safer characteristic — encryption is a GATT security
     * permission, not a property bit — so the guard has to be "confirm it twice".
     */
    @Test
    fun oneFailedDeepProbeDoesNotDeclareADropAndRecoveryClearsIt() = runTest {
        val backend = FakeBleBackend()
        backend.connect(DeviceHandle(handle))
        val registry = PeripheralRegistry(backgroundScope, leaseGrace = 10.seconds)
        registry.acquire(handle, owner)
        registry.onConnected(handle, owner)
        ConnectionWatcher(registry, backend, backgroundScope, interval = tick, livenessInterval = tick * 3).start()

        // One stalled probe — a pairing dialog, a busy stack, a slow round trip.
        backend.livenessOverride = false
        advanceTimeBy(tick * 3)
        runCurrent()
        assertTrue(
            registry.snapshot().single { it.handle == handle }.connected,
            "a single stalled probe must not disconnect a live peripheral",
        )

        // It answers again: the counter resets, so a later isolated failure still won't drop it.
        backend.livenessOverride = null
        advanceTimeBy(tick * 3)
        runCurrent()
        backend.livenessOverride = false
        advanceTimeBy(tick * 3)
        runCurrent()

        val lease = registry.snapshot().single { it.handle == handle }
        assertTrue(lease.connected, "a recovered probe must reset the consecutive-failure count")
        assertFalse(lease.inGrace)
    }

    @Test
    fun nativeDropIsForwardedToTheRegistryWithReasonWithoutWaitingForAPoll() = runTest {
        val backend = FakeBleBackend()
        backend.connect(DeviceHandle(handle)) // radio reports connected: the poll loop alone won't flag it
        val registry = PeripheralRegistry(backgroundScope, leaseGrace = 10.seconds)
        registry.acquire(handle, owner)
        registry.onConnected(handle, owner)

        val notified = mutableListOf<Pair<String, AgentError?>>()
        registry.registerClient(owner) { h, r -> notified += h to r }

        ConnectionWatcher(registry, backend, backgroundScope, interval = tick).start()
        runCurrent() // let the native-drop collector subscribe before we emit

        // The backend detects the drop natively and pushes it with a reason — no poll tick elapses.
        val reason = AgentError(ErrorKind.DISCONNECTED, message = "peer disconnected")
        backend.connectionDropSignals.emit(ConnectionDrop(DeviceHandle(handle), reason))
        runCurrent()

        assertEquals(listOf<Pair<String, AgentError?>>(handle to reason), notified, "native drop must reach the client with its reason")
        val lease = registry.snapshot().single { it.handle == handle }
        assertFalse(lease.connected, "the native drop must mark the lease disconnected")
        assertTrue(lease.inGrace, "the native drop must start the release grace")
    }

    @Test
    fun aThrowingClientNotifierDoesNotKillTheWatcher() = runTest {
        val backend = FakeBleBackend()
        val registry = PeripheralRegistry(backgroundScope, leaseGrace = 10.seconds)
        // Models a client whose wire send fails (e.g. a socket already closing during the
        // transport-grace window): the registry invokes this to notify the client of the drop.
        registry.registerClient(owner) { _, _ -> error("send on closed socket") }

        // First dropped peripheral: leased + connected, but the radio is not up in the backend.
        registry.acquire(handle, owner)
        registry.onConnected(handle, owner)

        ConnectionWatcher(registry, backend, backgroundScope, interval = tick).start()
        advanceTimeBy(tick * 2)
        runCurrent()

        // The drop is still recorded despite the notifier throwing (release grace runs first)...
        val lease = registry.snapshot().single { it.handle == handle }
        assertFalse(lease.connected, "the drop must still be recorded even though the notifier threw")
        assertTrue(lease.inGrace)

        // ...and, crucially, the watcher is still alive: a second dropped peripheral registered
        // after the throw is still detected. If the throw had killed the loop, this would fail.
        val handle2 = "FA:KE:02"
        registry.acquire(handle2, owner)
        registry.onConnected(handle2, owner)
        advanceTimeBy(tick * 2)
        runCurrent()

        val lease2 = registry.snapshot().single { it.handle == handle2 }
        assertFalse(lease2.connected, "watcher must keep detecting drops after a notifier threw")
        assertTrue(lease2.inGrace)
    }
}
