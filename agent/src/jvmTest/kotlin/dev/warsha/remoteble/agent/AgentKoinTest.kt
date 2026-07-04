package dev.warsha.remoteble.agent

import dev.warsha.remoteble.agent.di.AgentConfig
import dev.warsha.remoteble.agent.di.agentModule
import kotlin.test.Test
import kotlin.test.assertNotNull
import org.koin.dsl.koinApplication
import org.koin.dsl.module

/**
 * Guards the agent's composition root: the [agentModule] graph must resolve an
 * [AgentWebSocketServer] (AgentBackend -> BleBackend) so a future constructor change
 * can't silently break the wiring. The leaf [BleBackend] is overridden with a fake so
 * the real Kable backend — which would touch the native radio — is never exercised.
 * The server is resolved, not started.
 */
class AgentKoinTest {

    @Test
    fun moduleResolvesServer() {
        val app = koinApplication {
            allowOverride(true)
            modules(
                agentModule(AgentConfig(port = 0)),
                module { single<BleBackend> { FakeBleBackend() } },
            )
        }
        try {
            assertNotNull(app.koin.get<AgentWebSocketServer>())
        } finally {
            app.close()
        }
    }
}
