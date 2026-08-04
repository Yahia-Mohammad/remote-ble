package dev.warsha.remoteble.agent

import dev.warsha.remoteble.log.Logger
import dev.warsha.remoteble.protocol.AgentEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Per-connection fair admission to the shared outbound transport.  This deliberately stops at
 * [emit]: replies, observations and the transport itself keep their existing best-effort policy.
 */
internal class ScanOutboundArbiter(
    scope: CoroutineScope,
    private val emit: suspend (AgentEvent) -> Unit,
    private val mailboxCapacity: Int = DEFAULT_MAILBOX_CAPACITY,
) {
    companion object {
        /** Steady-state headroom; guaranteed-mode replay reserves additional capacity. */
        const val DEFAULT_MAILBOX_CAPACITY: Int = 64
    }
    internal class Sink internal constructor(
        internal val scanId: Long,
        internal val events: Channel<AgentEvent>,
    )

    private val lock = Mutex()
    private val sinks = linkedMapOf<Long, Sink>()
    private val wake = Channel<Unit>(capacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private var nextScanId: Long? = null

    /**
     * Survives a failing [emit].
     *
     * Without the guard, one throw out of `emit` — a transport write error, a codec or translator
     * failure — ends this coroutine for good, and because it is the *only* path scan events take to
     * the socket, that connection silently never receives another advertisement. Nothing logs, the
     * client is not told, and replies keep working (they call `outgoing` directly), so the failure
     * presents as "scanning stopped working" with no evidence anywhere. A dropped event is
     * acceptable — scan results are best-effort — but a dropped *delivery mechanism* is not.
     */
    private val worker: Job = scope.launch {
        for (ignored in wake) {
            try {
                drainRound()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                Logger.warn(LogTags.SERVER) { "scan outbound round failed, delivery continues: ${failure.message}" }
            }
        }
    }

    /**
     * Steady-state depth, and deliberately **suspending** rather than drop-newest.
     *
     * Drop-newest is applied once, upstream, where the coordinator's physical fan-out writes into
     * a logical scan's single bounded reservation — that is the hop that must never block, because
     * blocking it would let one slow connection slow the radio for every client. This hop is
     * behind that reservation, so making it suspend costs nothing and buys the property that a
     * full replay burst is carried through rather than needing a second copy of the reservation
     * here. A closed sink surfaces to the producer as `ClosedSendChannelException`, which
     * `BleAgent.deliverScanEvent` treats as "this scan's delivery ended".
     */
    suspend fun register(scanId: Long): Sink = lock.withLock {
        val sink = Sink(scanId, Channel(mailboxCapacity))
        sinks.put(scanId, sink)?.events?.close()
        Logger.debug(LogTags.SERVER) { "scan sink registered [scanId=$scanId sinks=${sinks.size}]" }
        sink
    }

    fun signal() {
        wake.trySend(Unit)
    }

    suspend fun unregister(sink: Sink) = lock.withLock {
        if (sinks[sink.scanId] === sink) sinks.remove(sink.scanId)
        sink.events.close()
    }

    fun close() {
        worker.cancel()
        wake.close()
    }

    private suspend fun drainRound() {
        val round = lock.withLock {
            val ordered = sinks.values.toList()
            val pivot = nextScanId?.let { id -> ordered.indexOfFirst { it.scanId == id } } ?: 0
            if (pivot <= 0) ordered else ordered.drop(pivot) + ordered.take(pivot)
        }
        var delivered = false
        round.forEach { sink ->
            val event = sink.events.tryReceive().getOrNull() ?: return@forEach
            val current = lock.withLock { sinks[sink.scanId] === sink }
            if (!current) return@forEach
            // Per-sink rather than per-round: one scan whose emit fails must not cost the other
            // logical scans their turn in this round. The event itself is already taken from the
            // mailbox and is lost, which is the best-effort contract scan results carry anyway.
            try {
                emit(event)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                Logger.warn(LogTags.SERVER) {
                    "dropped a scan event for scanId=${sink.scanId}: ${failure.message}"
                }
                return@forEach
            }
            delivered = true
            nextScanId = round.getOrNull((round.indexOf(sink) + 1) % round.size)?.scanId
        }
        // A round that wakes with sinks registered and delivers nothing is the signature of scan
        // delivery stalling: either every mailbox was empty when the producer believed it had just
        // filled one, or the sink identity check rejected them all. Cheap to log and it is the one
        // observation that distinguishes "the collector never produced" from "the arbiter never
        // drained" after the fact.
        if (!delivered && round.isNotEmpty()) {
            Logger.debug(LogTags.SERVER) { "scan outbound round woke with ${round.size} sink(s) and delivered nothing" }
        }
        // One extra empty turn is harmless; it avoids polling while ensuring a non-empty mailbox
        // is revisited after every other current logical scan had one opportunity to send.
        if (delivered) signal()
    }
}
