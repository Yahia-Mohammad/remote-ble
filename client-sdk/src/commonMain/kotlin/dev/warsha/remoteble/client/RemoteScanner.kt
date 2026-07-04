package dev.warsha.remoteble.client

import dev.warsha.remoteble.protocol.ScanFilter
import com.juul.kable.Scanner
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * A Kable [Scanner] backed by a remote agent. App code that collects
 * [advertisements] cannot tell this from a local scanner; each emitted
 * [RemoteAdvertisement] carries the agent handle for [RemotePeripheral] creation.
 */
public class RemoteScanner(
    session: AgentSession,
    filters: List<ScanFilter> = emptyList(),
) : Scanner<RemoteAdvertisement> {

    private val source = RemoteScanSource(session)

    override val advertisements: Flow<RemoteAdvertisement> =
        source.advertisements(filters).map(::RemoteAdvertisement)
}
