package dev.warsha.remoteble.client

import dev.warsha.remoteble.agent.AgentIdentityStore
import java.nio.file.Files
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNull

/**
 * The SPKI walk the Apple client pins with must find exactly the bytes the JDK's
 * `publicKey.encoded` returns, which is what the JVM and Android clients hash. Run against real
 * agent certificates here because the JVM is the one platform that can state the expected bytes.
 */
class CertificateSpkiTest {
    private val dir = Files.createTempDirectory("remoteble-spki")

    @OptIn(ExperimentalPathApi::class)
    @AfterTest
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun theWalkFindsTheSameSpkiAsTheJdk() {
        repeat(3) { i ->
            val certificate = AgentIdentityStore.loadOrCreate(dir.resolve("agent-$i.pem")).certificate
            assertContentEquals(certificate.publicKey.encoded, certificateSpki(certificate.encoded))
        }
    }

    @Test
    fun aDamagedCertificateYieldsNothing() {
        val der = AgentIdentityStore.loadOrCreate(dir.resolve("agent.pem")).certificate.encoded
        assertNull(certificateSpki(der.copyOf(der.size / 2)))
        assertNull(certificateSpki(byteArrayOf()))
        assertNull(certificateSpki(byteArrayOf(0x30, 0x84.toByte(), 0x7F, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())))
        assertNull(certificateSpki(der.copyOf().also { it[0] = 0x31 }))
    }
}
