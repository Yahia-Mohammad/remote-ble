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
}
