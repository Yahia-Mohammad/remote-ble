package dev.warsha.remoteble.client

import dev.warsha.remoteble.agent.AgentWebSocketServer
import dev.warsha.remoteble.agent.BleAgentBackend
import dev.warsha.remoteble.agent.ClientCredentials
import dev.warsha.remoteble.agent.WritePolicy
import dev.warsha.remoteble.protocol.AgentException
import dev.warsha.remoteble.protocol.Capabilities
import dev.warsha.remoteble.protocol.CborProtocolCodec
import dev.warsha.remoteble.protocol.CharRef
import dev.warsha.remoteble.protocol.ClientHello
import dev.warsha.remoteble.protocol.Command
import dev.warsha.remoteble.protocol.DeviceHandle
import dev.warsha.remoteble.protocol.ErrorKind
import dev.warsha.remoteble.protocol.Frame
import dev.warsha.remoteble.protocol.Op
import dev.warsha.remoteble.protocol.OpResult
import dev.warsha.remoteble.protocol.Reply
import dev.warsha.remoteble.protocol.ServerHello
import dev.warsha.remoteble.protocol.CLIENT_ID_HEADER
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.websocket.Frame as WsFrame
import io.ktor.websocket.close
import io.ktor.websocket.readBytes
import io.ktor.websocket.send
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Proves U7's write policy is enforced **at the agent, independent of which client connects** —
 * the property the whole design exists for. The CLI's own `policy:` block is advisory (it lives in
 * a file the calling agent can edit), so the only thing worth testing here is that the agent itself
 * refuses a principal regardless of what code is driving the session.
 *
 * `remoteble-tools` (the CLI) lives in a separate, paused repository. This test therefore uses the
 * next strongest in-repo substitute: the SDK session and a raw WebSocket/CBOR client independently
 * drive the same matrix against the production agent. The external CLI matrix remains an integration
 * responsibility of that repository; policy enforcement itself is proven without either client
 * being able to influence it.
 */
class WritePolicyEndToEndTest {

    private val allowedChar = CharRef(
        service = "0000180d-0000-1000-8000-00805f9b34fb",
        characteristic = "00002a37-0000-1000-8000-00805f9b34fb",
    )
    private val deniedChar = allowedChar.copy(characteristic = "00002a38-0000-1000-8000-00805f9b34fb")

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val httpClient = defaultWebSocketHttpClient()

    @AfterTest
    fun tearDown() {
        httpClient.close()
        scope.cancel()
    }

    @Test
    fun theSamePolicyMatrixIsEnforcedForSdkAndRawWebSocketClients() = runBlocking {
        val writePolicy = WritePolicy.decode(
            """{"version":1,"principals":{
                "lab-a":{"writes":[{"service":"${allowedChar.service}","characteristic":"${allowedChar.characteristic}"}]},
                "lab-b":{"writes":[{"service":"${allowedChar.service}","characteristic":"${allowedChar.characteristic}"}]}
            }}""",
            knownPrincipals = setOf("lab-a", "lab-b"),
        )
        val server = AgentWebSocketServer(
            port = 0,
            backend = BleAgentBackend(StubBleBackend(), writePolicy = writePolicy),
            credentials = ClientCredentials.of(mapOf("lab-a" to "secret-a", "lab-b" to "secret-b")),
        ).also { it.startAndAwaitReady() }
        val port = server.resolvedPort
        try {
            // Sequential ownership: disconnect releases this test device immediately, so both
            // independently implemented clients exercise the same policy matrix on one server.
            suspend fun sdkMatrix() {
                val session = DefaultAgentSession(
                    WebSocketAgentTransport(
                        "ws://localhost:$port/agent",
                        scope,
                        httpClient,
                        authToken = { "secret-a" },
                    ),
                    CborProtocolCodec(),
                    scope,
                    clientCapabilities = setOf(Capabilities.WRITE_POLICY),
                )
                withTimeout(10.seconds) { session.transportState.first { it == TransportState.CONNECTED } }
                val peripheral = RemoteGattClient(DeviceHandle(StubBleBackend.DEVICE), session)
                peripheral.connect()
                try {
                    peripheral.write(allowedChar, byteArrayOf(0x01), withResponse = true)
                    val denied = try {
                        peripheral.write(deniedChar, byteArrayOf(0x01), withResponse = true)
                        null
                    } catch (e: AgentException) {
                        e.error.kind
                    }
                    assertEquals(ErrorKind.POLICY_DENIED, denied)
                } catch (e: AgentException) {
                    throw AssertionError("the allowed SDK write was refused", e)
                } finally {
                    peripheral.disconnect()
                }
            }

            suspend fun receive(socket: DefaultClientWebSocketSession): Frame = withTimeout(10.seconds) {
                while (true) {
                    when (val frame = socket.incoming.receive()) {
                        is WsFrame.Binary -> return@withTimeout CborProtocolCodec().decode(frame.readBytes())
                        is WsFrame.Close -> error("raw client closed before the expected protocol frame")
                        else -> Unit
                    }
                }
                error("unreachable")
            }

            suspend fun request(socket: DefaultClientWebSocketSession, cid: Long, op: Op): OpResult {
                socket.send(WsFrame.Binary(true, CborProtocolCodec().encode(Command(cid, op))))
                while (true) {
                    val frame = receive(socket)
                    if (frame is Reply && frame.cid == cid) return frame.result
                }
            }

            sdkMatrix()

            val raw = httpClient.webSocketSession(urlString = "ws://localhost:$port/agent") {
                header(HttpHeaders.Authorization, "Bearer secret-b")
                header(CLIENT_ID_HEADER, "raw-policy-client")
            }
            try {
                raw.send(WsFrame.Binary(true, CborProtocolCodec().encode(ClientHello(capabilities = setOf(Capabilities.WRITE_POLICY)))))
                assertIs<ServerHello>(receive(raw))
                assertIs<OpResult.Ok>(request(raw, 1, Op.Connect(DeviceHandle(StubBleBackend.DEVICE))))
                assertIs<OpResult.Ok>(request(raw, 2, Op.Write(DeviceHandle(StubBleBackend.DEVICE), allowedChar, byteArrayOf(0x01), true)))
                val denied = assertIs<OpResult.Err>(
                    request(raw, 3, Op.Write(DeviceHandle(StubBleBackend.DEVICE), deniedChar, byteArrayOf(0x01), true)),
                )
                assertEquals(ErrorKind.POLICY_DENIED, denied.error.kind)
                assertIs<OpResult.Ok>(request(raw, 4, Op.Disconnect(DeviceHandle(StubBleBackend.DEVICE))))
            } finally {
                raw.close()
            }
        } finally {
            server.stop()
        }
        Unit
    }
}
