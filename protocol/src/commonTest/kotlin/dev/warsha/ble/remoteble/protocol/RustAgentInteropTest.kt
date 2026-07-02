package dev.warsha.ble.remoteble.protocol

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Cross-language wire-compatibility guard for the native Rust agent (`agent-rs`).
 *
 * The byte arrays below are the *exact* CBOR the Rust agent emits (via `ciborium`)
 * for frames it sends to this client. Unlike `kotlinx.serialization` — which writes
 * **indefinite-length** maps/arrays (`0xBF…0xFF` / `0x9F…0xFF`) — `ciborium` writes
 * **definite-length** ones (`0xA3`, `0x82`, `0x84`, …). This test proves the Kotlin
 * decoder reads that definite-length form, and that the agent's field names, enum
 * encodings (SCREAMING_SNAKE_CASE strings), `gattStatus` casing, and signed-byte
 * `ByteArray` encoding all match the Kotlin contract.
 *
 * If this breaks, the Rust agent and Kotlin client have drifted apart on the wire.
 * Regenerate the hex from agent-rs `temp_dump_rust_hex` (or any conforming agent).
 */
class RustAgentInteropTest {

    private val cbor = CborProtocolCodec()

    private fun hex(s: String): ByteArray =
        ByteArray(s.length / 2) { ((s[it * 2].digitToInt(16) shl 4) or s[it * 2 + 1].digitToInt(16)).toByte() }

    private fun assertDecodes(hexStr: String, expected: Frame) =
        assertEquals(expected, cbor.decode(hex(hexStr)))

    private val dev = DeviceHandle("AA:BB:CC:DD:EE:FF")

    @Test
    fun serverHello() = assertDecodes(
        "826c7365727665725f68656c6c6fa36776657273696f6e016c6361706162696c6974696573816b64657363726970746f7273696167656e74496e666f781852656d6f7465426c652d4167656e742d525320302e312e30",
        ServerHello(version = 1, capabilities = setOf("descriptors"), agentInfo = "RemoteBle-Agent-RS 0.1.0"),
    )

    @Test
    fun replyOkNull() = assertDecodes(
        "82657265706c79a2636369640166726573756c7482626f6ba0",
        Reply(1, OpResult.Ok(null)),
    )

    @Test
    fun replyOkMtu() = assertDecodes(
        "82657265706c79a2636369640666726573756c7482626f6ba1677061796c6f616482636d7475a1636d747518b9",
        Reply(6, OpResult.Ok(ResultPayload.Mtu(185))),
    )

    @Test
    fun replyOkBytes_signedHighBytes() = assertDecodes(
        "82657265706c79a2636369640266726573756c7482626f6ba1677061796c6f616482656279746573a16576616c75658400187f387f20",
        Reply(2, OpResult.Ok(ResultPayload.Bytes(byteArrayOf(0x00, 0x7f, -0x80, -0x01)))),
    )

    @Test
    fun replyErr_gattStatus() = assertDecodes(
        "82657265706c79a2636369640866726573756c748263657272a1656572726f72a3646b696e646a474154545f4552524f526a676174745374617475731885676d6573736167656178",
        Reply(8, OpResult.Err(AgentError(ErrorKind.GATT_ERROR, gattStatus = 133, message = "x"))),
    )

    @Test
    fun eventConnState() = assertDecodes(
        "82656576656e74a1656576656e74826a636f6e6e2e7374617465a266646576696365a16576616c75657141413a42423a43433a44443a45453a464665737461746569434f4e4e4543544544",
        Event(AgentEvent.ConnectionState(dev, BleConnState.CONNECTED)),
    )

    @Test
    fun eventNotification_signedHighBytes() = assertDecodes(
        "82656576656e74a1656576656e74826c6e6f74696669636174696f6ea2657375624964182a6576616c756582387f20",
        Event(AgentEvent.Notification(42, byteArrayOf(-0x80, -0x01))),
    )

    @Test
    fun eventSlotState() = assertDecodes(
        "82656576656e74a1656576656e74826a636f6e6e2e736c6f7473a264667265650365746f74616c04",
        Event(AgentEvent.SlotState(free = 3, total = 4)),
    )
}
