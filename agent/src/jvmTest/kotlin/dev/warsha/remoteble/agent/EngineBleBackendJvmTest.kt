package dev.warsha.remoteble.agent

import com.juul.kable.Characteristic
import com.juul.kable.DiscoveredCharacteristic
import com.juul.kable.DiscoveredDescriptor
import com.juul.kable.ExperimentalApi
import dev.warsha.remoteble.protocol.AgentException
import dev.warsha.remoteble.protocol.Capabilities
import dev.warsha.remoteble.protocol.DeviceHandle
import dev.warsha.remoteble.protocol.ErrorKind
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest

/**
 * JVM-specific guard for the platform-conditional `rssi` capability. Kable's JVM/btleplug backend
 * has no connected-RSSI read (its `rssi()` returns cached advertisement RSSI, not a live value), so
 * the agent must NOT advertise the `rssi` capability here — otherwise a client would issue
 * `Op.ReadRssi` and get a stale/garbage number. See RssiSupport.jvm.kt (`agentRssiSupported()=false`).
 *
 * Constructing [EngineBleBackend] touches no radio (its `capabilities` set is computed eagerly, the
 * peripheral map stays empty), so this is safe to run headless.
 */
@OptIn(ExperimentalUuidApi::class, ExperimentalApi::class)
class EngineBleBackendJvmTest {

    @Test
    fun jvmDoesNotAdvertiseRssiCapability() {
        assertFalse(
            Capabilities.RSSI in EngineBleBackend().capabilities,
            "JVM/btleplug backend must not advertise the rssi capability (no connected read)",
        )
    }

    @Test
    fun jvmStillAdvertisesDescriptors() {
        // Sanity: the platform-gating didn't accidentally drop the always-on descriptors capability.
        assertTrue(Capabilities.DESCRIPTORS in EngineBleBackend().capabilities)
    }

    @Test
    fun jvmDoesNotAdvertiseConnParamsOrConnPriorityCapability() {
        // btleplug exposes no interval/priority control at all (see ConnParamsSupport.jvm.kt), so
        // neither the new conn.params surface nor its conn.priority alias should be advertised here.
        val capabilities = EngineBleBackend().capabilities
        assertFalse(Capabilities.CONN_PARAMS in capabilities)
        assertFalse(Capabilities.CONN_PRIORITY in capabilities)
    }

    /**
     * Regression test for the design-decisions boundary table's `CharNode.properties` row, which
     * claimed this was populated only on the macOS engine via a `propertiesOf` seam. That seam
     * doesn't exist: `toNode()` reads Kable's `properties.value` directly in commonMain, and
     * Kable's JVM/btleplug `DiscoveredCharacteristic` (verified: `BtleplugCharacteristic` reads the
     * real `btleplug-ffi` `CharacteristicPropertyFlags` bits, not a stub) already carries real
     * property bits — so this asserts the mapping preserves them rather than zeroing them out.
     */
    @Test
    fun toNodePreservesNonZeroPropertyBits() {
        val fakeChar = object : DiscoveredCharacteristic {
            override val serviceUuid: Uuid = Uuid.parse("0000180d-0000-1000-8000-00805f9b34fb")
            override val characteristicUuid: Uuid = Uuid.parse("00002a37-0000-1000-8000-00805f9b34fb")
            override val properties: Characteristic.Properties = Characteristic.Properties(0x12)
            override val descriptors: List<DiscoveredDescriptor> = emptyList()
        }

        val node = with(EngineBleBackend()) { fakeChar.toNode() }

        assertEquals(0x12, node.properties, "property bits must survive the DiscoveredCharacteristic -> CharNode mapping")
    }

    /**
     * Confirmed on hardware (Rig A, 2026-07-27): a peripheral-side ATT error on a
     * write-with-response is never delivered by btleplug on macOS — `peripheral.write` neither
     * returns nor throws, it simply parks forever. Unbounded, that stalls the agent's command
     * coroutine indefinitely, and because `BleAgent` chains same-device writes, the stalled write
     * never completes its turn and every later write to that device blocks behind it. Nothing
     * cancels it (the client's timeout is client-side; there is no cancel op), so the bound here is
     * the only thing that lets that chain drain.
     *
     * Virtual time: `runTest` advances the clock, so the 10s bound costs no wall-clock time.
     */
    @Test
    fun gattOpThatNeverCompletesIsReportedAsTimeoutRatherThanHanging() = runTest {
        val error = assertFailsWith<AgentException> {
            EngineBleBackend().gattOp(ErrorKind.WRITE_FAILED, "write") { awaitCancellation() }
        }.error

        // TIMEOUT, deliberately *not* the WRITE_FAILED passed as the failure kind: a transaction
        // that never completed has an unknown outcome — the peripheral may have applied it — so
        // "the radio rejected the write" would claim more than is known.
        assertEquals(ErrorKind.TIMEOUT, error.kind)
        assertTrue(
            error.message?.contains("write") == true,
            "the message should name the operation that stalled, got: ${error.message}",
        )
    }

