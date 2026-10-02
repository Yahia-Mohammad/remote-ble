package dev.warsha.remoteble.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClientCredentialsTest {
    @Test
    fun authenticatesNamedCredentialsWithoutExposingTheNameOnWire() {
        val credentials = ClientCredentials.of(mapOf("lab-a" to "secret-a", "lab-b" to "secret-b"))

        assertEquals("lab-a", credentials.authenticate("Bearer secret-a"))
        assertEquals("lab-b", credentials.authenticate("Bearer secret-b"))
        assertNull(credentials.authenticate("Bearer wrong"))
    }

    @Test
    fun legacyTokenMapsToDefaultPrincipal() {
        assertEquals(ClientCredentials.DEFAULT_PRINCIPAL, ClientCredentials.legacy("legacy").authenticate("Bearer legacy"))
        assertEquals(ClientCredentials.ANONYMOUS_PRINCIPAL, ClientCredentials.legacy(null).authenticate(null))
    }

    @Test
    fun rejectsEmptyNamesAndSecrets() {
        assertFailsWith<IllegalArgumentException> { ClientCredentials.of(mapOf("" to "secret")) }
        assertFailsWith<IllegalArgumentException> { ClientCredentials.of(mapOf("name" to "")) }
        assertFailsWith<IllegalArgumentException> {
            ClientCredentials.of(mapOf("first" to "same-secret", "second" to "same-secret"))
        }
    }

    @Test
    fun sessionKeyIsPrincipalScoped() {
        assertEquals(
            false,
            ClientCredentials.sessionKey("alpha", "same-client") == ClientCredentials.sessionKey("beta", "same-client"),
        )
        assertFailsWith<IllegalArgumentException> { ClientCredentials.sessionKey("alpha", "") }
    }

    @Test
    fun liveSessionReleaseCannotRetireANewerGeneration() {
        val sessions = LiveSessionRegistry()
        val key = ClientCredentials.sessionKey("alpha", "same-client")

        assertTrue(sessions.tryAcquire(key, generation = 1))
        assertFalse(sessions.tryAcquire(key, generation = 2))
        sessions.release(key, generation = 2)
        assertFalse(sessions.tryAcquire(key, generation = 3))
        sessions.release(key, generation = 1)
        assertTrue(sessions.tryAcquire(key, generation = 3))
    }

    @Test
    fun revokedCredentialFailsAuthenticationUntilUnrevoked() {
        val credentials = ClientCredentials.of(mapOf("alpha" to "secret-a", "beta" to "secret-b"))
        assertEquals("alpha", credentials.authenticate("Bearer secret-a"))

        credentials.revoke("alpha")
        assertTrue(credentials.isRevoked("alpha"))
        assertNull(credentials.authenticate("Bearer secret-a"))
        // Revocation is per-principal: an unrelated credential keeps working.
        assertEquals("beta", credentials.authenticate("Bearer secret-b"))

        credentials.unrevoke("alpha")
        assertFalse(credentials.isRevoked("alpha"))
        assertEquals("alpha", credentials.authenticate("Bearer secret-a"))

        assertFailsWith<IllegalArgumentException> { credentials.revoke("unknown-principal") }
    }

    @Test
    fun failedAuthLimiterBoundsEachPeerAndSuppressesRepeatedLimitLogs() {
        val limiter = FailedAuthLimiter(maxPeers = 1, maxFailuresPerPeer = 2, maxFailuresGlobal = 3, windowMillis = 60_000)

        assertTrue(limiter.recordFailure("peer-a").allowed)
        assertTrue(limiter.recordFailure("peer-a").allowed)
        val limited = limiter.recordFailure("peer-a")
        assertFalse(limited.allowed)
        assertTrue(limited.shouldLog)
        assertFalse(limiter.recordFailure("peer-a").shouldLog)
        // A second peer evicts the oldest entry instead of growing the peer map.
        assertTrue(limiter.recordFailure("peer-b").allowed)
    }

    @Test
    fun onePairingPerLiveCredentialDefaultFirstAndOneWithoutForATokenFreeAgent() {
        val fp = dev.warsha.remoteble.protocol.AgentFingerprint.ofSpkiSha256(ByteArray(32))
        val credentials = ClientCredentials.of(
            mapOf(ClientCredentials.DEFAULT_PRINCIPAL to "t0ken", "zed" to "z-secret", "amy" to "a-secret", "gone" to "g-secret"),
        )
        credentials.revoke("gone")

        val pairings = credentials.pairings("10.0.0.2", 8080, fp)

        kotlin.test.assertEquals(listOf(null, "amy", "zed"), pairings.map { it.first })
        kotlin.test.assertEquals("remoteble://10.0.0.2:8080?token=t0ken&fp=$fp", pairings[0].second.toUri())
        kotlin.test.assertEquals("a-secret", pairings[1].second.token)
        kotlin.test.assertEquals(
            listOf(null to "remoteble://127.0.0.1:8080"),
            ClientCredentials.legacy(null).pairings("127.0.0.1", 8080, null).map { it.first to it.second.toUri() },
        )
    }

    @Test
    fun aPairingNamesTheBoundAddressOrTheLanOneForAWildcard() {
        kotlin.test.assertEquals("192.168.1.20", pairingHost("192.168.1.20") { "10.9.9.9" })
        for (wildcard in listOf("0.0.0.0", "::", "0:0:0:0:0:0:0:0")) {
            kotlin.test.assertEquals("10.9.9.9", pairingHost(wildcard) { "10.9.9.9" }, wildcard)
        }
        kotlin.test.assertEquals(null, pairingHost("0.0.0.0") { null })
    }
}
