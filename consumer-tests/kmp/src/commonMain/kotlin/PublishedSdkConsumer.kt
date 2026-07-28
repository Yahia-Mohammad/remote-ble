import dev.warsha.remoteble.client.di.RemoteBleClientConfig
import dev.warsha.remoteble.protocol.ProtocolVersionSelection
import dev.warsha.remoteble.protocol.selectProtocolVersion

/**
 * Compilation of this file is the clean-consumer assertion. It deliberately uses public types from
 * both the SDK and its published protocol dependency without relying on this repository's projects.
 */
fun configuredAgentUrl(): String {
    val selection = selectProtocolVersion(minVersion = 1, maxVersion = 1)
    check(selection is ProtocolVersionSelection.Selected)
    return RemoteBleClientConfig(url = "ws://127.0.0.1:8080/agent").url
}
