package dev.warsha.remoteble.agent

import dev.warsha.remoteble.agent.PeripheralRegistry.Acquisition
import dev.warsha.remoteble.protocol.AgentError
import dev.warsha.remoteble.protocol.ErrorKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class PeripheralRegistryTest {

    private val p = "FA:KE:01"
    private val a = "client-a"
    private val b = "client-b"

    @Test
    fun exclusivePeripheralIsOwnedByOneClient() = runTest {
        val registry = PeripheralRegistry(backgroundScope)

        assertIs<Acquisition.Granted>(registry.acquire(p, a))
        registry.onConnected(p, a)

        val denied = assertIs<Acquisition.Denied>(registry.acquire(p, b))
        assertEquals(a, denied.owner)
    }

    @Test
    fun authorizationRequiresTheOwningClientAndALiveConnection() = runTest {
        val registry = PeripheralRegistry(backgroundScope)

        assertIs<PeripheralRegistry.Authorization.NotConnected>(registry.authorizeConnected(p, a))
        registry.acquire(p, a)
        assertIs<PeripheralRegistry.Authorization.NotConnected>(registry.authorizeConnected(p, a))

        registry.onConnected(p, a)
        assertIs<PeripheralRegistry.Authorization.Granted>(registry.authorizeConnected(p, a))
        assertIs<PeripheralRegistry.Authorization.PeripheralBusy>(registry.authorizeConnected(p, b))
    }

    @Test
    fun bleDisconnectReleasesOnlyAfterLeaseGrace() = runTest {
        val released = mutableListOf<String>()
        val registry = PeripheralRegistry(backgroundScope, leaseGrace = 10.seconds, onRelease = { released += it })
        registry.acquire(p, a)
        registry.onConnected(p, a)
        registry.onDisconnected(p, a) // unsolicited peripheral drop

        advanceTimeBy(9.seconds)
        runCurrent()
        assertIs<Acquisition.Denied>(registry.acquire(p, b)) // still owned within the window

        advanceTimeBy(2.seconds)
        runCurrent()
        assertIs<Acquisition.Granted>(registry.acquire(p, b)) // freed after it elapses
        assertEquals(listOf(p), released)
    }

    @Test
    fun explicitDisconnectReleasesImmediatelyAndCannotResume() = runTest {
        val registry = PeripheralRegistry(backgroundScope, transportGrace = 10.seconds)
        registry.acquire(p, a)
        registry.onConnected(p, a)

        registry.releaseNow(p, a)

        assertIs<Acquisition.Granted>(registry.acquire(p, b))
        assertTrue(registry.heldBy(a).isEmpty())
    }

    @Test
    fun transportDropKeepsTheLinkWarmThenReleasesAfterTransportGrace() = runTest {
        val released = mutableListOf<String>()
        val registry = PeripheralRegistry(backgroundScope, transportGrace = 10.seconds, onRelease = { released += it })
        registry.acquire(p, a)
        registry.onConnected(p, a)

        registry.onTransportDropped(a) // WebSocket dropped
        runCurrent()

        // Within the window the lease is held and the radio link is NOT torn down.
        advanceTimeBy(9.seconds)
        runCurrent()
        assertIs<Acquisition.Denied>(registry.acquire(p, b))
        assertTrue(released.isEmpty())

        // After it elapses, the lease frees and the warm link is released.
        advanceTimeBy(2.seconds)
        runCurrent()
        assertIs<Acquisition.Granted>(registry.acquire(p, b))
        assertEquals(listOf(p), released)
    }

    @Test
    fun reconnectingWithinTransportGraceResumesTheLease() = runTest {
        val released = mutableListOf<String>()
        val registry = PeripheralRegistry(backgroundScope, transportGrace = 10.seconds, onRelease = { released += it })
        registry.acquire(p, a)
        registry.onConnected(p, a)
        registry.onTransportDropped(a)
        runCurrent()

        advanceTimeBy(5.seconds)
        registry.acquire(p, a) // same client reconnects: re-acquire cancels the pending release
        registry.onConnected(p, a)

        // Past the original deadline the lease survives and the link was never torn down.
        advanceTimeBy(20.seconds)
        runCurrent()
        assertIs<Acquisition.Denied>(registry.acquire(p, b))
        assertTrue(released.isEmpty())
    }

    @Test
    fun sharedModeConfigurationIsRejected() = runTest {
        val error = assertFailsWith<IllegalArgumentException> {
            PeripheralRegistry(backgroundScope, defaultExclusive = false)
        }
        assertTrue(error.message.orEmpty().contains("Shared peripheral mode"))
    }

    @Test
    fun unsolicitedDisconnectNotifiesTheRegisteredClientAndStartsGrace() = runTest {
        val registry = PeripheralRegistry(backgroundScope, leaseGrace = 10.seconds)
        registry.acquire(p, a)
        registry.onConnected(p, a)

        val notified = mutableListOf<String>()
        var receivedReason: AgentError? = null
        registry.registerClient(a) { handle, reason ->
            notified += handle
            receivedReason = reason
        }

        val reason = AgentError(ErrorKind.DISCONNECTED, message = "peer disconnected")
        registry.onUnsolicitedDisconnect(p, a, reason)

        assertEquals(listOf(p), notified)
        assertEquals(reason, receivedReason, "the drop's reason must reach the client notifier")
        advanceTimeBy(11.seconds)
        runCurrent()
        assertIs<Acquisition.Granted>(registry.acquire(p, b)) // grace elapsed, same as onDisconnected
    }

    @Test
    fun unsolicitedDisconnectIsSilentWithoutARegisteredClient() = runTest {
        // No registerClient call — must not throw or otherwise misbehave; nothing to notify.
        val registry = PeripheralRegistry(backgroundScope)
        registry.acquire(p, a)
        registry.onConnected(p, a)

        registry.onUnsolicitedDisconnect(p, a)
    }

    @Test
    fun unregisterIgnoresAStaleCallbackAfterAReconnectRegisteredAFreshOne() = runTest {
        // Simulates the race: client a's old BleAgent tears down (unregisters) after a reconnect
        // has already registered a new callback under the same clientKey. The old connection's
        // teardown must not clobber the new registration.
        val registry = PeripheralRegistry(backgroundScope)
        val oldNotified = mutableListOf<String>()
        val newNotified = mutableListOf<String>()
        val oldCallback: suspend (String, AgentError?) -> Unit = { handle, _ -> oldNotified += handle }
        val newCallback: suspend (String, AgentError?) -> Unit = { handle, _ -> newNotified += handle }

        registry.registerClient(a, oldCallback)
        registry.registerClient(a, newCallback) // reconnect's fresh BleAgent registers first
        registry.unregisterClient(a, oldCallback) // old connection's delayed teardown arrives after
        runCurrent()

        registry.acquire(p, a)
        registry.onConnected(p, a)
        registry.onUnsolicitedDisconnect(p, a)

        assertEquals(listOf(p), newNotified, "the current registration must still fire")
        assertTrue(oldNotified.isEmpty(), "a stale unregister must not remove the newer registration")
    }

    @Test
    fun connectCommittingWhileLiveMarksTheLeaseConnected() = runTest {
        val registry = PeripheralRegistry(backgroundScope)
        registry.acquire(p, a)

        assertTrue(registry.onConnected(p, a) { true })
        assertIs<Acquisition.Denied>(registry.acquire(p, b)) // a owns a live, connected lease
    }

    @Test
    fun connectCommittingAfterRetirementLeavesTheLeaseToGraceInsteadOfResurrectingIt() = runTest {
        // The exact race the connectionLive guard closes: a slow connect completes *after* the
        // transport was retired and its lease grace scheduled. It must not mark the lease connected
        // and cancel that grace (which would leak the lease with no release timer) — it must leave
        // the pending release intact so the lease is freed on expiry.
        val registry = PeripheralRegistry(backgroundScope, transportGrace = 5.seconds)
        assertIs<Acquisition.Granted>(registry.acquire(p, a))
        registry.onTransportDropped(a)
        runCurrent() // let the transport-drop coroutine schedule the grace release

        assertFalse(registry.onConnected(p, a) { false })

        advanceTimeBy(6.seconds)
        runCurrent()
        assertIs<Acquisition.Granted>(registry.acquire(p, b)) // released by grace, not stuck connected
    }

    @Test
    fun theSlotCapIsHostWideRatherThanPerClient() = runTest {
        val registry = PeripheralRegistry(backgroundScope, maxSlots = 2)

        assertIs<Acquisition.Granted>(registry.acquire("dev-1", a))
        assertIs<Acquisition.Granted>(registry.acquire("dev-2", b))
        // Two different clients have exhausted the host radio's capacity between them, which the
        // per-session cap this replaced could not express.
        assertIs<Acquisition.NoSlot>(registry.acquire("dev-3", a))

        registry.releaseNow("dev-1", a)
        assertIs<Acquisition.Granted>(registry.acquire("dev-3", a))
    }

    @Test
    fun reAcquiringAnOwnedLeaseConsumesNoFurtherSlot() = runTest {
        val registry = PeripheralRegistry(backgroundScope, maxSlots = 1)

        assertIs<Acquisition.Granted>(registry.acquire(p, a))
        assertIs<Acquisition.Granted>(registry.acquire(p, a)) // the resume path, not a new lease
        assertEquals(1, registry.occupiedSlots.value)
    }

    @Test
    fun occupancyCountsALeaseUntilItsGraceExpires() = runTest {
        val registry = PeripheralRegistry(backgroundScope, transportGrace = 10.seconds, maxSlots = 4)
        registry.acquire(p, a)
        registry.onConnected(p, a)
        assertEquals(1, registry.occupiedSlots.value)

        registry.onTransportDropped(a)
        runCurrent()
        // The client is gone but its link is warm and the peripheral is nobody else's: reporting
        // this slot as free is what would mislead a client whose next process is about to resume.
        assertEquals(1, registry.occupiedSlots.value)

        advanceTimeBy(11.seconds)
        runCurrent()
        assertEquals(0, registry.occupiedSlots.value)
    }
}
