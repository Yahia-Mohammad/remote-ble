package dev.warsha.remoteble.client

import dev.warsha.remoteble.protocol.AgentFingerprint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class PendingRefusalsTest {
    private val refusal = AgentIdentityMismatchException(
        AgentFingerprint.ofSpkiSha256(ByteArray(32)),
        AgentFingerprint.ofSpkiSha256(ByteArray(32) { 1 }),
    )

    @Test
    fun aFailureAtAnotherOriginIsNotTheMismatch() {
        val refusals = PendingRefusals()
        refusals.record(PendingRefusals.origin("192.168.1.20", 8080), refusal)

        assertNull(refusals.take(PendingRefusals.origin("192.168.1.21", 8080)), "another host")
        assertNull(refusals.take(PendingRefusals.origin("192.168.1.20", 8081)), "another port")
        assertSame(refusal, refusals.take(PendingRefusals.origin("192.168.1.20", 8080)))
    }

    @Test
    fun aRefusalIsTakenOnce() {
        val refusals = PendingRefusals()
        val origin = PendingRefusals.origin("10.0.0.2", 443)
        refusals.record(origin, refusal)

        assertSame(refusal, refusals.take(origin))
        assertNull(refusals.take(origin), "a later, unrelated failure must not repeat it")
    }

    @Test
    fun ipv6HostsMatchWithOrWithoutBracketsAndInAnyCase() {
        // NSURLProtectionSpace's host has no brackets; a request URL's may, and hex may differ in case.
        assertEquals(PendingRefusals.origin("fe80::1a", 8080), PendingRefusals.origin("[FE80::1A]", 8080))
        assertEquals(PendingRefusals.origin("h", 8080), PendingRefusals.origin("h", 8080L))
    }
}