    @Test
    fun gattOpStillMapsARealFailureToItsOwnErrorKind() = runTest {
        // The bound must not swallow the normal path: a backend call that *does* fail keeps
        // reporting the caller's kind, so a genuine rejection is still WRITE_FAILED, not TIMEOUT.
        val error = assertFailsWith<AgentException> {
            EngineBleBackend().gattOp(ErrorKind.WRITE_FAILED, "write") {
                throw IllegalStateException("peripheral said no")
            }
        }.error

        assertEquals(ErrorKind.WRITE_FAILED, error.kind)
        assertEquals("peripheral said no", error.message)
    }

    @Test
    fun gattOpReturnsTheValueWhenTheOperationCompletes() = runTest {
        val value = EngineBleBackend().gattOp(ErrorKind.READ_FAILED, "read") { byteArrayOf(0x07) }
        assertEquals(0x07, value.single())
    }

    @Test
    fun gattOpLetsCancellationPropagateRatherThanMappingIt() = runTest {
        // Structured cancellation must survive the bound: mapping a CancellationException to an
        // ErrorKind would turn a cancelled scope into a bogus BLE error reply.
        assertFailsWith<CancellationException> {
            EngineBleBackend().gattOp(ErrorKind.READ_FAILED, "read") {
                throw CancellationException("scope cancelled")
            }
        }
    }

    // --- teardown bound -------------------------------------------------------------------------
    // Kable's own `disconnectTimeout` is read off the builder only by the Android and Apple
    // backends; the JVM/btleplug factory drops it, so without this bound a `disconnect()` that never
    // returns leaves the op suspended forever and the client waiting on a reply that never comes.

    @Test
    fun aDisconnectThatNeverCompletesStillReturnsSoTheCloseCanRun() = runTest {
        // The stimulus is the point: `awaitCancellation` genuinely never completes, so returning at
        // all is only possible because the bound expired — and virtual time proves it waited the
        // bound out rather than the block having quietly finished.
        EngineBleBackend().boundedDisconnect(DeviceHandle("dev-1")) { awaitCancellation() }

        assertTrue(
            currentTime > 0,
            "a disconnect that never completes must return once the bound expires, not suspend forever",
        )
    }

    @Test
    fun aDisconnectThatCompletesIsNotDelayedByTheBound() = runTest {
        var ran = false
        EngineBleBackend().boundedDisconnect(DeviceHandle("dev-1")) { ran = true }

        assertTrue(ran, "the normal path must still run the teardown")
        assertEquals(0L, currentTime, "a completing disconnect must not wait out the bound")
    }

    @Test
    fun aFailingDisconnectStillReachesTheCallersBestEffortHandling() = runTest {
        // The bound must not convert a real teardown failure into a silent success: `disconnect()`
        // logs it and falls through to `close()`, which it can only do if the throw still arrives.
        assertFailsWith<IllegalStateException> {
            EngineBleBackend().boundedDisconnect(DeviceHandle("dev-1")) {
                throw IllegalStateException("stack refused")
            }
        }
    }

    // --- degraded-write fail-fast -------------------------------------------------------------
    // The condition itself (btleplug dropping write completions after an ATT error) reproduces only
    // on hardware; what is testable here is the gate — that the state is tracked, that the switch
    // actually switches, and that the short-circuit reports the same kind as waiting would.

    @Test
    fun writesAreNotDegradedUntilOneFailsToComplete() {
        assertNull(
            EngineBleBackend().degradedWriteRejection(DEVICE, withResponse = true),
            "a device with no stalled write must not be short-circuited",
        )
    }

