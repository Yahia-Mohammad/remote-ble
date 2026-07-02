package dev.warsha.ble.remoteble.client.di

import dev.warsha.ble.remoteble.client.AgentSession
import dev.warsha.ble.remoteble.client.AgentTransport
import dev.warsha.ble.remoteble.client.Backoff
import dev.warsha.ble.remoteble.client.DefaultAgentSession
import dev.warsha.ble.remoteble.client.RemotePeripheralFactory
import dev.warsha.ble.remoteble.client.RemoteScanner
import dev.warsha.ble.remoteble.client.WebSocketAgentTransport
import dev.warsha.ble.remoteble.client.defaultWebSocketHttpClient
import dev.warsha.ble.remoteble.client.DefaultDispatcherProvider
import dev.warsha.ble.remoteble.client.DispatcherProvider
import dev.warsha.ble.remoteble.protocol.Capabilities
import dev.warsha.ble.remoteble.protocol.CborProtocolCodec
import dev.warsha.ble.remoteble.protocol.ProtocolCodec
import dev.warsha.ble.remoteble.protocol.ScanFilter
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * App-supplied configuration for the client SDK — the values that can't be defaulted
 * because they describe a particular agent (endpoint, credential, reconnect policy).
 */
public data class RemoteBleClientConfig(
    val url: String,
    val authToken: String? = null,
    val autoReconnect: Boolean = true,
    val backoff: Backoff = Backoff(),
)

/**
 * An **optional** Koin module that assembles the client SDK at a composition root.
 * It simply calls the same public constructors the library already exposes via `get()`;
 * the SDK internals never reference Koin, so apps that prefer manual construction can
 * ignore this entirely.
 *
 * Compose it into your own `startKoin { modules(remoteBleClientModule(config)) }` and
 * resolve [AgentSession] / [RemotePeripheralFactory] (and a parameterized [RemoteScanner]).
 *
 * Override the [CoroutineScope] binding in an app that owns a lifecycle scope (e.g. a
 * ViewModel/screen scope) so the session and transport coroutines are torn down with it —
 * the default is a process-lifetime supervisor scope.
 */
public fun remoteBleClientModule(config: RemoteBleClientConfig): Module = module {
    single { config }
    single<DispatcherProvider> { DefaultDispatcherProvider }
    single<CoroutineScope> { CoroutineScope(SupervisorJob() + get<DispatcherProvider>().default) }
    single<HttpClient> { defaultWebSocketHttpClient() }
    single<ProtocolCodec> { CborProtocolCodec() }
    single<AgentTransport> {
        WebSocketAgentTransport(
            config.url,
            get(),
            get(),
            authToken = config.authToken,
            autoReconnect = config.autoReconnect,
            backoff = config.backoff,
        )
    }
    single<AgentSession> {
        DefaultAgentSession(
            get(), get(), get(),
            clientCapabilities = setOf(
                Capabilities.DESCRIPTORS,
                Capabilities.PAIRING,
                Capabilities.CONNECTION_SLOTS,
                Capabilities.CONN_PRIORITY,
                Capabilities.SCAN_BATCH,
            ),
        )
    }
    factory { RemotePeripheralFactory(get(), get()) }
    // Per-scan: the filter list is supplied at the call site via Koin parameters,
    // e.g. koin.get<RemoteScanner> { parametersOf(filters) }.
    factory { (filters: List<ScanFilter>) -> RemoteScanner(get(), filters) }
}
