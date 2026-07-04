package dev.warsha.remoteble.client

import dev.warsha.remoteble.protocol.AdvertisementDto
import dev.warsha.remoteble.protocol.AgentEvent
import dev.warsha.remoteble.protocol.Op
import dev.warsha.remoteble.protocol.ScanFilter
import dev.warsha.remoteble.protocol.orThrow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.channelFlow

/**
 * The remote scan source: streams protocol-level [AdvertisementDto]s for an
 * [AgentSession]. The Kable-facing [RemoteScanner] adapts this to Kable's `Scanner`.
 */
class RemoteScanSource(private val session: AgentSession) {

    /**
     * Opens a scan on collect and tears it down on cancel. Streams [AdvertisementDto]s
     * tagged with this scan's id; a best-effort `scan.stop` is issued on cancellation.
     */
    fun advertisements(filters: List<ScanFilter> = emptyList()): Flow<AdvertisementDto> = channelFlow {
        val scanId = session.nextStreamId()
        // Handle both single results and coalesced batches (the agent sends one form or the
        // other for a given scan, depending on the negotiated `scan.batch` capability).
        val pump = session.events()
            .onEach { event ->
                when (event) {
                    is AgentEvent.ScanResult -> if (event.scanId == scanId) send(event.advertisement)
                    is AgentEvent.ScanResultBatch -> if (event.scanId == scanId) {
                        event.advertisements.forEach { send(it) }
                    }
                    else -> {}
                }
            }
            .launchIn(this)
        session.request(Op.ScanStart(scanId, filters)).orThrow()
        awaitClose {
            pump.cancel()
            session.fireAndForget(Op.ScanStop(scanId))
        }
    }
}