    @Test
    fun aStalledWriteMarksTheConnectionAndShortCircuitsLaterWithResponseWrites() {
        val backend = EngineBleBackend()
        backend.markWriteDegraded(DEVICE)

        assertTrue(backend.isWriteDegraded(DEVICE))
        val rejection = assertNotNull(backend.degradedWriteRejection(DEVICE, withResponse = true))
        // Same kind the caller would have got by waiting out GATT_OP_TIMEOUT — the point of the
        // short-circuit is latency, not a different outcome.
        assertEquals(ErrorKind.TIMEOUT, rejection.kind)
        assertTrue(
            rejection.message?.contains("reconnect") == true,
            "the message should tell the operator what recovers it, got: ${rejection.message}",
        )
    }

    /**
     * Regression test (Rig A, 2026-07-28): the gate used to apply to every write regardless of
     * type, which made a degraded connection reject WriteWithoutResponse too — breaking the
     * documented "WWR still returns Ok" guarantee, since WithoutResponse never awaits the ATT
     * response that actually wedges (see [degradedWriteRejection]'s doc). A degraded device must
     * still let WithoutResponse writes through.
     */
    @Test
    fun aDegradedDeviceStillLetsWriteWithoutResponseThrough() {
        val backend = EngineBleBackend()
        backend.markWriteDegraded(DEVICE)

        assertTrue(backend.isWriteDegraded(DEVICE))
        assertNull(
            backend.degradedWriteRejection(DEVICE, withResponse = false),
            "WriteWithoutResponse never awaits the ATT response that degrades, so it must not be short-circuited",
        )
    }

    @Test
    fun degradationIsPerDeviceNotAgentWide() {
        val backend = EngineBleBackend()
        backend.markWriteDegraded(DEVICE)

        assertNull(
            backend.degradedWriteRejection(DeviceHandle("other-device"), withResponse = true),
            "one peripheral's stalled writes must not short-circuit a different peripheral",
        )
    }

    @Test
    fun disablingFailFastKeepsTheUnmodifiedBehaviourEvenOnceDegraded() {
        // The switch exists so the workaround can be turned off — if btleplug starts delivering ATT
        // errors, or if the short-circuit ever misfires. Off means writes go to the radio as before.
        val backend = EngineBleBackend(failFastOnDegradedWrites = false)
        backend.markWriteDegraded(DEVICE)

        assertTrue(backend.isWriteDegraded(DEVICE), "the state is still tracked for logging")
        assertNull(
            backend.degradedWriteRejection(DEVICE, withResponse = true),
            "with fail-fast off, a degraded device must still attempt the write",
        )
    }

    /**
     * Nothing cancels an in-flight op when a link drops, so a write that hangs can still be waiting
     * out GATT_OP_TIMEOUT long after the client has reconnected. Marking on the bare handle would
     * let that late timeout degrade the *fresh* connection, which — with fail-fast on by default —
     * fails every subsequent with-response write on a connection that is perfectly healthy.
     */
    @Test
    fun aStalledWriteFromAPreviousConnectionDoesNotDegradeTheCurrentOne() {
        val backend = EngineBleBackend()
        val whenTheWriteStarted = backend.generationOf(DEVICE)
        // The client dropped and reconnected while that write was still hanging.
        backend.advanceConnectionGeneration(DEVICE)

        backend.markWriteDegraded(DEVICE, generation = whenTheWriteStarted)

        assertFalse(backend.isWriteDegraded(DEVICE), "a previous connection's stall must not carry over")
        assertNull(backend.degradedWriteRejection(DEVICE, withResponse = true))
    }

    @Test
    fun aStalledWriteOnTheCurrentConnectionStillDegradesIt() {
        // The guard above must not over-reach: a stall reported against the live generation is the
        // real case the workaround exists for.
        val backend = EngineBleBackend()
        backend.advanceConnectionGeneration(DEVICE)

        backend.markWriteDegraded(DEVICE, generation = backend.generationOf(DEVICE))

        assertTrue(backend.isWriteDegraded(DEVICE))
    }

    @Test
    fun reconnectingClearsTheDegradedState() {
        val backend = EngineBleBackend()
        backend.markWriteDegraded(DEVICE)
        assertTrue(backend.isWriteDegraded(DEVICE))

        backend.advanceConnectionGeneration(DEVICE)

        assertFalse(backend.isWriteDegraded(DEVICE), "a re-established connection is the observed recovery")
        assertNull(backend.degradedWriteRejection(DEVICE, withResponse = true))
    }

    private companion object {
        val DEVICE = DeviceHandle("11111111-2222-3333-4444-555555555555")
    }
}
