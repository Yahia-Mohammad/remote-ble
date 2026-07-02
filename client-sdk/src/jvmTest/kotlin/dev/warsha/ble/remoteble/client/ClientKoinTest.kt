package dev.warsha.ble.remoteble.client

import dev.warsha.ble.remoteble.client.di.RemoteBleClientConfig
import dev.warsha.ble.remoteble.client.di.remoteBleClientModule
import dev.warsha.ble.remoteble.protocol.ScanFilter
import io.ktor.client.HttpClient
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import org.koin.core.parameter.parametersOf
import org.koin.dsl.koinApplication

/**
 * Guards the client SDK's composition root: [remoteBleClientModule] must resolve the
 * public entry points apps use — [AgentSession], [RemotePeripheralFactory], and the
 * parameterized [RemoteScanner]. `autoReconnect = false` so the single failed connect
 * the session kicks off on creation does not spin a backoff loop; the bound scope and
 * HttpClient are torn down afterward.
 */
class ClientKoinTest {

    @Test
    fun moduleResolvesClientGraph() {
        val app = koinApplication {
            modules(
                remoteBleClientModule(
                    RemoteBleClientConfig(url = "ws://127.0.0.1:1/agent", autoReconnect = false),
                ),
            )
        }
        val koin = app.koin
        try {
            assertNotNull(koin.get<AgentSession>())
            assertNotNull(koin.get<RemotePeripheralFactory>())
            assertNotNull(koin.get<RemoteScanner> { parametersOf(emptyList<ScanFilter>()) })
        } finally {
            koin.get<CoroutineScope>().cancel()
            koin.get<HttpClient>().close()
            app.close()
        }
    }
}
