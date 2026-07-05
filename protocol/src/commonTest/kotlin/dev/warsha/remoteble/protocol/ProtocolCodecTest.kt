package dev.warsha.remoteble.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Round-trips every wire variant (encode -> decode -> assert equality) through
 * both the production CBOR codec and the debug JSON codec, including ByteArray
 * payloads and empty/edge cases. This freezes the wire contract: a change to any
 * type or `@SerialName` that breaks compatibility shows up here.
 */
class ProtocolCodecTest {

    private val codecs: List<ProtocolCodec> = listOf(CborProtocolCodec(), JsonProtocolCodec())

    private fun assertRoundTrips(frame: Frame) {
        for (codec in codecs) {
            val name = codec::class.simpleName
            val encoded = codec.encode(frame)
            assertTrue(encoded.isNotEmpty(), "[$name] encoded bytes should be non-empty")
            val decoded = codec.decode(encoded)
            assertEquals(frame, decoded, "[$name] round-trip mismatch for $frame")
        }
    }

    private val dev = DeviceHandle("AA:BB:CC:DD:EE:FF")
    private val char = CharRef("0000180d-0000-1000-8000-00805f9b34fb", "00002a37-0000-1000-8000-00805f9b34fb")
    private val charWithInstance = CharRef("svc", "chr", instance = 3)

    // ---- Command / Op variants ----

    @Test
    fun scanStart_withAndWithoutFilters() {
        assertRoundTrips(Command(1, Op.ScanStart(scanId = 7)))
        assertRoundTrips(
            Command(
                2,
                Op.ScanStart(
                    scanId = 8,
                    filters = listOf(ScanFilter(service = "180d"), ScanFilter(name = "HRM"), ScanFilter()),
                ),
            ),
        )
    }

    @Test
    fun scanStop() = assertRoundTrips(Command(3, Op.ScanStop(scanId = 7)))

    @Test
    fun connect_disconnect_discover() {
        assertRoundTrips(Command(4, Op.Connect(dev)))
        assertRoundTrips(Command(5, Op.Disconnect(dev)))
        assertRoundTrips(Command(6, Op.Discover(dev)))
    }

    @Test
    fun read_withInstanceIndex() {
        assertRoundTrips(Command(7, Op.Read(dev, char)))
        assertRoundTrips(Command(8, Op.Read(dev, charWithInstance)))
    }

    @Test
    fun write_emptyAndNonEmpty_withAndWithoutResponse() {
        assertRoundTrips(Command(9, Op.Write(dev, char, byteArrayOf(), withResponse = true)))
        assertRoundTrips(
            Command(10, Op.Write(dev, char, byteArrayOf(0x00, 0x7f, -0x80, -0x01), withResponse = false)),
        )
        // 512-byte payload to exercise larger writes.
        assertRoundTrips(
            Command(11, Op.Write(dev, char, ByteArray(512) { (it % 256).toByte() }, withResponse = true)),
        )
    }

    @Test
    fun observe_startStop() {
        assertRoundTrips(Command(12, Op.ObserveStart(subId = 42, device = dev, char = char)))
        assertRoundTrips(Command(13, Op.ObserveStop(subId = 42)))
    }

    @Test
    fun requestMtu() = assertRoundTrips(Command(14, Op.RequestMtu(dev, mtu = 247)))

    @Test
    fun readWriteDescriptor() {
        val desc = DescRef(service = "180d", characteristic = "2a37", descriptor = "2902")
        assertRoundTrips(Command(15, Op.ReadDescriptor(dev, desc)))
        assertRoundTrips(Command(16, Op.ReadDescriptor(dev, desc.copy(instance = 2))))
        assertRoundTrips(Command(17, Op.WriteDescriptor(dev, desc, byteArrayOf(0x01, 0x00))))
        assertRoundTrips(Command(18, Op.WriteDescriptor(dev, desc, byteArrayOf())))
    }

    @Test
    fun pairUnpair() {
        assertRoundTrips(Command(19, Op.Pair(dev)))
        assertRoundTrips(Command(20, Op.Unpair(dev)))
    }

    @Test
    fun requestConnectionPriority_everyPriority() {
        ConnPriority.entries.forEachIndexed { i, p ->
            assertRoundTrips(Command(30L + i, Op.RequestConnectionPriority(dev, p)))
        }
    }

    @Test
    fun reply_okBond_everyState() {
        BleBondState.entries.forEach { state ->
            assertRoundTrips(Reply(21, OpResult.Ok(ResultPayload.Bond(state))))
        }
    }

    @Test
    fun event_bondState_everyStateWithAndWithoutReason() {
        BleBondState.entries.forEach { state ->
            assertRoundTrips(Event(AgentEvent.BondState(device = dev, state = state)))
        }
        assertRoundTrips(
            Event(
                AgentEvent.BondState(
                    device = dev,
                    state = BleBondState.NONE,
                    reason = AgentError(ErrorKind.GATT_ERROR, message = "bond removed by OS"),
                ),
            ),
        )
    }

    @Test
    fun command_edgeCids() {
        assertRoundTrips(Command(0, Op.Connect(dev)))
        assertRoundTrips(Command(Long.MAX_VALUE, Op.Connect(dev)))
    }

    // ---- Reply / OpResult / ResultPayload variants ----

    @Test
    fun reply_okNoPayload() = assertRoundTrips(Reply(1, OpResult.Ok(payload = null)))

    @Test
    fun reply_okBytes_emptyAndNonEmpty() {
        assertRoundTrips(Reply(2, OpResult.Ok(ResultPayload.Bytes(byteArrayOf()))))
        assertRoundTrips(Reply(3, OpResult.Ok(ResultPayload.Bytes(byteArrayOf(1, 2, 3, -1, -128)))))
    }

