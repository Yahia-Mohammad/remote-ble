import dev.warsha.remoteble.client.AgentTransport
import dev.warsha.remoteble.client.WebSocketAgentTransport
import dev.warsha.remoteble.client.pinnedWebSocketHttpClient
import dev.warsha.remoteble.protocol.AgentFingerprint
import kotlinx.coroutines.CoroutineScope

/** The Apple pinned client, which is not common API: only the JVM, Android and Apple targets have it. */
fun pinnedTransport(scope: CoroutineScope): AgentTransport = WebSocketAgentTransport(
    "wss://127.0.0.1:8080/agent",
    scope,
    pinnedWebSocketHttpClient(AgentFingerprint.parse("sha256:" + "0".repeat(64))),
)
