package dev.warsha.remoteble.agent

import dev.warsha.remoteble.protocol.AGENT_TLS_SERVER_NAME
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import kotlin.io.path.deleteRecursively
import kotlin.io.path.ExperimentalPathApi
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AgentIdentityTest {
    private val dir = Files.createTempDirectory("remoteble-identity")

    @OptIn(ExperimentalPathApi::class)
    @AfterTest
    fun tearDown() = dir.deleteRecursively()

    @Test
    fun theCertificateIsAValidSelfSignedServerCertificate() {
        val cert = AgentIdentityStore.generate().certificate

        cert.verify(cert.publicKey) // self-signed: its own key verifies its signature
        cert.checkValidity()
        assertEquals(3, cert.version)
        assertEquals("SHA256withECDSA", cert.sigAlgName)
        assertEquals(cert.subjectX500Principal, cert.issuerX500Principal)
        assertEquals(listOf(listOf<Any>(2, AGENT_TLS_SERVER_NAME)), cert.subjectAlternativeNames.map { it.toList() })
        assertEquals(-1, cert.basicConstraints, "an end-entity certificate, not a CA")
        assertTrue(cert.keyUsage[0], "digitalSignature")
        assertEquals(listOf("1.3.6.1.5.5.7.3.1"), cert.extendedKeyUsage)
        assertEquals(253402300799000L, cert.notAfter.time, "no well-defined expiry: 9999-12-31T23:59:59Z")
    }

    @Test
    fun theFingerprintIsTheSha256OfTheSpki() {
        val identity = AgentIdentityStore.generate()

        val expected = MessageDigest.getInstance("SHA-256").digest(identity.certificate.publicKey.encoded)
        assertTrue(identity.fingerprint.matches(expected))
    }

    @Test
    fun theIdentityIsStableAcrossLoadsAndChangesOnReset() {
        val path = dir.resolve("nested/agent-identity.pem")

        val first = AgentIdentityStore.loadOrCreate(path)
        val again = AgentIdentityStore.loadOrCreate(path)
        val reset = AgentIdentityStore.loadOrCreate(path, reset = true)

        assertEquals(first.fingerprint, again.fingerprint)
        assertEquals(first.certificate, again.certificate)
        assertNotEquals(first.fingerprint, reset.fingerprint)
        assertEquals(reset.fingerprint, AgentIdentityStore.loadOrCreate(path).fingerprint)
    }

    @Test
    fun theFileIsReadableByItsOwnerOnly() {
        val path = dir.resolve("agent-identity.pem")
        AgentIdentityStore.loadOrCreate(path)

        if (path.fileSystem.supportedFileAttributeViews().contains("posix")) {
            assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(path)))
        }
        assertEquals(listOf("agent-identity.pem"), Files.list(dir).map { it.fileName.toString() }.toList(), "no temp file left behind")
    }

    @Test
    fun defaultPathsFollowEachPlatformsConvention() {
        val env = mapOf("APPDATA" to "C:\\Users\\a\\AppData\\Roaming", "XDG_CONFIG_HOME" to "/xdg")
        assertEquals(
            Paths.get("/home/a", "Library", "Application Support", "RemoteBLE", "agent-identity.pem"),
            AgentIdentityStore.defaultPath("Mac OS X", env::get, "/home/a"),
        )
        assertEquals(Paths.get("/xdg", "remoteble", "agent-identity.pem"), AgentIdentityStore.defaultPath("Linux", env::get, "/home/a"))
        assertEquals(
            Paths.get("/home/a", ".config", "remoteble", "agent-identity.pem"),
            AgentIdentityStore.defaultPath("Linux", { null }, "/home/a"),
        )
        assertEquals(
            Paths.get("C:\\Users\\a\\AppData\\Roaming", "RemoteBLE", "agent-identity.pem"),
            AgentIdentityStore.defaultPath("Windows 11", env::get, "/home/a"),
        )
    }

    @Test
    fun timesBeyond2049UseGeneralizedTime() {
        // 2050-01-01T00:00:00Z: the first instant RFC 5280 forbids as UTCTime.
        assertEquals("18 0f 32303530303130313030303030305a", Der.time(2524608000L).hex())
        assertEquals("17 0d 3439313233313233353935395a", Der.time(2524607999L).hex())
        // 1970-01-01T00:00:00Z
        assertEquals("17 0d 3730303130313030303030305a", Der.time(0).hex())
    }

    private fun ByteArray.hex(): String {
        val all = joinToString("") { "%02x".format(it) }
        return all.substring(0, 2) + " " + all.substring(2, 4) + " " + all.substring(4)
    }
}
