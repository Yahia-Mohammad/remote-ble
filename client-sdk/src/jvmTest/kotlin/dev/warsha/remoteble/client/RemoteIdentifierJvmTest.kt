package dev.warsha.remoteble.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * On the JVM, Kable's `Identifier` is the host radio's native id, so whether an agent handle can
 * become an `Identifier` depends on the JVM host OS. On a macOS host a CoreBluetooth-style UUID
 * handle round-trips; on any other host a foreign-format handle surfaces
 * [RemoteIdentifierUnavailableException] (the portable identity is `.handle`). This is a
 * documented interim limitation, removed by agent-side handle translation in a future release.
 *
 * Host-conditional because CI runs `jvmTest` on Linux while local dev runs it on macOS — each host
 * asserts the behavior that applies to it.
 */
class RemoteIdentifierJvmTest {

    private val uuidHandle = "71B7A414-9541-EE17-0891-8D254512935A"
    private val hostIsMac = System.getProperty("os.name").orEmpty().startsWith("Mac")

    @Test
    fun uuid_handle_round_trips_on_macos_host() {
        if (!hostIsMac) return
        assertEquals(
            uuidHandle.uppercase(),
            deviceHandleToIdentifier(uuidHandle).toString().uppercase(),
        )
    }

    @Test
    fun foreign_format_handle_surfaces_clear_exception_off_macos() {
        if (hostIsMac) return
        assertFailsWith<RemoteIdentifierUnavailableException> {
            deviceHandleToIdentifier(uuidHandle)
        }
    }
}
