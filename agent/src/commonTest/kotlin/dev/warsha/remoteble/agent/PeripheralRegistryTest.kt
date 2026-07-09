package dev.warsha.remoteble.agent

import dev.warsha.remoteble.agent.PeripheralRegistry.Acquisition
import dev.warsha.remoteble.protocol.AgentError
import dev.warsha.remoteble.protocol.ErrorKind
import kotlin.test.Test
import kotlin.test.assertEquals
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
    fun bleDisconnectReleasesOnlyAfterLeaseGrace() = runTest {
        val released = mutableListOf<String>()
        val registry = PeripheralRegistry(backgroundScope, leaseGrace = 10.seconds, onRelease = { released += it })
        registry.acquire(p, a)
        registry.onConnected(p, a)
        registry.onDisconnected(p, a) // peripheral dropped / explicit disconnect

        advanceTimeBy(9.seconds)
        runCurrent()
        assertIs<Acquisition.Denied>(registry.acquire(p, b)) // still owned within the window

        advanceTimeBy(2.seconds)
        runCurrent()
        assertIs<Acquisition.Granted>(registry.acquire(p, b)) // freed after it elapses
        assertEquals(listOf(p), released)
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
    fun nonExclusivePeripheralGrantsEveryone() = runTest {
        val registry = PeripheralRegistry(backgroundScope, defaultExclusive = false)
        registry.acquire(p, a)
        registry.onConnected(p, a)

        assertIs<Acquisition.Granted>(registry.acquire(p, b))
    }

    @Test
    fun switchingToSharedOpensAHeldPeripheral() = runTest {
        val registry = PeripheralRegistry(backgroundScope) // exclusive by default
        registry.acquire(p, a)
        registry.onConnected(p, a)
        assertIs<Acquisition.Denied>(registry.acquire(p, b))

        registry.setExclusive(p, false) // operator opens it
        assertIs<Acquisition.Granted>(registry.acquire(p, b))
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
}
