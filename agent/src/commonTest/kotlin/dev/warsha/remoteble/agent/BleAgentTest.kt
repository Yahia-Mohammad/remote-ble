package dev.warsha.remoteble.agent

import dev.warsha.remoteble.protocol.AdvertisementDto
import dev.warsha.remoteble.protocol.AgentEvent
import dev.warsha.remoteble.protocol.BleBondState
import dev.warsha.remoteble.protocol.BleConnState
import dev.warsha.remoteble.protocol.Capabilities
import dev.warsha.remoteble.protocol.CborProtocolCodec
import dev.warsha.remoteble.protocol.CharRef
import dev.warsha.remoteble.protocol.ClientHello
import dev.warsha.remoteble.protocol.Command
import dev.warsha.remoteble.protocol.ConnPriority
import dev.warsha.remoteble.protocol.DescRef
import dev.warsha.remoteble.protocol.DeviceHandle
import dev.warsha.remoteble.protocol.ErrorKind
import dev.warsha.remoteble.protocol.Event
import dev.warsha.remoteble.protocol.Frame
import dev.warsha.remoteble.protocol.IdentifierFormat
import dev.warsha.remoteble.protocol.Op
import dev.warsha.remoteble.protocol.OpResult
import dev.warsha.remoteble.protocol.Reply
import dev.warsha.remoteble.protocol.ResultPayload
import dev.warsha.remoteble.protocol.ServerHello
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class BleAgentTest {

    private val device = DeviceHandle("FA:KE:0A")
    private val char = CharRef(
        service = "0000180d-0000-1000-8000-00805f9b34fb",
        characteristic = "00002a37-0000-1000-8000-00805f9b34fb",
    )

    private class Harness(
        scope: CoroutineScope,
        backend: BleBackend,
        maxConnections: Int = 4,
        registry: PeripheralRegistry = PeripheralRegistry(scope),
        clientId: Long = 0L,
        capabilities: Set<String> = emptySet(),
        scanBatchWindow: Duration = BleAgent.DEFAULT_SCAN_BATCH_WINDOW,
        scanBatchMaxSize: Int = BleAgent.DEFAULT_SCAN_BATCH_MAX_SIZE,
        strictMode: StrictModeState = StrictModeState(),
        // Default to a format that differs from the clients tests declare, so translation engages.
        agentFormat: IdentifierFormat = IdentifierFormat.BLUEZ_JSON,
    ) {
        private val codec = CborProtocolCodec()
        private val toAgent = Channel<ByteArray>(Channel.UNLIMITED)
        private val fromAgent = Channel<ByteArray>(Channel.UNLIMITED)
        val frames = MutableSharedFlow<Frame>(replay = 128, extraBufferCapacity = 128)

        init {
            scope.launch { fromAgent.receiveAsFlow().collect { frames.emit(codec.decode(it)) } }
            BleAgent(
                incoming = toAgent.receiveAsFlow(),
                outgoing = { fromAgent.trySend(it); Unit },
                scope = scope,
                backend = backend,
                codec = codec,
                maxConnections = maxConnections,
                clientId = clientId,
                registry = registry,
                capabilities = capabilities,
                scanBatchWindow = scanBatchWindow,
                scanBatchMaxSize = scanBatchMaxSize,
                strictMode = strictMode,
                agentFormat = agentFormat,
            ).start()
        }

        fun send(cid: Long, op: Op) {
            toAgent.trySend(codec.encode(Command(cid, op)))
        }

        /** Feed an arbitrary (possibly undecodable) frame to exercise the decode loop. */
        fun sendRaw(bytes: ByteArray) {
            toAgent.trySend(bytes)
        }

        fun sendHello(wanted: Set<String>, identifierFormat: IdentifierFormat? = null) {
            toAgent.trySend(codec.encode(ClientHello(capabilities = wanted, identifierFormat = identifierFormat)))
        }
    }

    private suspend fun SharedFlow<Frame>.reply(cid: Long): OpResult =
        filterIsInstance<Reply>().first { it.cid == cid }.result

    @Test
    fun malformedFrameIsSkippedAndSessionSurvives() = runTest {
        val backend = FakeBleBackend()
        val h = Harness(backgroundScope, backend)

        // A garbage payload that is not a valid CBOR frame must not kill the session.
        h.sendRaw(byteArrayOf(0x00, 0x01, 0x02, 0x03, 0x04))
        // A subsequent valid command is still answered, proving the collect survived.
        h.send(1, Op.Connect(device))
        assertEquals(OpResult.Ok(), h.frames.reply(1))
    }

    @Test
    fun connectReadWriteDiscoverMtuResolve() = runTest {
        val backend = FakeBleBackend()
        val h = Harness(backgroundScope, backend)

        h.send(1, Op.Connect(device))
        assertEquals(OpResult.Ok(), h.frames.reply(1))
        val connState = h.frames.filterIsInstance<Event>().map { it.event }
            .filterIsInstance<AgentEvent.ConnectionState>().first()
        assertEquals(BleConnState.CONNECTED, connState.state)

        h.send(2, Op.Read(device, char))
        val read = assertIs<OpResult.Ok>(h.frames.reply(2))
        assertEquals(listOf<Byte>(0x42, 0x07), assertIs<ResultPayload.Bytes>(read.payload).value.toList())

        h.send(3, Op.Write(device, char, byteArrayOf(9, 9), withResponse = true))
        assertIs<OpResult.Ok>(h.frames.reply(3))
        assertEquals(listOf<Byte>(9, 9), backend.lastWrite?.second?.toList())

        h.send(4, Op.Discover(device))
        val discover = assertIs<OpResult.Ok>(h.frames.reply(4))
        assertEquals(FakeBleBackend.DEFAULT_SERVICES, assertIs<ResultPayload.Services>(discover.payload).services)

        h.send(5, Op.RequestMtu(device, 247))
        val mtu = assertIs<OpResult.Ok>(h.frames.reply(5))
        assertEquals(247, assertIs<ResultPayload.Mtu>(mtu.payload).mtu)
    }

    @Test
    fun unsolicitedRegistryDisconnectEmitsConnectionStateEvent() = runTest {
        // Simulates ConnectionWatcher detecting a peripheral vanished without an explicit
        // Disconnect op — the registry is the only thing that can tell this connection about it
        // (see PeripheralRegistry.onUnsolicitedDisconnect / BleAgent.registerClient).
        val registry = PeripheralRegistry(backgroundScope)
        val h = Harness(backgroundScope, FakeBleBackend(), registry = registry, clientId = 7)

        h.send(1, Op.Connect(device))
        assertEquals(OpResult.Ok(), h.frames.reply(1))

        registry.onUnsolicitedDisconnect(device.value, "7")

        val connState = h.frames.filterIsInstance<Event>().map { it.event }
            .filterIsInstance<AgentEvent.ConnectionState>()
            .first { it.state == BleConnState.DISCONNECTED }
        assertEquals(device, connState.device)
    }

    @Test
    fun readWriteDescriptorRoutesToBackend() = runTest {
        val backend = FakeBleBackend(descriptorValue = byteArrayOf(0x01, 0x00))
        val h = Harness(backgroundScope, backend)
        val desc = DescRef(service = char.service, characteristic = char.characteristic, descriptor = "2902")

        h.send(1, Op.ReadDescriptor(device, desc))
        val read = assertIs<OpResult.Ok>(h.frames.reply(1))
        assertEquals(listOf<Byte>(0x01, 0x00), assertIs<ResultPayload.Bytes>(read.payload).value.toList())

        h.send(2, Op.WriteDescriptor(device, desc, byteArrayOf(0x00, 0x00)))
        assertIs<OpResult.Ok>(h.frames.reply(2))
        assertEquals(desc, backend.lastDescriptorWrite?.first)
        assertEquals(listOf<Byte>(0x00, 0x00), backend.lastDescriptorWrite?.second?.toList())
    }

    @Test
    fun descriptorOpsAreUnsupportedWhenBackendDoesNotImplementThem() = runTest {
        // A backend that leaves the default BleBackend descriptor impls in place must
        // surface UNSUPPORTED rather than crash the op handler.
        val h = Harness(backgroundScope, MinimalBackend())
        h.send(1, Op.ReadDescriptor(device, DescRef("180d", "2a37", "2902")))
        assertEquals(ErrorKind.UNSUPPORTED, assertIs<OpResult.Err>(h.frames.reply(1)).error.kind)
    }

    @Test
    fun pairUnpairRouteToBackendAndEmitBondState() = runTest {
        val backend = FakeBleBackend()
        val h = Harness(backgroundScope, backend)

        h.send(1, Op.Pair(device))
        val paired = assertIs<OpResult.Ok>(h.frames.reply(1))
        assertEquals(BleBondState.BONDED, assertIs<ResultPayload.Bond>(paired.payload).state)
        assertEquals(listOf(device), backend.pairCalls)
        val bonded = h.frames.filterIsInstance<Event>().map { it.event }
            .filterIsInstance<AgentEvent.BondState>().first()
        assertEquals(BleBondState.BONDED, bonded.state)

        h.send(2, Op.Unpair(device))
        assertIs<OpResult.Ok>(h.frames.reply(2))
        assertEquals(listOf(device), backend.unpairCalls)
    }

    @Test
    fun pairIsUnsupportedWhenBackendDoesNotImplementIt() = runTest {
        val h = Harness(backgroundScope, MinimalBackend())
        h.send(1, Op.Pair(device))
        assertEquals(ErrorKind.UNSUPPORTED, assertIs<OpResult.Err>(h.frames.reply(1)).error.kind)
    }

    @Test
    fun requestConnectionPriorityRoutesToBackend() = runTest {
        val backend = FakeBleBackend()
        val h = Harness(backgroundScope, backend)

        h.send(1, Op.RequestConnectionPriority(device, ConnPriority.HIGH))
        assertIs<OpResult.Ok>(h.frames.reply(1))
        assertEquals(ConnPriority.HIGH, backend.lastConnectionPriority)
    }

    @Test
    fun connectionPriorityIsUnsupportedWhenBackendDoesNotImplementIt() = runTest {
        val h = Harness(backgroundScope, MinimalBackend())
        h.send(1, Op.RequestConnectionPriority(device, ConnPriority.BALANCED))
        assertEquals(ErrorKind.UNSUPPORTED, assertIs<OpResult.Err>(h.frames.reply(1)).error.kind)
    }

    @Test
    fun handshakeAdvertisesIntersectionOfClientAndAgentCapabilities() = runTest {
        // Agent supports only descriptors; client wants both. The ServerHello must carry
        // exactly the intersection.
        val h = Harness(backgroundScope, FakeBleBackend(), capabilities = setOf(Capabilities.DESCRIPTORS))
        h.sendHello(setOf(Capabilities.DESCRIPTORS, Capabilities.PAIRING))

        val hello = h.frames.filterIsInstance<ServerHello>().first()
        assertEquals(setOf(Capabilities.DESCRIPTORS), hello.capabilities)
    }

    @Test
    fun emitsSlotStateOnConnectAndDisconnectWhenNegotiated() = runTest {
        val h = Harness(
            backgroundScope, FakeBleBackend(), maxConnections = 2,
            capabilities = setOf(Capabilities.CONNECTION_SLOTS),
        )
        h.sendHello(setOf(Capabilities.CONNECTION_SLOTS))

        h.send(1, Op.Connect(device))
        assertIs<OpResult.Ok>(h.frames.reply(1))
        h.send(2, Op.Disconnect(device))
        assertIs<OpResult.Ok>(h.frames.reply(2))

        val slots = h.frames.filterIsInstance<Event>().map { it.event }
            .filterIsInstance<AgentEvent.SlotState>().take(2).toList()
        assertEquals(listOf(1, 2), slots.map { it.free }) // 2-1 after connect, back to 2 after disconnect
        assertEquals(2, slots[0].total)
    }

    @Test
    fun doesNotEmitSlotStateWhenNotNegotiated() = runTest {
        val h = Harness(backgroundScope, FakeBleBackend()) // no slots capability, no handshake
        h.send(1, Op.Connect(device))
        assertIs<OpResult.Ok>(h.frames.reply(1))

        val slot = withTimeoutOrNull(200) {
            h.frames.filterIsInstance<Event>().map { it.event }
                .filterIsInstance<AgentEvent.SlotState>().first()
        }
        assertNull(slot)
    }

    @Test
    fun connectionSlotCapIsEnforced() = runTest {
        val h = Harness(backgroundScope, FakeBleBackend(), maxConnections = 1)

        h.send(1, Op.Connect(DeviceHandle("A")))
        assertIs<OpResult.Ok>(h.frames.reply(1))

        h.send(2, Op.Connect(DeviceHandle("B")))
        val err = assertIs<OpResult.Err>(h.frames.reply(2))
        assertEquals(ErrorKind.NO_CONNECTION_SLOT, err.error.kind)
    }

    @Test
    fun failedConnectReleasesItsSlot() = runTest {
        val backend = FakeBleBackend(failConnectFor = setOf("A"))
        val h = Harness(backgroundScope, backend, maxConnections = 1)

        h.send(1, Op.Connect(DeviceHandle("A")))
        assertEquals(ErrorKind.CONNECTION_FAILED, assertIs<OpResult.Err>(h.frames.reply(1)).error.kind)

        // The reserved slot must have been released, so a different device can connect.
        h.send(2, Op.Connect(DeviceHandle("B")))
        assertIs<OpResult.Ok>(h.frames.reply(2))
    }

    @Test
    fun backendErrorsMapToErrorKind() = runTest {
        val backend = FakeBleBackend(characteristicNotFound = true)
        val h = Harness(backgroundScope, backend)

        h.send(1, Op.Connect(device))
        assertIs<OpResult.Ok>(h.frames.reply(1))

        h.send(2, Op.Read(device, char))
        assertEquals(ErrorKind.CHARACTERISTIC_NOT_FOUND, assertIs<OpResult.Err>(h.frames.reply(2)).error.kind)
    }

    @Test
    fun exclusivePeripheralBlocksOtherClientsButNotItsOwner() = runTest {
        // Two clients sharing one registry — the shared-radio gate the real agent uses.
        val registry = PeripheralRegistry(backgroundScope)
        val a = Harness(backgroundScope, FakeBleBackend(), registry = registry, clientId = 1)
        val b = Harness(backgroundScope, FakeBleBackend(), registry = registry, clientId = 2)

        a.send(1, Op.Connect(device))
        assertIs<OpResult.Ok>(a.frames.reply(1))

        // Another client cannot connect a peripheral already owned (exclusive default).
        b.send(1, Op.Connect(device))
        assertEquals(ErrorKind.PERIPHERAL_BUSY, assertIs<OpResult.Err>(b.frames.reply(1)).error.kind)

        // The owner re-connecting the same device is still an idempotent success.
        a.send(2, Op.Connect(device))
        assertIs<OpResult.Ok>(a.frames.reply(2))
    }

    @Test
    fun sharedPeripheralAllowsMultipleClients() = runTest {
        val registry = PeripheralRegistry(backgroundScope, defaultExclusive = false)
        val a = Harness(backgroundScope, FakeBleBackend(), registry = registry, clientId = 1)
        val b = Harness(backgroundScope, FakeBleBackend(), registry = registry, clientId = 2)

        a.send(1, Op.Connect(device))
        assertIs<OpResult.Ok>(a.frames.reply(1))
        b.send(1, Op.Connect(device))
        assertIs<OpResult.Ok>(b.frames.reply(1))
    }

    @Test
    fun scanStreamsAdvertisementsTaggedByScanId() = runTest {
        val h = Harness(backgroundScope, FakeBleBackend())

        h.send(1, Op.ScanStart(scanId = 7))
        assertIs<OpResult.Ok>(h.frames.reply(1))

        val results = h.frames.filterIsInstance<Event>().map { it.event }
            .filterIsInstance<AgentEvent.ScanResult>()
            .filter { it.scanId == 7L }
            .take(3)
            .toList()
        assertEquals(3, results.size)
        assertEquals(FakeBleBackend.DEFAULT_ADVERTISEMENTS[0], results[0].advertisement)

        h.send(2, Op.ScanStop(7))
        assertIs<OpResult.Ok>(h.frames.reply(2))
    }

    @Test
    fun scanRetainsLastKnownNameAndUuidsWhenALaterPacketOmitsThem() = runTest {
        val handle = DeviceHandle("FA:KE:0C")
        val backend = FakeBleBackend(
            advertisements = listOf(
                AdvertisementDto(device = handle, name = "Heart Monitor", rssi = -50, serviceUuids = listOf("180d")),
                AdvertisementDto(device = handle, name = null, rssi = -55), // bare refresh: no name, no uuids
            ),
        )
        val h = Harness(backgroundScope, backend)

        h.send(1, Op.ScanStart(scanId = 7))
        assertIs<OpResult.Ok>(h.frames.reply(1))

        val results = h.frames.filterIsInstance<Event>().map { it.event }
            .filterIsInstance<AgentEvent.ScanResult>()
            .filter { it.scanId == 7L }
            .take(2)
            .toList()

        // The first packet carries the identity.
        assertEquals("Heart Monitor", results[0].advertisement.name)
        assertEquals(listOf("180d"), results[0].advertisement.serviceUuids)
        // The second omitted them — the agent fills name + service UUIDs from the last known…
        assertEquals("Heart Monitor", results[1].advertisement.name)
        assertEquals(listOf("180d"), results[1].advertisement.serviceUuids)
        // …but forwards the fresh RSSI rather than a stale one.
        assertEquals(-55, results[1].advertisement.rssi)

        h.send(2, Op.ScanStop(7))
        assertIs<OpResult.Ok>(h.frames.reply(2))
    }

    @Test
    fun batchedScanCoalescesAdvertisementsWhenNegotiated() = runTest {
        val h = Harness(
            backgroundScope, FakeBleBackend(),
            capabilities = setOf(Capabilities.SCAN_BATCH),
            scanBatchMaxSize = 2, // flush as soon as two accumulate (deterministic under virtual time)
        )
        h.sendHello(setOf(Capabilities.SCAN_BATCH))

        h.send(1, Op.ScanStart(scanId = 7))
        assertIs<OpResult.Ok>(h.frames.reply(1))

        val batch = h.frames.filterIsInstance<Event>().map { it.event }
            .filterIsInstance<AgentEvent.ScanResultBatch>()
            .filter { it.scanId == 7L }
            .first()
        assertEquals(FakeBleBackend.DEFAULT_ADVERTISEMENTS, batch.advertisements)

        h.send(2, Op.ScanStop(7))
        assertIs<OpResult.Ok>(h.frames.reply(2))
    }

    @Test
    fun observeStreamsNotificationsTaggedBySubId() = runTest {
        val h = Harness(backgroundScope, FakeBleBackend())

        h.send(1, Op.Connect(device))
        assertIs<OpResult.Ok>(h.frames.reply(1))

        h.send(2, Op.ObserveStart(subId = 9, device = device, char = char))
        assertIs<OpResult.Ok>(h.frames.reply(2))

        val values = h.frames.filterIsInstance<Event>().map { it.event }
            .filterIsInstance<AgentEvent.Notification>()
            .filter { it.subId == 9L }
            .take(3)
            .toList()
        assertEquals(3, values.size)
        assertEquals(FakeBleBackend.DEFAULT_NOTIFICATIONS[0].toList(), values[0].value.toList())

        h.send(3, Op.ObserveStop(9))
        assertIs<OpResult.Ok>(h.frames.reply(3))
    }

    // ---- Identifier translation (capability `identifier.translate`) ----

    private suspend fun SharedFlow<Frame>.firstScanResult(scanId: Long): AgentEvent.ScanResult =
        filterIsInstance<Event>().map { it.event }
            .filterIsInstance<AgentEvent.ScanResult>()
            .first { it.scanId == scanId }

    @Test
    fun identifierTranslation_rewritesScanHandleAndRoutesOpsBack() = runTest {
        // A UUID-format client against an agent whose radio mints non-UUID (bluez) handles.
        val realHandle = DeviceHandle("FA:KE:0A")
        val backend = FakeBleBackend(
            advertisements = listOf(AdvertisementDto(device = realHandle, name = "Fake A", rssi = -55)),
        )
        val h = Harness(
            backgroundScope, backend,
            capabilities = setOf(Capabilities.IDENTIFIER_TRANSLATION),
            agentFormat = IdentifierFormat.BLUEZ_JSON,
        )
        h.sendHello(setOf(Capabilities.IDENTIFIER_TRANSLATION), IdentifierFormat.UUID)

        h.send(1, Op.ScanStart(scanId = 7))
        assertIs<OpResult.Ok>(h.frames.reply(1))

        // The client sees a UUID-shaped handle, not the real bluez one.
        val clientHandle = h.frames.firstScanResult(7).advertisement.device.value
        assertNotEquals(realHandle.value, clientHandle)
        assertTrue(clientHandle.contains("-"), "expected a UUID handle, got $clientHandle")

        // An op keyed on that translated handle routes to the real radio device.
        h.send(2, Op.Connect(DeviceHandle(clientHandle)))
        assertEquals(OpResult.Ok(), h.frames.reply(2))
        assertEquals(listOf(realHandle), backend.connectCalls)
    }

    @Test
    fun identifierTranslation_strictModePassesHandlesThrough() = runTest {
        val realHandle = DeviceHandle("FA:KE:0A")
        val backend = FakeBleBackend(
            advertisements = listOf(AdvertisementDto(device = realHandle, name = "Fake A", rssi = -55)),
        )
        val h = Harness(
            backgroundScope, backend,
            capabilities = setOf(Capabilities.IDENTIFIER_TRANSLATION),
            strictMode = StrictModeState(initial = true),
            agentFormat = IdentifierFormat.BLUEZ_JSON,
        )
        h.sendHello(setOf(Capabilities.IDENTIFIER_TRANSLATION), IdentifierFormat.UUID)

        h.send(1, Op.ScanStart(scanId = 7))
        assertIs<OpResult.Ok>(h.frames.reply(1))

        // Strict mode: the real handle passes through untranslated (surfaces mismatches loudly).
        assertEquals(realHandle.value, h.frames.firstScanResult(7).advertisement.device.value)
    }

    @Test
    fun identifierTranslation_inactiveWhenCapabilityNotNegotiated() = runTest {
        val realHandle = DeviceHandle("FA:KE:0A")
        val backend = FakeBleBackend(
            advertisements = listOf(AdvertisementDto(device = realHandle, name = "Fake A", rssi = -55)),
        )
        // Agent does not advertise the capability, so translation never engages.
        val h = Harness(backgroundScope, backend, agentFormat = IdentifierFormat.BLUEZ_JSON)
        h.sendHello(setOf(Capabilities.IDENTIFIER_TRANSLATION), IdentifierFormat.UUID)

        h.send(1, Op.ScanStart(scanId = 7))
        assertIs<OpResult.Ok>(h.frames.reply(1))
        assertEquals(realHandle.value, h.frames.firstScanResult(7).advertisement.device.value)
    }
}
