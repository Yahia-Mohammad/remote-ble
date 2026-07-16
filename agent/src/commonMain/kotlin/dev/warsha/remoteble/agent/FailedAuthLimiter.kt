package dev.warsha.remoteble.agent

import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * Fixed-memory failed-auth limiter for WebSocket upgrades. It deliberately tracks only a bounded
 * number of peers and evicts the least-recently-seen entry, so arbitrary source addresses cannot
 * turn rejected handshakes into an unbounded map. The return value also tells the caller when a
 * rate-limit log is due, keeping denial floods out of the operational log.
 */
@OptIn(ExperimentalTime::class)
internal class FailedAuthLimiter(
    private val maxPeers: Int = MAX_TRACKED_PEERS,
    private val maxFailuresPerPeer: Int = MAX_FAILURES_PER_PEER,
    private val maxFailuresGlobal: Int = MAX_FAILURES_GLOBAL,
    private val windowMillis: Long = WINDOW_MILLIS,
) {
    init {
        require(maxPeers > 0 && maxFailuresPerPeer > 0 && maxFailuresGlobal > 0 && windowMillis > 0)
    }

    data class Decision(val allowed: Boolean, val shouldLog: Boolean)

    private data class Peer(var failures: Int, var lastSeen: Long, var lastLimitedLog: Long?)

    private val lock = SynchronizedObject()
    private val peers = mutableMapOf<String, Peer>()
    private var windowStarted = now()
    private var globalFailures = 0

    fun recordFailure(peer: String): Decision = synchronized(lock) {
        val current = now()
        if (current - windowStarted >= windowMillis) {
            windowStarted = current
            globalFailures = 0
            peers.entries.removeAll { (_, state) -> current - state.lastSeen >= windowMillis }
        }
        val state = peers[peer] ?: run {
            if (peers.size >= maxPeers) {
                peers.minByOrNull { it.value.lastSeen }?.key?.let { peers.remove(it) }
            }
            Peer(failures = 0, lastSeen = current, lastLimitedLog = null).also { peers[peer] = it }
        }
        state.lastSeen = current
        if (globalFailures >= maxFailuresGlobal || state.failures >= maxFailuresPerPeer) {
            val shouldLog = state.lastLimitedLog?.let { current - it >= windowMillis } ?: true
            if (shouldLog) state.lastLimitedLog = current
            return@synchronized Decision(allowed = false, shouldLog = shouldLog)
        }
        state.failures++
        globalFailures++
        Decision(allowed = true, shouldLog = true)
    }

    private fun now(): Long = Clock.System.now().toEpochMilliseconds()

    private companion object {
        const val MAX_TRACKED_PEERS = 256
        const val MAX_FAILURES_PER_PEER = 5
        const val MAX_FAILURES_GLOBAL = 64
        const val WINDOW_MILLIS = 60_000L
    }
}
