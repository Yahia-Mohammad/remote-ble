package dev.warsha.remoteble.agent

import dev.warsha.remoteble.log.LogLevel
import dev.warsha.remoteble.log.Logger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * An ownership key is `principal\u0000clientId`, and interpolating one straight into a log line put a
 * raw NUL byte in `agent.log`. That is not cosmetic: one NUL makes the whole file binary, so `file`
 * reports `data` and — the part that actually costs time — `grep` reports *no match* for strings
 * that are plainly present, silently, in the middle of an incident.
 *
 * These assert on the emitted line rather than on the helper, because the defect was never in the
 * key format: it was a log site interpolating the key directly, which a test of
 * [ClientCredentials.describeSessionKey] alone would not have caught.
 */
class LeaseLogFormatTest {

    private val captured = mutableListOf<String>()

    private fun capture() {
        Logger.configure(level = LogLevel.INFO) { _, _, message, _ -> captured.add(message) }
    }

    @AfterTest
    fun tearDown() {
        Logger.configure(level = null)
    }

    @Test
    fun anAcquiredLeaseLogsItsOwnerWithoutANulByte() = runTest {
        capture()
        val registry = PeripheralRegistry(backgroundScope)
        registry.acquire("sim-hrm-1", ClientCredentials.sessionKey("primary", "acceptance-client-a"))

        val line = captured.single { it.startsWith("lease acquired") }
        assertFalse('\u0000' in line, "a '\u0000' in a log line makes the file binary and unsearchable: $line")
        assertContains(line, "owner=primary/acceptance-client-a")
    }

    @Test
    fun aRefusedLeaseLogsItsOwnerWithoutANulByte() = runTest {
        capture()
        // One slot, already held by another device's lease, so the next acquisition is refused.
        val registry = PeripheralRegistry(backgroundScope, maxSlots = 1)
        registry.acquire("sim-hrm-1", ClientCredentials.sessionKey("primary", "incumbent"))
        registry.acquire("sim-hrm-2", ClientCredentials.sessionKey("primary", "latecomer"))

        val line = captured.single { it.startsWith("lease refused") }
        assertFalse('\u0000' in line, "a '\u0000' in a log line makes the file binary and unsearchable: $line")
        assertContains(line, "owner=primary/latecomer")
    }

    @Test
    fun noRegistryLogLineCarriesTheRawSeparator() = runTest {
        capture()
        val registry = PeripheralRegistry(backgroundScope)
        val key = ClientCredentials.sessionKey("primary", "acceptance-client-a")
        registry.acquire("sim-hrm-1", key)
        registry.releaseNow("sim-hrm-1", key)
        registry.acquire("sim-hrm-1", key)

        assertTrue(captured.isNotEmpty(), "expected the registry to log something to assert on")
        assertTrue(
            captured.none { '\u0000' in it },
            "these lines carry a raw NUL: ${captured.filter { '\u0000' in it }}",
        )
    }
}