    @Test
    fun reply_okServices_emptyAndPopulated() {
        assertRoundTrips(Reply(4, OpResult.Ok(ResultPayload.Services(emptyList()))))
        assertRoundTrips(
            Reply(
                5,
                OpResult.Ok(
                    ResultPayload.Services(
                        listOf(
                            ServiceNode(
                                uuid = "180d",
                                characteristics = listOf(
                                    CharNode(uuid = "2a37", properties = 0x10),
                                    CharNode(
                                        uuid = "2a38",
                                        properties = 0x0a,
                                        descriptors = listOf("2902", "2901"),
                                    ),
                                ),
                            ),
                            ServiceNode(uuid = "180f", characteristics = emptyList()),
                        ),
                    ),
                ),
            ),
        )
    }

    @Test
    fun reply_okMtu() = assertRoundTrips(Reply(6, OpResult.Ok(ResultPayload.Mtu(mtu = 185))))

    @Test
    fun reply_err_minimalAndFull() {
        assertRoundTrips(Reply(7, OpResult.Err(AgentError(ErrorKind.TIMEOUT))))
        assertRoundTrips(
            Reply(
                8,
                OpResult.Err(AgentError(ErrorKind.GATT_ERROR, gattStatus = 133, message = "GATT_ERROR (0x85)")),
            ),
        )
    }

    @Test
    fun reply_err_everyErrorKind() {
        ErrorKind.entries.forEachIndexed { i, kind ->
            assertRoundTrips(Reply(100L + i, OpResult.Err(AgentError(kind, message = kind.name))))
        }
    }

    // ---- Event / AgentEvent variants ----

    @Test
    fun event_scanResult_minimalAndFull() {
        assertRoundTrips(
            Event(AgentEvent.ScanResult(scanId = 7, advertisement = AdvertisementDto(device = dev, rssi = -60))),
        )
        assertRoundTrips(
            Event(
                AgentEvent.ScanResult(
                    scanId = 7,
                    advertisement = AdvertisementDto(
                        device = dev,
                        name = "Heart Rate Ünïcödë",
                        rssi = -42,
                        serviceUuids = listOf("180d", "180f"),
                        manufacturerData = mapOf(
                            0x004C to byteArrayOf(0x02, 0x15),
                            0x0059 to byteArrayOf(),
                        ),
                    ),
                ),
            ),
        )
    }

    @Test
    fun event_notification_emptyAndNonEmpty() {
        assertRoundTrips(Event(AgentEvent.Notification(subId = 42, value = byteArrayOf())))
        assertRoundTrips(
            Event(AgentEvent.Notification(subId = 42, value = ByteArray(244) { (it and 0xff).toByte() })),
        )
    }

    @Test
    fun event_connectionState_everyStateWithAndWithoutReason() {
        BleConnState.entries.forEach { state ->
            assertRoundTrips(Event(AgentEvent.ConnectionState(device = dev, state = state)))
        }
        assertRoundTrips(
            Event(
                AgentEvent.ConnectionState(
                    device = dev,
                    state = BleConnState.DISCONNECTED,
                    reason = AgentError(ErrorKind.DISCONNECTED, gattStatus = 19, message = "peer terminated"),
                ),
            ),
        )
    }

    // ---- Handshake (ClientHello / ServerHello) ----

    @Test
    fun clientHello_defaultsAndPopulated() {
        assertRoundTrips(ClientHello())
        assertRoundTrips(
            ClientHello(
                minVersion = 1,
                maxVersion = 2,
                capabilities = setOf(Capabilities.DESCRIPTORS, Capabilities.PAIRING),
            ),
        )
    }

    @Test
    fun clientHello_withIdentifierFormat() {
        // The 0.8.0 handshake field is optional (null default) and round-trips for every format.
        assertRoundTrips(ClientHello(identifierFormat = null))
        for (format in IdentifierFormat.entries) {
            assertRoundTrips(
                ClientHello(
                    capabilities = setOf(Capabilities.IDENTIFIER_TRANSLATION),
                    identifierFormat = format,
                ),
            )
        }
    }

    @Test
    fun serverHello_defaultsAndPopulated() {
        assertRoundTrips(ServerHello())
        assertRoundTrips(
            ServerHello(
                version = PROTOCOL_VERSION,
                capabilities = setOf(Capabilities.DESCRIPTORS),
                agentInfo = "blue-falcon/macOS",
            ),
        )
    }

    @Test
    fun event_scanResultBatch_emptyAndPopulated() {
        assertRoundTrips(Event(AgentEvent.ScanResultBatch(scanId = 7, advertisements = emptyList())))
        assertRoundTrips(
            Event(
                AgentEvent.ScanResultBatch(
                    scanId = 7,
                    advertisements = listOf(
                        AdvertisementDto(device = dev, rssi = -60),
                        AdvertisementDto(device = DeviceHandle("11:22"), name = "B", rssi = -70, serviceUuids = listOf("180f")),
                    ),
                ),
            ),
        )
    }

    @Test
    fun event_slotState() {
        assertRoundTrips(Event(AgentEvent.SlotState(free = 0, total = 4)))
        assertRoundTrips(Event(AgentEvent.SlotState(free = 3, total = 4)))
    }

    // ---- Cross-cutting ----

    @Test
    fun cborIsMoreCompactThanJson_forBinaryHeavyFrame() {
        val frame = Event(AgentEvent.Notification(subId = 1, value = ByteArray(256) { (it and 0xff).toByte() }))
        val cbor = CborProtocolCodec().encode(frame).size
        val json = JsonProtocolCodec().encode(frame).size
        assertTrue(cbor < json, "expected CBOR ($cbor) < JSON ($json) for binary-heavy payload")
    }
}
