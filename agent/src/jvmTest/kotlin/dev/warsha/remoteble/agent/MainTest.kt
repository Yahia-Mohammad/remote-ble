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
    fun tlsAndIdentityResetAreOptInFlags() {
        assertEquals(
            Cli(bindHost = null, port = 8443, simulationPath = null, tls = true, resetIdentity = true),
            parseCli(arrayOf("8443", "--tls", "--reset-identity")),
        )
        assertFalse(parseCli(emptyArray()).tls)
    }

    @Test
    fun desktopIdentityIsTouchedOnlyAfterTheActualBind() = kotlinx.coroutines.runBlocking<Unit> {
        val dir = Files.createTempDirectory("remoteble-main-tls")
        val path = dir.resolve("id.pem")
        val env = mutableMapOf("REMOTE_BLE_IDENTITY_FILE" to path.toString())
        try {
            assertEquals(null, tlsFrontFor(Cli(null, 8080, null), env::get).front)
            assertFalse(Files.exists(path))
            env["REMOTE_BLE_TLS"] = "true"
            val prepared = tlsFrontFor(Cli(null, 8080, null), env::get)
            assertFalse(Files.exists(path), "preparing the graph must not create an identity")
            prepared.front!!.start("127.0.0.1", 0, 1) {}.stop()
            val first = Files.readAllBytes(path)
            java.net.ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1")).use { occupied ->
                val reset = tlsFrontFor(Cli(null, occupied.localPort, null, resetIdentity = true), env::get)
                assertFailsWith<AgentBindException> { reset.front!!.start("127.0.0.1", occupied.localPort, 1) {} }
                assertTrue(first.contentEquals(Files.readAllBytes(path)), "a refused reset must preserve the pinned key")
            }
            val reset = tlsFrontFor(Cli(null, 8080, null, resetIdentity = true), env::get)
            reset.front!!.start("127.0.0.1", 0, 1) {}.stop()
            assertFalse(first.contentEquals(Files.readAllBytes(path)), "a bound reset changes the identity")
            env.remove("REMOTE_BLE_TLS")
            val cleartextReset = tlsFrontFor(Cli(null, 8080, null, resetIdentity = true), env::get)
            assertTrue(Files.exists(path))
            cleartextReset.afterBind()
            assertFalse(Files.exists(path))
            env["REMOTE_BLE_TLS"] = "yes"
            assertFailsWith<IllegalStateException> { tlsFrontFor(Cli(null, 8080, null), env::get) }
        } finally {
            Files.deleteIfExists(path)
            Files.deleteIfExists(dir)
        }
    }

    @Test
    fun entrypointRefusalsPreserveIdentityBeforeConfigurationAndBindFailures() {
        val dir = Files.createTempDirectory("remoteble-startup-regression")
        val path = dir.resolve("id.pem")
        val simulation = dir.resolve("simulation.json")
        Files.writeString(simulation, """{"schemaVersion":1,"peripherals":[{"id":"test","advertisement":{},"services":[{"uuid":"180f","characteristics":[{"uuid":"2a19","properties":["read"],"read":{"static":"64"}}]}]}]}""")
        readSimulationProfile(simulation.toString()) // Fail here if the fixture cannot reach startup.
        AgentIdentityStore.loadOrCreate(path)
        val original = Files.readAllBytes(path)
        val classpath = checkNotNull(System.getProperty("remoteble.agent.testClasspath"))
        try {
            java.net.ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1")).use { occupied ->
                for (extra in listOf(
                    mapOf("REMOTE_BLE_SCAN_CONCURRENCY" to "invalid"),
                    mapOf("REMOTE_BLE_WRITE_FAIL_FAST" to "invalid"),
                    mapOf("REMOTE_BLE_TOKEN" to "client-secret", "REMOTE_BLE_OPERATOR_TOKEN" to "client-secret"),
                    emptyMap(),
                )) {
                    for (tls in listOf(true, false)) {
                        val args = mutableListOf(
                            "${System.getProperty("java.home")}/bin/java", "-cp", classpath,
                            "dev.warsha.remoteble.agent.MainKt", "--port", occupied.localPort.toString(),
                            "--bind", "127.0.0.1", "--reset-identity", "--simulate", simulation.toString(),
                        )
                        if (tls) args += "--tls"
                        val output = dir.resolve("startup.log").toFile()
                        val process = ProcessBuilder(args).apply {
                            environment().keys.removeIf { it.startsWith("REMOTE_BLE_") }
                            environment()["REMOTE_BLE_IDENTITY_FILE"] = path.toString()
                            environment().putAll(extra)
                            redirectErrorStream(true)
                            redirectOutput(output)
                        }.start()
                        try {
                            assertTrue(process.waitFor(15, java.util.concurrent.TimeUnit.SECONDS), "refused startup hung")
                            assertEquals(1, process.exitValue(), output.readText())
                            val failure = output.readText()
                            val expected = when {
                                "REMOTE_BLE_SCAN_CONCURRENCY" in extra -> "invalid"
                                "REMOTE_BLE_WRITE_FAIL_FAST" in extra -> "REMOTE_BLE_WRITE_FAIL_FAST must"
                                "REMOTE_BLE_OPERATOR_TOKEN" in extra -> "operator token"
                                else -> "Cannot start"
                            }
                            assertTrue(failure.lowercase().contains(expected.lowercase()), failure)
                            assertTrue(original.contentEquals(Files.readAllBytes(path)), "startup refusal changed the pinned identity")
                        } finally {
                            process.destroyForcibly()
                        }
                    }
                }
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun realCliPairingOutputBypassesTheStdoutLog() {
        val dir = Files.createTempDirectory("remoteble-pairing-regression")
        val pairingFile = Files.createFile(dir.resolve("pairing-output"))
        val logFile = dir.resolve("agent.log")
        val identityFile = dir.resolve("identity.pem")
        val simulation = dir.resolve("simulation.json")
        Files.writeString(simulation, """{"schemaVersion":1,"peripherals":[{"id":"test","advertisement":{},"services":[{"uuid":"180f","characteristics":[{"uuid":"2a19","properties":["read"],"read":{"static":"64"}}]}]}]}""")
        val port = java.net.ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1")).use { it.localPort }
        val process = ProcessBuilder(
            "${System.getProperty("java.home")}/bin/java", "-cp", System.getProperty("remoteble.agent.testClasspath"),
            "dev.warsha.remoteble.agent.MainKt", "--bind", "127.0.0.1", "--port", port.toString(),
            "--tls", "--print-pairing", "--simulate", simulation.toString(),
        ).apply {
            environment().keys.removeIf { it.startsWith("REMOTE_BLE_") }
            environment()["REMOTE_BLE_IDENTITY_FILE"] = identityFile.toString()
            environment()["REMOTE_BLE_TOKEN"] = "pairing-only-secret"
            environment()["REMOTE_BLE_PAIRING_OUTPUT"] = pairingFile.toString()
            redirectErrorStream(true)
            redirectOutput(logFile.toFile())
        }.start()
        try {
            kotlinx.coroutines.runBlocking {
                kotlinx.coroutines.withTimeout(15_000) {
                    while (!Files.exists(pairingFile) || Files.readString(pairingFile).isBlank()) {
                        assertTrue(process.isAlive, logFile.toFile().readText())
                        kotlinx.coroutines.delay(25)
                    }
                }
            }
            val uri = Files.readString(pairingFile).trim().removePrefix("Pairing: ")
            val pairing = dev.warsha.remoteble.protocol.AgentPairing.parse(uri)
            assertEquals("pairing-only-secret", pairing.token)
            assertEquals(port, pairing.port)
            assertEquals(AgentIdentityStore.loadOrCreate(identityFile).fingerprint, pairing.fingerprint)
            val log = Files.readString(logFile)
            assertTrue(log.contains("RemoteBLE agent listening on wss://"), log)
            assertFalse(log.contains("remoteble://"), "pairing URI leaked into agent.log")
            assertFalse(log.contains("pairing-only-secret"), "bearer token leaked into agent.log")
        } finally {
            process.destroy()
            if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) process.destroyForcibly().waitFor()
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun wrapperPairingOutputBypassesTheConfiguredLogger() {
        val path = Files.createTempFile("remoteble-pairing-output", ".txt")
        Logger.configure(level = LogLevel.INFO, sink = LogSink { _, _, message, _ -> logMessages += message })
        try {
            printPairings(listOf("Pairing: remoteble://127.0.0.1:8080?token=private"), path.toString())
            assertTrue(Files.readString(path).contains("token=private"))
            assertTrue(logMessages.isEmpty())
            Files.delete(path)
            assertFailsWith<java.nio.file.NoSuchFileException> {
                printPairings(listOf("Pairing: remoteble://127.0.0.1:8080?token=private"), path.toString())
            }
            assertFalse(Files.exists(path), "a removed FIFO must never be recreated as a regular secret file")
        } finally {
            Files.deleteIfExists(path)
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

    @Test
    fun printPairingIsAFlag() {
        assertTrue(parseCli(arrayOf("--tls", "--print-pairing")).printPairing)
        assertFalse(parseCli(arrayOf("--tls")).printPairing)
    }

    @Test
    fun tlsPin05ANonLoopbackBindServesCleartextOnlyWhenAllowed() {
        val refused = assertFailsWith<IllegalStateException> { validateCleartext("0.0.0.0", tls = false, allowCleartextLan = false) }
        assertTrue("REMOTE_BLE_ALLOW_CLEARTEXT_LAN" in refused.message.orEmpty() && "--tls" in refused.message.orEmpty())
        assertFailsWith<IllegalStateException> { validateCleartext("192.168.1.20", tls = false, allowCleartextLan = false) }

        validateCleartext("0.0.0.0", tls = true, allowCleartextLan = false)
        validateCleartext("0.0.0.0", tls = false, allowCleartextLan = true)
        // Loopback keeps serving ws:// for tunnels and the TLS proxy recipe.
        validateCleartext("127.0.0.1", tls = false, allowCleartextLan = false)
        validateCleartext("::1", tls = false, allowCleartextLan = false)
    }
}
