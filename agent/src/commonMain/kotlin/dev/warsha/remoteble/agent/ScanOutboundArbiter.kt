package dev.warsha.remoteble.agent

import dev.warsha.remoteble.protocol.AgentEvent
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

    private val worker: Job = scope.launch {
        for (ignored in wake) drainRound()
    }

    suspend fun register(scanId: Long): Sink = lock.withLock {
        val sink = Sink(
            scanId,
            Channel(mailboxCapacity, onBufferOverflow = BufferOverflow.DROP_LATEST),
        )
        sinks.put(scanId, sink)?.events?.close()
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
            emit(event)
            delivered = true
            nextScanId = round.getOrNull((round.indexOf(sink) + 1) % round.size)?.scanId
        }
        // One extra empty turn is harmless; it avoids polling while ensuring a non-empty mailbox
        // is revisited after every other current logical scan had one opportunity to send.
        if (delivered) signal()
    }
}
