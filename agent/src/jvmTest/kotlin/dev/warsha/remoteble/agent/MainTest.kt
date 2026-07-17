package dev.warsha.remoteble.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import java.nio.file.Files

/**
 * BIND-SECURITY-01: a non-loopback bind without credentials must fail startup unless the named
 * development override is set. Mirrors `agent-rs`'s `validate_bind_policy` tests
 * (`main::tests::bind_policy_allows_loopback_and_authenticated_lan` /
 * `bind_policy_rejects_open_lan_and_multicast`).
 */
class MainTest {
    @Test
    fun loopbackAndAuthenticatedLanAreAllowed() {
        assertEquals("127.0.0.1", validateBind("127.0.0.1", hasCredential = false, allowInsecureLan = false))
        assertEquals("0.0.0.0", validateBind("0.0.0.0", hasCredential = true, allowInsecureLan = false))
        assertEquals("0:0:0:0:0:0:0:1", validateBind("::1", hasCredential = false, allowInsecureLan = false))
    }

    @Test
    fun openLanWithoutCredentialsFailsAbsentTheDevelopmentOverride() {
        assertFailsWith<IllegalStateException> {
            validateBind("0.0.0.0", hasCredential = false, allowInsecureLan = false)
        }
    }

    @Test
    fun theNamedDevelopmentOverrideUnblocksAnUnauthenticatedNonLoopbackBind() {
        assertEquals("0.0.0.0", validateBind("0.0.0.0", hasCredential = false, allowInsecureLan = true))
    }

    @Test
    fun multicastIsRejectedEvenWithCredentials() {
        assertFailsWith<IllegalArgumentException> {
            validateBind("224.0.0.1", hasCredential = true, allowInsecureLan = false)
        }
    }

    @Test
    fun simulationFlagAndProfileLoaderFailBeforeServerStartup() {
        assertEquals(
            Cli(bindHost = "127.0.0.1", port = 9000, simulationPath = "sim.json"),
            parseCli(arrayOf("--bind", "127.0.0.1", "--port", "9000", "--simulate", "sim.json")),
        )
        assertFailsWith<IllegalStateException> { parseCli(arrayOf("--simulate")) }

        val malformed = Files.createTempFile("remoteble-invalid-sim", ".json")
        try {
            Files.writeString(malformed, "{\"schemaVersion\": 99, \"peripherals\": []}")
            assertFailsWith<IllegalArgumentException> { readSimulationProfile(malformed.toString()) }
        } finally {
            Files.deleteIfExists(malformed)
        }
    }
}
