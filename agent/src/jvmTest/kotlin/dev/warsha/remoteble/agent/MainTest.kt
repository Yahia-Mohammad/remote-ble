package dev.warsha.remoteble.agent

import dev.warsha.remoteble.log.LogLevel
import dev.warsha.remoteble.log.LogSink
import dev.warsha.remoteble.log.Logger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import java.nio.file.Files

/**
 * BIND-SECURITY-01: a non-loopback bind without credentials must fail startup unless the named
 * development override is set. Mirrors `agent-rs`'s `validate_bind_policy` tests
 * (`main::tests::bind_policy_allows_loopback_and_authenticated_lan` /
 * `bind_policy_rejects_open_lan_and_multicast`).
 */
class MainTest {
    private val logMessages = mutableListOf<String>()

    @AfterTest
    fun resetLogger() {
        Logger.configure(level = null)
        logMessages.clear()
    }

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

    @Test
    fun writePolicyLoaderHandlesBlankAndInvalidPathsBeforeServerStartup() {
        val policy = Files.createTempFile("remoteble-policy", ".json")
        try {
            val known = setOf("lab-a")
            Logger.configure(
                level = LogLevel.WARN,
                sink = LogSink { _, _, message, _ -> logMessages += message },
            )

            assertFalse(loadWritePolicy(path = null, knownPrincipals = known).enforced)
            assertFalse(loadWritePolicy(path = "", knownPrincipals = known).enforced)
            assertFalse(loadWritePolicy(path = " \t ", knownPrincipals = known).enforced)
            assertEquals(2, logMessages.size)
            assertTrue(logMessages.all { it.contains("write policy is permissive") })

            Files.writeString(policy, "{\"version\":1,\"principals\":{\"lab-a\":{\"writes\":[]}}}")
            assertTrue(loadWritePolicy(policy.toString(), known).enforced)
            assertFailsWith<IllegalArgumentException> {
                loadWritePolicy("${policy}-missing", knownPrincipals = known)
            }
            Files.writeString(policy, "not json")
            assertFailsWith<IllegalArgumentException> {
                loadWritePolicy(policy.toString(), knownPrincipals = known)
            }
        } finally {
            Files.deleteIfExists(policy)
        }
    }
}
