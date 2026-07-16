package dev.warsha.remoteble.agent

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FailedAuthLimiterTest {

    @Test
    fun capsAPeerAndRateLimitsItsDenialLogs() {
        val limiter = FailedAuthLimiter(maxFailuresPerPeer = 5, maxFailuresGlobal = 64)
        val peer = "10.0.0.1"

        repeat(5) { assertTrue(limiter.recordFailure(peer).allowed) }

        val limited = limiter.recordFailure(peer)
        assertFalse(limited.allowed, "the sixth failure must be rate limited")
        assertTrue(limited.shouldLog, "the first denial should log")
        assertFalse(limiter.recordFailure(peer).shouldLog, "subsequent denials in the window are silent")
    }

    @Test
    fun globalCeilingLimitsAcrossDistinctPeers() {
        val limiter = FailedAuthLimiter(maxFailuresPerPeer = 1000, maxFailuresGlobal = 3)

        repeat(3) { index -> assertTrue(limiter.recordFailure("peer-$index").allowed) }
        assertFalse(limiter.recordFailure("peer-new").allowed, "the global ceiling caps unique peers too")
    }

    @Test
    fun trackedPeerMapStaysBounded() {
        val limiter = FailedAuthLimiter(maxPeers = 4, maxFailuresPerPeer = 1, maxFailuresGlobal = 1_000_000)
        // Far more distinct source addresses than the tracked-peer capacity: the map must never grow
        // past maxPeers (least-recently-seen eviction), so spoofed sources can't exhaust memory.
        repeat(1000) { index -> limiter.recordFailure("peer-$index") }
        // Nothing to assert on internal size directly; the guarantee is that this completes without
        // unbounded growth. A previously evicted peer is treated as new (its budget resets).
        assertTrue(limiter.recordFailure("peer-0").allowed)
    }
}
