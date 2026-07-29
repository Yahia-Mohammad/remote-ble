package dev.warsha.remoteble.e2e

import dev.warsha.remoteble.client.DefaultAgentSession
import dev.warsha.remoteble.client.WebSocketAgentTransport
import dev.warsha.remoteble.client.defaultWebSocketHttpClient
import dev.warsha.remoteble.protocol.AgentEvent
import dev.warsha.remoteble.protocol.Capabilities
import dev.warsha.remoteble.protocol.CborProtocolCodec
import dev.warsha.remoteble.protocol.ErrorKind
import dev.warsha.remoteble.protocol.Op
import dev.warsha.remoteble.protocol.OpResult
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Radio-state probe for gap 17: negotiates `radio.state`, prints every `AgentEvent.RadioState` the
 * agent pushes, and periodically attempts a scan so the *solicited* half is visible next to the
 * unsolicited one.
 *
 * Built to classify rather than to pass/fail (Rig B method note 12): the interesting outcomes are
 * "capability refused", "event says OFF", "scan rejected RADIO_OFF", and "scan accepted, zero
 * results" — the last being the pre-fix behaviour this exists to distinguish from the others.
 *
 * Usage: java ... dev.warsha.remoteble.e2e.RadioStateMainKt [ws-url] [seconds]
 */
fun main(args: Array<String>): Unit = runBlocking {
    val url = args.getOrNull(0) ?: "ws://localhost:8080/agent"
    val window = (args.getOrNull(1)?.toIntOrNull() ?: 30).seconds
    val token = System.getenv("REMOTE_BLE_TOKEN")

    println("== RemoteBle radio-state probe ==")
    println("agent : $url")
    println("window: $window")

    val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    val http = defaultWebSocketHttpClient()
    val session = DefaultAgentSession(
        WebSocketAgentTransport(url, scope, http, authToken = { token }),
        CborProtocolCodec(),
        scope,
        clientCapabilities = setOf(Capabilities.RADIO_STATE),
    )

    try {
        val negotiated = withTimeoutOrNull(10.seconds) { session.capabilities.filterNotNull().first() }
        println("negotiated: $negotiated")
        println("radio.state supported: ${negotiated?.contains(Capabilities.RADIO_STATE)}")

        session.events()
            .filterIsInstance<AgentEvent.RadioState>()
            .onEach { println("[event] radio -> ${it.state}") }
            .launchIn(scope)

        var elapsed = 0
        while (elapsed < window.inWholeSeconds) {
            when (val result = session.request(Op.ScanStart(scanId = 1, filters = emptyList()))) {
                is OpResult.Ok -> println("[scan ] accepted (a client without radio.state sees exactly this)")
                is OpResult.Err -> {
                    val marker = if (result.error.kind == ErrorKind.RADIO_OFF) "<-- gap 17 fixed" else ""
                    println("[scan ] rejected ${result.error.kind}: ${result.error.message} $marker")
                }
            }
            session.request(Op.ScanStop(scanId = 1))
            kotlinx.coroutines.delay(5.seconds)
            elapsed += 5
        }
    } finally {
        session.close()
        http.close()
        scope.cancel()
    }
}
