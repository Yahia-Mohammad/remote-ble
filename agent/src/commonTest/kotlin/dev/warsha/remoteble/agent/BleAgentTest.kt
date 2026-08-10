package dev.warsha.remoteble.agent

import dev.warsha.remoteble.protocol.AdvertisementDto
import dev.warsha.remoteble.protocol.AgentEvent
import dev.warsha.remoteble.protocol.AgentStatusDto
import dev.warsha.remoteble.protocol.BleBondState
import dev.warsha.remoteble.protocol.BleConnState
import dev.warsha.remoteble.protocol.BleRadioState
import dev.warsha.remoteble.protocol.Capabilities
import dev.warsha.remoteble.protocol.CborProtocolCodec
import dev.warsha.remoteble.protocol.CharRef
import dev.warsha.remoteble.protocol.ClientHello
import dev.warsha.remoteble.protocol.Command
import dev.warsha.remoteble.protocol.ConnParamHint
import dev.warsha.remoteble.protocol.ConnPriority
import dev.warsha.remoteble.protocol.ConnProfile
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
import dev.warsha.remoteble.protocol.ScanFilter
import dev.warsha.remoteble.protocol.ServerHello
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
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
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
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
        maxActiveScans: Int = BleAgent.MAX_ACTIVE_SCANS,
        maxActiveObservations: Int = BleAgent.MAX_ACTIVE_OBSERVATIONS,
        // The slot cap lives in the registry (it is the host radio's, not one session's), so a
        // harness asking for a smaller cap has to build the registry with it.
        registry: PeripheralRegistry = PeripheralRegistry(scope, maxSlots = maxConnections),
        clientId: Long = 0L,
        capabilities: Set<String> = emptySet(),
        scanBatchWindow: Duration = BleAgent.DEFAULT_SCAN_BATCH_WINDOW,
        scanBatchMaxSize: Int = BleAgent.DEFAULT_SCAN_BATCH_MAX_SIZE,
        strictMode: StrictModeState = StrictModeState(),
        // Default to a format that differs from the clients tests declare, so translation engages.
        agentFormat: IdentifierFormat = IdentifierFormat.BLUEZ_JSON,
        observer: AgentObserver = AgentObserver.None,
        // Ownership identity. Defaults to the connection id, as it does in production for a client
        // that sends no stable id; `agent.status` disclosure tests set it to a real session key.
        clientKey: String = clientId.toString(),
        monitor: AgentMonitor? = null,
        operatorScope: Boolean = false,
        writePolicy: WritePolicy = WritePolicy.permissive(),
    ) {
        private val codec = CborProtocolCodec()
        private val toAgent = Channel<ByteArray>(Channel.UNLIMITED)
        private val fromAgent = Channel<ByteArray>(Channel.UNLIMITED)
        val frames = MutableSharedFlow<Frame>(replay = 128, extraBufferCapacity = 128)

        // LIMIT-SLOW-01: flips the outbound link to a stalled client (every write suspends
        // forever) without touching the inbound command path, so a test can drive a connection
        // to a healthy state first and then simulate the transport going slow.
        var stallOutgoing: Boolean = false

        /**
         * Suspension points inserted immediately before a `Reply` reaches the transport.
         *
         * Turns the reply-before-events guarantee into a deterministic assertion instead of a race
         * that a fast machine always wins. With delivery started before the reply — the behaviour
         * before `BleAgent.replyThenDeliver` — a stream collector is already running during these
         * yields and its event overtakes the reply every time. With delivery deferred, nothing can.
         */
        var yieldsBeforeReply: Int = 0

        val agent: BleAgent

        init {
            scope.launch { fromAgent.receiveAsFlow().collect { frames.emit(codec.decode(it)) } }
            agent = BleAgent(
                incoming = toAgent.receiveAsFlow(),
                outgoing = {
                    if (stallOutgoing) {
                        kotlinx.coroutines.awaitCancellation()
                    } else {
                        if (yieldsBeforeReply > 0 && codec.decode(it) is Reply) {
                            repeat(yieldsBeforeReply) { kotlinx.coroutines.yield() }
                        }
                        fromAgent.send(it)
                    }
                },
                scope = scope,
                backend = backend,
                codec = codec,
                maxConnections = maxConnections,
                maxActiveScans = maxActiveScans,
                maxActiveObservations = maxActiveObservations,
                clientId = clientId,
                registry = registry,
                capabilities = capabilities,
                scanBatchWindow = scanBatchWindow,
                scanBatchMaxSize = scanBatchMaxSize,
                strictMode = strictMode,
                agentFormat = agentFormat,
                observer = observer,
                clientKey = clientKey,
                monitor = monitor,
                operatorScope = operatorScope,
                writePolicy = writePolicy,
            )
            agent.start()
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

        /** Ends this connection's incoming flow — the agent sees a transport drop. */
        fun close() {
            toAgent.close()
        }
    }

    private suspend fun SharedFlow<Frame>.reply(cid: Long): OpResult =
        filterIsInstance<Reply>().first { it.cid == cid }.result

    private suspend fun Harness.connect(cid: Long, device: DeviceHandle = this@BleAgentTest.device) {
        send(cid, Op.Connect(device))
        assertIs<OpResult.Ok>(frames.reply(cid))
    }

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
    fun concurrentWritesToOneDeviceReachBackendInSubmissionOrder() = runTest {
        // Each command runs on its own coroutine, so a pipelined WWR burst could race into
        // backend.write out of order (0.8.3 / feature C, plan §2d). This backend delays *longer*
        // for earlier payloads — so without the agent's per-device write chain, the later
        // (shorter-delay) writes would land first and `recorded` would come back reversed.
        val recorded = mutableListOf<Int>()
        val backend = object : BleBackend by FakeBleBackend() {
            override suspend fun write(device: DeviceHandle, char: CharRef, value: ByteArray, withResponse: Boolean) {
                val n = value.first().toInt()
                kotlinx.coroutines.delay((100 - n).toLong()) // earlier writes wait longer
                recorded += n
            }
        }
        val h = Harness(backgroundScope, backend)

        h.connect(100)
        val count = 8
        for (n in 0 until count) {
            h.send(n.toLong(), Op.Write(device, char, byteArrayOf(n.toByte()), withResponse = false))
        }
        // Await every reply so all writes have completed before asserting.
        for (n in 0 until count) assertIs<OpResult.Ok>(h.frames.reply(n.toLong()))

        assertEquals(
            (0 until count).toList(),
            recorded,
            "same-device writes must reach the backend in submission order despite per-command concurrency",
        )
    }

    @Test
    fun writesToDifferentDevicesAreNotSerializedByTheOrderingChain() = runTest {
        // The write chain is *per device*: a slow write to device A must not hold up a write to
        // device B. Device A's write blocks until released; device B's must complete meanwhile.
        val deviceB = DeviceHandle("FA:KE:0B")
        val aStarted = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val completed = mutableListOf<String>()
        val backend = object : BleBackend by FakeBleBackend() {
            override suspend fun write(device: DeviceHandle, char: CharRef, value: ByteArray, withResponse: Boolean) {
                val isA = value.first().toInt() and 0xFF == 0xAA
                if (isA) {
                    aStarted.complete(Unit)
                    release.await() // device A's write is held open
                }
                completed += if (isA) "A" else "B"
            }
        }
        val h = Harness(backgroundScope, backend)

        h.connect(100, device)
        h.connect(101, deviceB)
        h.send(1, Op.Write(device, char, byteArrayOf(0xAA.toByte()), withResponse = false)) // device A, blocks
        aStarted.await()
        h.send(2, Op.Write(deviceB, char, byteArrayOf(0xBB.toByte()), withResponse = false)) // device B, must proceed
        assertIs<OpResult.Ok>(h.frames.reply(2))
        assertEquals(listOf("B"), completed, "device B's write must complete while device A's is still blocked")

        release.complete(Unit)
        assertIs<OpResult.Ok>(h.frames.reply(1))
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

        h.connect(100)
        h.send(1, Op.ReadDescriptor(device, desc))
        val read = assertIs<OpResult.Ok>(h.frames.reply(1))
        assertEquals(listOf<Byte>(0x01, 0x00), assertIs<ResultPayload.Bytes>(read.payload).value.toList())

        h.send(2, Op.WriteDescriptor(device, desc, byteArrayOf(0x00, 0x00)))
        assertIs<OpResult.Ok>(h.frames.reply(2))
        assertEquals(desc, backend.lastDescriptorWrite?.first)
        assertEquals(listOf<Byte>(0x00, 0x00), backend.lastDescriptorWrite?.second?.toList())
    }

    @Test
    fun operationLimitsRejectOversizedRequestsAsInvalidWithoutCallingTheBackend() = runTest {
        val backend = FakeBleBackend()
        val h = Harness(backgroundScope, backend)
        val desc = DescRef(service = char.service, characteristic = char.characteristic, descriptor = "2902")

        h.send(1, Op.ScanStart(scanId = 1, filters = List(BleAgent.MAX_SCAN_FILTERS + 1) { ScanFilter() }))
        h.send(2, Op.Write(device, char, ByteArray(BleAgent.MAX_WRITE_BYTES + 1), withResponse = true))
        h.send(3, Op.WriteDescriptor(device, desc, ByteArray(BleAgent.MAX_WRITE_BYTES + 1)))
        h.send(4, Op.RequestMtu(device, BleAgent.MAX_MTU + 1))
        h.send(5, Op.RequestMtu(device, BleAgent.MIN_MTU - 1))

        for (cid in 1L..5L) {
            assertEquals(ErrorKind.INVALID_REQUEST, assertIs<OpResult.Err>(h.frames.reply(cid)).error.kind)
        }
        assertNull(backend.lastWrite)
        assertNull(backend.lastDescriptorWrite)
    }

    @Test
    fun aStreamsReplyReachesTheTransportBeforeAnyEventItProduces() = runTest {
        // The wire guarantee, made deterministic. `yieldsBeforeReply` parks the reply just short of
        // the transport, so a collector started during the command — the behaviour this replaced —
        // has every opportunity to overtake it. FakeBleBackend emits on its first scan turn, so
        // there is always an event contending.
        //
        // Written this way because the end-to-end version of this assertion cannot fail on a fast
        // machine: the reply wins the race by default, and the violation only showed up on a
        // two-core CI runner, about 40% of the time.
        val backend = FakeBleBackend()
        val h = Harness(backgroundScope, backend)
        h.yieldsBeforeReply = 32

        // Connect first so the observe below is authorized, and get both streams running before
        // reading anything back: order is asserted over the recorded frames, not by racing reads.
        h.connect(1)
        h.send(2, Op.ScanStart(scanId = 1))
        assertIs<OpResult.Ok>(h.frames.reply(2))
        h.send(3, Op.ObserveStart(subId = 1, device, char))
        assertIs<OpResult.Ok>(h.frames.reply(3))
        // Let both collectors run so there is actually something to be out of order with.
        repeat(64) { kotlinx.coroutines.yield() }

        // replayCache is the transport's frame order. Compare positions rather than reading with
        // `first { }`, which would match a replayed frame from earlier in this same test.
        val ordered = h.frames.replayCache
        fun indexOfReply(cid: Long) = ordered.indexOfFirst { it is Reply && it.cid == cid }
        fun indexOfEvent(predicate: (AgentEvent) -> Boolean) =
            ordered.indexOfFirst { it is Event && predicate(it.event) }

        val firstScanResult = indexOfEvent { it is AgentEvent.ScanResult || it is AgentEvent.ScanResultBatch }
        assertTrue(firstScanResult >= 0, "the backend must have produced a scan result to order against")
        assertTrue(
            indexOfReply(2) < firstScanResult,
            "scan.start must be acknowledged before any result it produces — a client is otherwise " +
                "handed results for a stream it has not been told exists. Frames: $ordered",
        )

        val firstNotification = indexOfEvent { it is AgentEvent.Notification }
        if (firstNotification >= 0) {
            assertTrue(
                indexOfReply(3) < firstNotification,
                "observe.start must be acknowledged before its first notification. Frames: $ordered",
            )
        }
    }

    @Test
    fun activeStreamLimitsRejectNewIdsButAllowSameIdReplacement() = runTest {
        val backend = FakeBleBackend()
        val h = Harness(backgroundScope, backend, maxActiveScans = 1, maxActiveObservations = 1)

        h.send(1, Op.ScanStart(scanId = 1))
        assertIs<OpResult.Ok>(h.frames.reply(1))
        h.send(2, Op.ScanStart(scanId = 2))
        assertEquals(ErrorKind.INVALID_REQUEST, assertIs<OpResult.Err>(h.frames.reply(2)).error.kind)
        h.send(3, Op.ScanStart(scanId = 1))
        assertIs<OpResult.Ok>(h.frames.reply(3))

        h.connect(4)
        h.send(5, Op.ObserveStart(subId = 1, device, char))
        assertIs<OpResult.Ok>(h.frames.reply(5))
        h.send(6, Op.ObserveStart(subId = 2, device, char))
        assertEquals(ErrorKind.INVALID_REQUEST, assertIs<OpResult.Err>(h.frames.reply(6)).error.kind)
        h.send(7, Op.ObserveStart(subId = 1, device, char))
        assertIs<OpResult.Ok>(h.frames.reply(7))
    }

    @Test
    fun descriptorOpsAreUnsupportedWhenBackendDoesNotImplementThem() = runTest {
        // A backend that leaves the default BleBackend descriptor impls in place must
        // surface UNSUPPORTED rather than crash the op handler.
        val h = Harness(backgroundScope, MinimalBackend())
        h.connect(100)
        h.send(1, Op.ReadDescriptor(device, DescRef("180d", "2a37", "2902")))
        assertEquals(ErrorKind.UNSUPPORTED, assertIs<OpResult.Err>(h.frames.reply(1)).error.kind)
    }

    @Test
    fun pairUnpairRouteToBackendAndEmitBondState() = runTest {
        val backend = FakeBleBackend()
        val h = Harness(backgroundScope, backend, capabilities = setOf(Capabilities.PAIRING))
        // BondState is a capability-gated event: it is emitted only to a client that
        // negotiated `pairing` (the reply payload carries the state regardless).
        h.sendHello(setOf(Capabilities.PAIRING))
        h.connect(100)

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
    fun bondStateEventIsNotEmittedWithoutNegotiatedPairing() = runTest {
        val backend = FakeBleBackend()
        val h = Harness(backgroundScope, backend, capabilities = setOf(Capabilities.PAIRING))

        // No hello: the op is still served — the solicited reply carries the bond state —
        // but the capability-gated BondState event is suppressed.
        h.connect(100)
        h.send(1, Op.Pair(device))
        val paired = assertIs<OpResult.Ok>(h.frames.reply(1))
        assertEquals(BleBondState.BONDED, assertIs<ResultPayload.Bond>(paired.payload).state)

        val event = withTimeoutOrNull(200) {
            h.frames.filterIsInstance<Event>().map { it.event }
                .filterIsInstance<AgentEvent.BondState>().first()
        }
        assertNull(event)
    }

    @Test
    fun pairIsUnsupportedWhenBackendDoesNotImplementIt() = runTest {
        val h = Harness(backgroundScope, MinimalBackend())
        h.connect(100)
        h.send(1, Op.Pair(device))
        assertEquals(ErrorKind.UNSUPPORTED, assertIs<OpResult.Err>(h.frames.reply(1)).error.kind)
    }

    @Test
    fun requestConnectionPriorityRoutesToBackend() = runTest {
        val backend = FakeBleBackend()
        val h = Harness(backgroundScope, backend)

        h.connect(100)
        h.send(1, Op.RequestConnectionPriority(device, ConnPriority.HIGH))
        assertIs<OpResult.Ok>(h.frames.reply(1))
        assertEquals(ConnPriority.HIGH, backend.lastConnectionPriority)
    }

    @Test
    fun connectionPriorityIsUnsupportedWhenBackendDoesNotImplementIt() = runTest {
        val h = Harness(backgroundScope, MinimalBackend())
        h.connect(100)
        h.send(1, Op.RequestConnectionPriority(device, ConnPriority.BALANCED))
        assertEquals(ErrorKind.UNSUPPORTED, assertIs<OpResult.Err>(h.frames.reply(1)).error.kind)
    }

    @Test
    fun setConnParamsRoutesToBackend() = runTest {
        val backend = FakeBleBackend()
        val h = Harness(backgroundScope, backend)
        val hint = ConnParamHint(minIntervalMs = 20.0, maxIntervalMs = 40.0, latency = 0, supervisionTimeoutMs = 5000)

        h.connect(100)
        h.send(1, Op.SetConnParams(device, ConnProfile.LOW_LATENCY, hint))
        assertIs<OpResult.Ok>(h.frames.reply(1))
        assertEquals(ConnProfile.LOW_LATENCY to hint, backend.lastConnParams)
    }

    @Test
    fun setConnParamsIsUnsupportedWhenBackendDoesNotImplementIt() = runTest {
        val h = Harness(backgroundScope, MinimalBackend())
        h.connect(100)
        h.send(1, Op.SetConnParams(device, ConnProfile.BALANCED))
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
    fun emitsSlotStateOnNegotiationThenOnEveryOccupancyChange() = runTest {
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
            .filterIsInstance<AgentEvent.SlotState>().take(3).toList()
        // The first is the handshake snapshot: a client that negotiates `slots` and connects
        // nothing still learns the current occupancy, instead of waiting for a change that a
        // quiet agent may never produce.
        assertEquals(listOf(2, 1, 2), slots.map { it.free })
        assertEquals(2, slots[0].total)
    }

    @Test
    fun slotStateCountsAnotherClientsLease() = runTest {
        val registry = PeripheralRegistry(backgroundScope, maxSlots = 2)
        val other = Harness(backgroundScope, FakeBleBackend(), registry = registry, clientId = 1L)
        other.sendHello(emptySet())
        other.send(1, Op.Connect(device))
        assertIs<OpResult.Ok>(other.frames.reply(1))

        // A second client's very first slot report must already account for the peripheral the
        // first client holds — the per-session count this replaced would have said "2 free".
        val watcher = Harness(
            backgroundScope, FakeBleBackend(), registry = registry, clientId = 2L,
            capabilities = setOf(Capabilities.CONNECTION_SLOTS),
        )
        watcher.sendHello(setOf(Capabilities.CONNECTION_SLOTS))

        val slot = watcher.frames.filterIsInstance<Event>().map { it.event }
            .filterIsInstance<AgentEvent.SlotState>().first()
        assertEquals(1, slot.free)
        assertEquals(2, slot.total)
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
    fun nonOwnerCannotOperateAnExclusivePeripheralAfterLearningItsHandle() = runTest {
        // A handle is observable through scan results, so every device-bearing op — not merely
        // Connect — must go through the registry authorization gate before touching the backend.
        val registry = PeripheralRegistry(backgroundScope)
        val backend = FakeBleBackend()
        val a = Harness(backgroundScope, backend, registry = registry, clientId = 1)
        val b = Harness(backgroundScope, backend, registry = registry, clientId = 2)
        a.connect(1)

        val descriptor = DescRef(char.service, char.characteristic, "2902")
        val unauthorizedOps = listOf<Op>(
            Op.Discover(device),
            Op.Read(device, char),
            Op.Write(device, char, byteArrayOf(1), withResponse = true),
            Op.RequestMtu(device, 247),
            Op.ReadRssi(device),
            Op.ReadDescriptor(device, descriptor),
            Op.WriteDescriptor(device, descriptor, byteArrayOf(0, 0)),
            Op.Pair(device),
            Op.Unpair(device),
            Op.RequestConnectionPriority(device, ConnPriority.HIGH),
            Op.SetConnParams(device, ConnProfile.BALANCED),
            Op.ObserveStart(subId = 9, device = device, char = char),
            Op.Disconnect(device),
        )

        unauthorizedOps.forEachIndexed { index, op ->
            val cid = (index + 10).toLong()
            b.send(cid, op)
            assertEquals(ErrorKind.PERIPHERAL_BUSY, assertIs<OpResult.Err>(b.frames.reply(cid)).error.kind)
        }

        // The denied write/disconnect are especially important: client B must not mutate the
        // radio or tear down A's physical connection after learning the scanned handle.
        assertNull(backend.lastWrite)
        assertNull(backend.lastDescriptorWrite)
        assertTrue(backend.pairCalls.isEmpty())
        assertTrue(backend.unpairCalls.isEmpty())
        assertTrue(backend.disconnectCalls.isEmpty())
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
    fun scanLifecycleOrderingStateDoesNotGrowWithDistinctScanIds() = runTest {
        // Each scan.start/scan.stop frame reserves a same-id ordering turn keyed by the
        // client-chosen scanId, so the bookkeeping must retire finished turns rather than
        // retaining one entry per id the connection has ever seen.
        val h = Harness(backgroundScope, FakeBleBackend())

        repeat(200) { index ->
            val scanId = index.toLong()
            h.send(cid = scanId * 2, op = Op.ScanStart(scanId = scanId))
            h.send(cid = scanId * 2 + 1, op = Op.ScanStop(scanId))
            h.frames.reply(scanId * 2 + 1)
        }
        runCurrent()

        assertEquals(
            0,
            h.agent.pendingScanTurns(),
            "scan ordering state must drain once its commands finish, not retain an entry " +
                "per distinct scan id the connection has ever seen",
        )
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

    /**
     * LIMIT-SLOW-01: a notification stream is not safely coalescible, so a client that stops
     * draining its link must not be allowed to hold an unbounded producer chain — the affected
     * observe stream terminates once delivery can't complete within
     * [BleAgent.NOTIFICATION_DELIVERY_TIMEOUT], with a visible log line, and the subscription
     * slot is freed rather than left dangling.
     */
    @Test
    fun observeTerminatesOnOutputOverflowWhenTheClientCannotKeepUp() = runTest {
        val logs = mutableListOf<String>()
        val observer = object : AgentObserver {
            override fun onClientLog(clientId: Long, message: String) {
                logs += message
            }
        }
        // Emits every 10ms by default — plenty fast to hit a stalled client's delivery timeout.
        val h = Harness(backgroundScope, FakeBleBackend(), observer = observer)

        h.connect(1)
        h.send(2, Op.ObserveStart(subId = 9, device = device, char = char))
        assertIs<OpResult.Ok>(h.frames.reply(2))

        h.stallOutgoing = true
        advanceTimeBy(BleAgent.NOTIFICATION_DELIVERY_TIMEOUT + 1.seconds)
        runCurrent()

        assertTrue(
            logs.any { it.contains("too slow") },
            "overflow must be logged so the operator can see it: $logs",
        )

        // The slot is freed, not left dangling: the same sub id can be restarted immediately.
        h.stallOutgoing = false
        h.send(3, Op.ObserveStart(subId = 9, device = device, char = char))
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
    fun identifierTranslation_stringClientGetsHandlesThatRouteFromALaterConnection() = runTest {
        val realHandle = DeviceHandle("FA:KE:0A")
        val backend = FakeBleBackend(
            advertisements = listOf(AdvertisementDto(device = realHandle, name = "Fake A", rssi = -55)),
        )
        val registry = PeripheralRegistry(backgroundScope)

        // A non-Kable client declares STRING: it holds any handle, so nothing is synthesized.
        val first = Harness(
            backgroundScope, backend, registry = registry,
            capabilities = setOf(Capabilities.IDENTIFIER_TRANSLATION),
            agentFormat = IdentifierFormat.BLUEZ_JSON,
        )
        first.sendHello(setOf(Capabilities.IDENTIFIER_TRANSLATION), IdentifierFormat.STRING)
        first.send(1, Op.ScanStart(scanId = 7))
        assertIs<OpResult.Ok>(first.frames.reply(1))
        val scanned = first.frames.firstScanResult(7).advertisement.device.value
        assertEquals(realHandle.value, scanned)

        // A *second connection* — the next process of a process-per-command client — that has
        // scanned nothing and holds no lease still addresses the peripheral with that handle.
        val second = Harness(
            backgroundScope, backend, registry = registry,
            capabilities = setOf(Capabilities.IDENTIFIER_TRANSLATION),
            agentFormat = IdentifierFormat.BLUEZ_JSON,
        )
        second.sendHello(setOf(Capabilities.IDENTIFIER_TRANSLATION), IdentifierFormat.STRING)
        second.send(1, Op.Connect(DeviceHandle(scanned)))
        assertEquals(OpResult.Ok(), second.frames.reply(1))
        assertEquals(listOf(realHandle), backend.connectCalls)
    }

    @Test
    fun identifierTranslation_synthesizedHandleFromAnEarlierConnectionDoesNotRoute() = runTest {
        // The behaviour the STRING opt-out above exists to avoid, pinned so it cannot change
        // silently: synthesis is per connection and its reverse map is primed only from leases,
        // so a handle a translating client scanned in one connection is meaningless in the next.
        val realHandle = DeviceHandle("FA:KE:0A")
        val backend = FakeBleBackend()
        val synthesized = HandleTranslator.synthesize(IdentifierFormat.UUID, realHandle.value)

        val fresh = Harness(
            backgroundScope, backend,
            capabilities = setOf(Capabilities.IDENTIFIER_TRANSLATION),
            agentFormat = IdentifierFormat.BLUEZ_JSON,
        )
        fresh.sendHello(setOf(Capabilities.IDENTIFIER_TRANSLATION), IdentifierFormat.UUID)
        fresh.send(1, Op.Connect(DeviceHandle(synthesized)))
        assertIs<OpResult.Ok>(fresh.frames.reply(1))

        // It reached the radio as the synthetic string, not as the peripheral it names.
        assertEquals(listOf(DeviceHandle(synthesized)), backend.connectCalls)
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

    @Test
    fun repeatedHelloDoesNotRenegotiateOrReplaceTheTranslator() = runTest {
        val realHandle = DeviceHandle("FA:KE:0A")
        val backend = FakeBleBackend(
            advertisements = listOf(AdvertisementDto(device = realHandle, name = "Fake A", rssi = -55)),
        )
        val h = Harness(
            backgroundScope, backend,
            capabilities = setOf(Capabilities.IDENTIFIER_TRANSLATION, Capabilities.CONNECTION_SLOTS),
            agentFormat = IdentifierFormat.BLUEZ_JSON,
        )
        h.sendHello(setOf(Capabilities.IDENTIFIER_TRANSLATION), IdentifierFormat.UUID)
        assertEquals(
            setOf(Capabilities.IDENTIFIER_TRANSLATION),
            h.frames.filterIsInstance<ServerHello>().first().capabilities,
        )

        // Scan to mint a translated handle whose reverse mapping the translator must retain.
        h.send(1, Op.ScanStart(scanId = 7))
        assertIs<OpResult.Ok>(h.frames.reply(1))
        val clientHandle = h.frames.firstScanResult(7).advertisement.device.value
        assertNotEquals(realHandle.value, clientHandle)

        // A second hello asking for a different set is answered idempotently: the ServerHello
        // still carries the first negotiation, not a renegotiated one.
        h.sendHello(setOf(Capabilities.CONNECTION_SLOTS))
        val second = h.frames.filterIsInstance<ServerHello>().take(2).toList()[1]
        assertEquals(setOf(Capabilities.IDENTIFIER_TRANSLATION), second.capabilities)

        // And the handle minted before the repeated hello still routes to the real radio device —
        // the translator (and its reverse map) was not replaced.
        h.send(2, Op.Connect(DeviceHandle(clientHandle)))
        assertEquals(OpResult.Ok(), h.frames.reply(2))
        assertEquals(listOf(realHandle), backend.connectCalls)
    }

    @Test
    fun reconnectingClientsReplayedTranslatedHandleStillRoutes() = runTest {
        val realHandle = DeviceHandle("FA:KE:0A")
        val backend = FakeBleBackend(
            advertisements = listOf(AdvertisementDto(device = realHandle, name = "Fake A", rssi = -55)),
        )
        val registry = PeripheralRegistry(backgroundScope)

        // First connection: negotiate translation, mint a translated handle via scan, connect.
        val h1 = Harness(
            backgroundScope, backend, registry = registry,
            capabilities = setOf(Capabilities.IDENTIFIER_TRANSLATION),
            agentFormat = IdentifierFormat.BLUEZ_JSON,
        )
        h1.sendHello(setOf(Capabilities.IDENTIFIER_TRANSLATION), IdentifierFormat.UUID)
        h1.send(1, Op.ScanStart(scanId = 7))
        assertIs<OpResult.Ok>(h1.frames.reply(1))
        val clientHandle = h1.frames.firstScanResult(7).advertisement.device.value
        assertNotEquals(realHandle.value, clientHandle)
        h1.send(2, Op.Connect(DeviceHandle(clientHandle)))
        assertEquals(OpResult.Ok(), h1.frames.reply(2))

        // The transport drops; the lease stays warm for the grace window.
        h1.close()

        // A fresh connection resumes: same clientKey, same shared registry. The client replays
        // the handle its previous connection was issued (reconcile-on-reconnect, hello first).
        // respondHello must prime the new translator from the warm lease so the replayed
        // translated handle routes to the real radio device — no scan has re-emitted it here.
        val h2 = Harness(
            backgroundScope, backend, registry = registry,
            capabilities = setOf(Capabilities.IDENTIFIER_TRANSLATION),
            agentFormat = IdentifierFormat.BLUEZ_JSON,
        )
        h2.sendHello(setOf(Capabilities.IDENTIFIER_TRANSLATION), IdentifierFormat.UUID)
        h2.send(1, Op.Connect(DeviceHandle(clientHandle)))
        assertEquals(OpResult.Ok(), h2.frames.reply(1))
        // One physical connect for both invocations: the radio link never went down, so the resume
        // must not re-drive it. See [warmLeaseResumeDoesNotReconnectTheRadio].
        assertEquals(listOf(realHandle), backend.connectCalls)
    }

    @Test
    fun warmLeaseResumeDoesNotReconnectTheRadio() = runTest {
        // The property a process-per-command client depends on: each invocation opens a transport,
        // issues `connect`, and expects to be talking to the peripheral its predecessor left
        // connected. If that `connect` re-drove the radio, every command would pay a physical
        // reconnect (and rediscovery), which is the cost the transport-grace window exists to
        // avoid — the window would keep the *lease* while silently dropping its whole benefit.
        val backend = FakeBleBackend()
        val registry = PeripheralRegistry(backgroundScope)

        val first = Harness(backgroundScope, backend, registry = registry, clientId = 1L)
        first.sendHello(emptySet())
        first.send(1, Op.Connect(device))
        assertIs<OpResult.Ok>(first.frames.reply(1))
        assertEquals(listOf(device), backend.connectCalls)

        // Two more invocations, each a fresh connection. The client id is what keys ownership, so
        // it stays the same — that is precisely what makes these the *same* client resuming rather
        // than a second one contending.
        repeat(2) {
            val next = Harness(backgroundScope, backend, registry = registry, clientId = 1L)
            next.sendHello(emptySet())
            next.send(1, Op.Connect(device))
            assertIs<OpResult.Ok>(next.frames.reply(1))
            // Still reported as connected, so the client can proceed straight to its GATT op.
            assertEquals(
                AgentEvent.ConnectionState(device, BleConnState.CONNECTED),
                next.frames.filterIsInstance<Event>().map { it.event }
                    .filterIsInstance<AgentEvent.ConnectionState>().first(),
            )
            next.close()
        }

        assertEquals(listOf(device), backend.connectCalls, "the radio was re-driven on resume")
        assertEquals(emptyList(), backend.disconnectCalls)
    }

    @Test
    fun aLeaseWhoseRadioLinkDroppedStillReconnects() = runTest {
        // The complement, so the skip above cannot silently swallow a genuine reconnect: after an
        // unsolicited BLE disconnect the lease survives its own grace window, but the link is down
        // and the next connect must actually reach the radio.
        val backend = FakeBleBackend()
        val registry = PeripheralRegistry(backgroundScope)

        val first = Harness(backgroundScope, backend, registry = registry, clientId = 1L)
        first.sendHello(emptySet())
        first.send(1, Op.Connect(device))
        assertIs<OpResult.Ok>(first.frames.reply(1))

        registry.onDisconnected(device.value, "1") // radio dropped, lease still held
        first.close()

        val second = Harness(backgroundScope, backend, registry = registry, clientId = 1L)
        second.sendHello(emptySet())
        second.send(1, Op.Connect(device))
        assertIs<OpResult.Ok>(second.frames.reply(1))
        assertEquals(listOf(device, device), backend.connectCalls)
    }

    @Test
    fun commandBeforeHelloIsBaselineAndALateHelloStillNegotiates() = runTest {
        val h = Harness(
            backgroundScope, FakeBleBackend(), maxConnections = 2,
            capabilities = setOf(Capabilities.CONNECTION_SLOTS),
        )

        // An op before any hello is served under the v1 baseline: it succeeds, but no
        // capability-gated event (SlotState) accompanies it.
        h.send(1, Op.Connect(device))
        assertIs<OpResult.Ok>(h.frames.reply(1))
        val slotBeforeHello = withTimeoutOrNull(200) {
            h.frames.filterIsInstance<Event>().map { it.event }
                .filterIsInstance<AgentEvent.SlotState>().first()
        }
        assertNull(slotBeforeHello)

        // First-hello-wins keys off the first *hello*, not the first frame: a hello arriving
        // after commands have already run must still negotiate.
        h.sendHello(setOf(Capabilities.CONNECTION_SLOTS))
        assertEquals(
            setOf(Capabilities.CONNECTION_SLOTS),
            h.frames.filterIsInstance<ServerHello>().first().capabilities,
        )
        h.send(2, Op.Disconnect(device))
        assertIs<OpResult.Ok>(h.frames.reply(2))
        val slotsAfterHello = h.frames.filterIsInstance<Event>().map { it.event }
            .filterIsInstance<AgentEvent.SlotState>().take(2).toList()
        // The event now flows: the handshake snapshot already accounts for the peripheral this
        // client connected before negotiating, and the disconnect frees it.
        assertEquals(listOf(1, 2), slotsAfterHello.map { it.free })
    }

    @Test
    fun radioStateIsStreamedFromHandshakeAndOnEveryTransition() = runTest {
        val backend = FakeBleBackend()
        backend.radioSignals.value = BleRadioState.OFF
        val h = Harness(backgroundScope, backend, capabilities = setOf(Capabilities.RADIO_STATE))
        h.sendHello(setOf(Capabilities.RADIO_STATE))

        val events = h.frames.filterIsInstance<Event>().map { it.event }
            .filterIsInstance<AgentEvent.RadioState>()

        // The state at handshake time, not merely the next transition: a client that connects while
        // the radio is already off must learn that without waiting for the user to toggle it.
        assertEquals(BleRadioState.OFF, events.first().state)

        backend.radioSignals.value = BleRadioState.ON
        assertEquals(BleRadioState.ON, events.first { it.state != BleRadioState.OFF }.state)
    }

    @Test
    fun radioStateIsWithheldFromClientsThatDidNotNegotiateIt() = runTest {
        val backend = FakeBleBackend()
        val h = Harness(backgroundScope, backend, capabilities = setOf(Capabilities.RADIO_STATE))
        h.sendHello(emptySet()) // a v1 client: it has never heard of this event type
        h.frames.filterIsInstance<ServerHello>().first()

        backend.radioSignals.value = BleRadioState.OFF
        // Drive a round-trip so the assertion is about ordering, not about being early.
        h.send(1, Op.Connect(device))
        assertIs<OpResult.Ok>(h.frames.reply(1))

        val radio = withTimeoutOrNull(200) {
            h.frames.filterIsInstance<Event>().map { it.event }
                .filterIsInstance<AgentEvent.RadioState>().first()
        }
        assertNull(radio)
    }

    @Test
    fun scanAndConnectFailWithRadioOffWhileTheRadioIsOff() = runTest {
        val backend = FakeBleBackend()
        val h = Harness(backgroundScope, backend, capabilities = setOf(Capabilities.RADIO_STATE))
        h.sendHello(setOf(Capabilities.RADIO_STATE))
        h.frames.filterIsInstance<ServerHello>().first()
        backend.radioSignals.value = BleRadioState.OFF

        h.send(1, Op.ScanStart(1, emptyList()))
        assertEquals(ErrorKind.RADIO_OFF, assertIs<OpResult.Err>(h.frames.reply(1)).error.kind)

        h.send(2, Op.Connect(device))
        assertEquals(ErrorKind.RADIO_OFF, assertIs<OpResult.Err>(h.frames.reply(2)).error.kind)

        // And the gate lifts on its own once the radio comes back — RADIO_OFF is transient, so a
        // client that retries has to actually be able to succeed.
        backend.radioSignals.value = BleRadioState.ON
        h.send(3, Op.Connect(device))
        assertIs<OpResult.Ok>(h.frames.reply(3))
    }

    @Test
    fun radioOffIsNotReportedToAClientThatCouldNotDecodeIt() = runTest {
        // Without the capability the pre-0.10.0 behaviour must be preserved exactly: the scan is
        // accepted and simply finds nothing. Sending ErrorKind.RADIO_OFF here would be a name the
        // client's decoder has never seen.
        val backend = FakeBleBackend()
        val h = Harness(backgroundScope, backend, capabilities = setOf(Capabilities.RADIO_STATE))
        h.sendHello(emptySet())
        h.frames.filterIsInstance<ServerHello>().first()
        backend.radioSignals.value = BleRadioState.OFF

        h.send(1, Op.ScanStart(1, emptyList()))
        assertIs<OpResult.Ok>(h.frames.reply(1))
    }

    @Test
    fun anUnsupportedRadioIsNotReportedAsTransientlyOff() = runTest {
        // UNSUPPORTED means there is no radio at all — not transient. Reporting it as RADIO_OFF
        // would invite a client to retry forever, so the op is left to fail on its own terms.
        val backend = FakeBleBackend()
        val h = Harness(backgroundScope, backend, capabilities = setOf(Capabilities.RADIO_STATE))
        h.sendHello(setOf(Capabilities.RADIO_STATE))
        h.frames.filterIsInstance<ServerHello>().first()
        backend.radioSignals.value = BleRadioState.UNSUPPORTED

        h.send(1, Op.Connect(device))
        assertIs<OpResult.Ok>(h.frames.reply(1))
    }

    @Test
    fun aBackendThatCannotSeeItsRadioNeitherStreamsNorGates() = runTest {
        // The JVM/btleplug case. The capability is negotiable in principle, but with no source
        // there is nothing to say — and crucially, no op may be gated on an unknown.
        val backend = FakeBleBackend()
        backend.radioObservable = false
        backend.radioSignals.value = BleRadioState.OFF // would gate, if it were observable
        val h = Harness(backgroundScope, backend, capabilities = setOf(Capabilities.RADIO_STATE))
        h.sendHello(setOf(Capabilities.RADIO_STATE))
        h.frames.filterIsInstance<ServerHello>().first()

        h.send(1, Op.Connect(device))
        assertIs<OpResult.Ok>(h.frames.reply(1))

        val radio = withTimeoutOrNull(200) {
            h.frames.filterIsInstance<Event>().map { it.event }
                .filterIsInstance<AgentEvent.RadioState>().first()
        }
        assertNull(radio)
    }

    // ---- agent.status (U3) ----

    private suspend fun SharedFlow<Frame>.status(cid: Long): AgentStatusDto =
        assertIs<ResultPayload.Status>(assertIs<OpResult.Ok>(reply(cid)).payload).status

    private val alpha = ClientCredentials.sessionKey("lab-a", "shell-1")
    private val beta = ClientCredentials.sessionKey("lab-b", "ci-runner")

    @Test
    fun agentStatusShowsACallerItsOwnLeasesAndOnlyACountOfOthers() = runTest {
        val registry = PeripheralRegistry(backgroundScope, maxSlots = 4)
        val other = DeviceHandle("FA:KE:0B")
        // A lease held by a different principal entirely.
        registry.acquire(other.value, beta)
        registry.onConnected(other.value, beta)

        val h = Harness(backgroundScope, FakeBleBackend(), registry = registry, clientKey = alpha)
        h.connect(1)

        h.send(2, Op.AgentStatus)
        val status = h.frames.status(2)
        assertEquals(1, status.leases.size)
        assertTrue(status.leases.single().mine)
        assertEquals("lab-a/shell-1", status.leases.single().holder)
        // The other tenant is a number, not a name: enough to explain the capacity, nothing more.
        assertEquals(1, status.otherLeases)
        assertEquals(2, status.slots.free)
        assertEquals(4, status.slots.total)
        assertFalse(status.operatorScope)
    }

    @Test
    fun agentStatusUnderOperatorScopeNamesEveryHolder() = runTest {
        val registry = PeripheralRegistry(backgroundScope, maxSlots = 4)
        val other = DeviceHandle("FA:KE:0B")
        registry.acquire(other.value, beta)
        registry.onConnected(other.value, beta)

        val h = Harness(
            backgroundScope,
            FakeBleBackend(),
            registry = registry,
            clientKey = alpha,
            operatorScope = true,
        )
        h.connect(1)

        h.send(2, Op.AgentStatus)
        val status = h.frames.status(2)
        assertTrue(status.operatorScope)
        assertEquals(2, status.leases.size)
        // Nothing is left over once every lease is listed.
        assertEquals(0, status.otherLeases)
        // The other principal's client id is disclosed here and nowhere else.
        assertEquals(
            setOf("lab-a/shell-1", "lab-b/ci-runner"),
            status.leases.mapNotNull { it.holder }.toSet(),
        )
    }

    @Test
    fun agentStatusReportsLeaseHandlesInTheCallersOwnFormat() = runTest {
        // A client whose identifier format differs from the agent's, so translation engages: a
        // handle read from a status reply has to be usable in the caller's next op, exactly like
        // one read from a scan result.
        val h = Harness(
            backgroundScope,
            FakeBleBackend(),
            capabilities = setOf(Capabilities.IDENTIFIER_TRANSLATION),
            clientKey = alpha,
        )
        h.sendHello(setOf(Capabilities.IDENTIFIER_TRANSLATION), IdentifierFormat.UUID)
        h.frames.filterIsInstance<ServerHello>().first()
        h.connect(1)

        h.send(2, Op.AgentStatus)
        val reported = h.frames.status(2).leases.single().handle
        assertNotEquals(device.value, reported, "a translating client must not see the radio handle")

        // And it routes: addressing the peripheral by the handle status just returned works.
        h.send(3, Op.Read(DeviceHandle(reported), char))
        assertIs<OpResult.Ok>(h.frames.reply(3))
    }

    // ---- write policy (U7) ----

    private fun allowingWrite(principal: String): WritePolicy = WritePolicy.decode(
        """{"version":1,"principals":{"$principal":{"writes":[
            {"service":"${char.service}","characteristic":"${char.characteristic}","maximumBytes":1}
        ]}}}""",
        knownPrincipals = setOf(principal),
    )

    private fun denyingEverything(principal: String): WritePolicy = WritePolicy.decode(
        """{"version":1,"principals":{"$principal":{"writes":[]}}}""",
        knownPrincipals = setOf(principal),
    )

    /** A harness whose backend (and negotiated set) already includes `write.policy`. */
    private suspend fun policyAwareHarness(
        scope: CoroutineScope,
        backend: BleBackend,
        clientKey: String,
        writePolicy: WritePolicy,
    ): Harness {
        val h = Harness(
            scope,
            backend,
            capabilities = setOf(Capabilities.WRITE_POLICY),
            clientKey = clientKey,
            writePolicy = writePolicy,
        )
        h.sendHello(setOf(Capabilities.WRITE_POLICY))
        h.frames.filterIsInstance<ServerHello>().first()
        return h
    }

    @Test
    fun permissivePolicyAllowsAWriteByDefault() = runTest {
        val backend = FakeBleBackend()
        val h = Harness(backgroundScope, backend)
        h.connect(1)

        h.send(2, Op.Write(device, char, byteArrayOf(0x01), withResponse = true))
        assertIs<OpResult.Ok>(h.frames.reply(2))
    }

    @Test
    fun aConfiguredPolicyDeniesAnUnlistedOrEmptyPrincipal() = runTest {
        for (policy in listOf(
            denyingEverything("owner"),
            // "owner" isn't in this policy at all — same outcome as an empty rule list.
            WritePolicy.decode(
                """{"version":1,"principals":{"someone-else":{"writes":[]}}}""",
                knownPrincipals = setOf("owner", "someone-else"),
            ),
        )) {
            val backend = FakeBleBackend()
            val h = policyAwareHarness(backgroundScope, backend, "owner", policy)
            h.connect(1)

            h.send(2, Op.Write(device, char, byteArrayOf(0x01), withResponse = true))
            val err = assertIs<OpResult.Err>(h.frames.reply(2))
            assertEquals(ErrorKind.POLICY_DENIED, err.error.kind)
        }
    }

    @Test
    fun aWriteOverTheConfiguredByteBoundIsDenied() = runTest {
        val backend = FakeBleBackend()
        val h = policyAwareHarness(backgroundScope, backend, "owner", allowingWrite("owner"))
        h.connect(1)

        // The rule allows at most 1 byte.
        h.send(2, Op.Write(device, char, byteArrayOf(0x01, 0x02), withResponse = true))
        val err = assertIs<OpResult.Err>(h.frames.reply(2))
        assertEquals(ErrorKind.POLICY_DENIED, err.error.kind)
        assertNull(backend.lastWrite)
    }

    @Test
    fun policyDeniedRequiresTheCapabilityOtherwiseItIsInvalidRequest() = runTest {
        // The crux: an unknown ErrorKind name would break a v1 client's decode, so a client that
        // never negotiated write.policy must not receive POLICY_DENIED at all.
        val backend = FakeBleBackend()
        val h = Harness(backgroundScope, backend, clientKey = "owner", writePolicy = denyingEverything("owner"))
        h.connect(1)

        h.send(2, Op.Write(device, char, byteArrayOf(0x01), withResponse = true))
        assertEquals(ErrorKind.INVALID_REQUEST, assertIs<OpResult.Err>(h.frames.reply(2)).error.kind)
    }

    @Test
    fun policyDeniedIsSentOnceTheCapabilityIsNegotiated() = runTest {
        val backend = FakeBleBackend()
        val h = policyAwareHarness(backgroundScope, backend, "owner", denyingEverything("owner"))
        h.connect(1)

        h.send(2, Op.Write(device, char, byteArrayOf(0x01), withResponse = true))
        assertEquals(ErrorKind.POLICY_DENIED, assertIs<OpResult.Err>(h.frames.reply(2)).error.kind)
    }

    @Test
    fun peripheralBusyIsAnsweredBeforeAnyPolicyCheck() = runTest {
        // Ordering matters: a caller that doesn't own the lease must never learn whether policy
        // would also have refused it — that would leak whether the policy permits a characteristic
        // on a device this caller cannot even touch.
        val backend = FakeBleBackend()
        val registry = PeripheralRegistry(backgroundScope)
        val owner = Harness(backgroundScope, backend, registry = registry, clientKey = "owner")
        owner.connect(1)

        // "intruder" would also be denied by this policy, but must never find that out.
        val intruder = Harness(
            backgroundScope,
            backend,
            registry = registry,
            clientKey = "intruder",
            writePolicy = denyingEverything("intruder"),
        )
        intruder.send(2, Op.Write(device, char, byteArrayOf(0x01), withResponse = true))
        val err = assertIs<OpResult.Err>(intruder.frames.reply(2))
        assertEquals(ErrorKind.PERIPHERAL_BUSY, err.error.kind)
    }

    @Test
    fun aDeviceScopedRuleIsEnforcedAtDispatch() = runTest {
        // The policy object's own matching is unit-tested; this proves the dispatch point actually
        // passes the device it is operating on, rather than a wildcard that would make the field
        // decorative.
        val backend = FakeBleBackend()
        val otherDevice = DeviceHandle("FA:KE:0B")
        val policy = WritePolicy.decode(
            """{"version":1,"principals":{"owner":{"writes":[
                {"device":"${device.value}","service":"${char.service}","characteristic":"${char.characteristic}"}
            ]}}}""",
            knownPrincipals = setOf("owner"),
        )
        val h = policyAwareHarness(backgroundScope, backend, "owner", policy)
        h.connect(1)
        h.connect(2, otherDevice)

        h.send(3, Op.Write(device, char, byteArrayOf(0x01), withResponse = true))
        assertIs<OpResult.Ok>(h.frames.reply(3))

        // Same principal, same characteristic, the peripheral the rule does not name.
        h.send(4, Op.Write(otherDevice, char, byteArrayOf(0x01), withResponse = true))
        assertEquals(ErrorKind.POLICY_DENIED, assertIs<OpResult.Err>(h.frames.reply(4)).error.kind)
    }

    @Test
    fun structuredHolderRidesOnPeripheralBusyOnlyForANegotiatedClient() = runTest {
        val backend = FakeBleBackend()
        val registry = PeripheralRegistry(backgroundScope)
        val owner = Harness(
            backgroundScope,
            backend,
            registry = registry,
            clientKey = ClientCredentials.sessionKey("lab-a", "rble-laptop"),
        )
        owner.connect(1)

        // A client that negotiated nothing: the prose names the holder, the field stays absent —
        // sending it would fail its decode of the whole frame, not merely be ignored.
        val ungated = Harness(
            backgroundScope,
            backend,
            registry = registry,
            clientKey = ClientCredentials.sessionKey("lab-a", "rble-ci"),
        )
        ungated.send(2, Op.Connect(device))
        val plain = assertIs<OpResult.Err>(ungated.frames.reply(2))
        assertEquals(ErrorKind.PERIPHERAL_BUSY, plain.error.kind)
        assertNull(plain.error.holder)
        assertContains(plain.error.message!!, "rble-laptop")

        // A client that negotiated `lease.holder` gets the same decision as fields.
        val gated = Harness(
            backgroundScope,
            backend,
            registry = registry,
            clientKey = ClientCredentials.sessionKey("lab-a", "rble-agent"),
            capabilities = setOf(Capabilities.LEASE_HOLDER),
        )
        gated.sendHello(wanted = setOf(Capabilities.LEASE_HOLDER))
        gated.send(3, Op.Connect(device))
        val structured = assertIs<OpResult.Err>(gated.frames.reply(3))
        assertEquals(ErrorKind.PERIPHERAL_BUSY, structured.error.kind)
        assertEquals("lab-a", structured.error.holder?.principal)
        assertEquals("rble-laptop", structured.error.holder?.clientId)
    }

    @Test
    fun theStructuredHolderWithholdsAnotherPrincipalsClientId() = runTest {
        val backend = FakeBleBackend()
        val registry = PeripheralRegistry(backgroundScope)
        val owner = Harness(
            backgroundScope,
            backend,
            registry = registry,
            clientKey = ClientCredentials.sessionKey("lab-a", "rble-laptop"),
        )
        owner.connect(1)

        val other = Harness(
            backgroundScope,
            backend,
            registry = registry,
            clientKey = ClientCredentials.sessionKey("lab-b", "rble-ci"),
            capabilities = setOf(Capabilities.LEASE_HOLDER),
        )
        other.sendHello(wanted = setOf(Capabilities.LEASE_HOLDER))
        other.send(2, Op.Connect(device))
        val err = assertIs<OpResult.Err>(other.frames.reply(2))
        // Enough to diagnose contention ("lab-a has it"), without publishing another tenant's
        // client id — which can carry a hostname or username it never meant to share.
        assertEquals("lab-a", err.error.holder?.principal)
        assertNull(err.error.holder?.clientId)
    }

    @Test
    fun descriptorWritesAreGatedIndependentlyOfCharacteristicWrites() = runTest {
        val backend = FakeBleBackend()
        val desc = DescRef(char.service, char.characteristic, "00002902-0000-1000-8000-00805f9b34fb")
        val otherDesc = DescRef(char.service, char.characteristic, "00002901-0000-1000-8000-00805f9b34fb")
        val policy = WritePolicy.decode(
            """{"version":1,"principals":{"owner":{"descriptorWrites":[
                {"service":"${char.service}","characteristic":"${char.characteristic}","descriptor":"${desc.descriptor}","maximumBytes":2}
            ]}}}""",
            knownPrincipals = setOf("owner"),
        )
        val h = policyAwareHarness(backgroundScope, backend, "owner", policy)
        h.connect(1)

        h.send(2, Op.WriteDescriptor(device, desc, byteArrayOf(0x01, 0x00)))
        assertIs<OpResult.Ok>(h.frames.reply(2))
        assertEquals(desc, backend.lastDescriptorWrite?.first)

        h.send(3, Op.WriteDescriptor(device, otherDesc, byteArrayOf(0x01)))
        val err = assertIs<OpResult.Err>(h.frames.reply(3))
        assertEquals(ErrorKind.POLICY_DENIED, err.error.kind)
        assertEquals(desc, backend.lastDescriptorWrite?.first)
    }

    @Test
    fun pairAndUnpairAreGated() = runTest {
        val backend = FakeBleBackend()

        val denying = WritePolicy.decode(
            """{"version":1,"principals":{"owner":{"writes":[],"pairing":false}}}""",
            knownPrincipals = setOf("owner"),
        )
        val denied = policyAwareHarness(backgroundScope, backend, "owner", denying)
        denied.connect(1)
        denied.send(2, Op.Pair(device))
        assertEquals(ErrorKind.POLICY_DENIED, assertIs<OpResult.Err>(denied.frames.reply(2)).error.kind)
        denied.send(3, Op.Unpair(device))
        assertEquals(ErrorKind.POLICY_DENIED, assertIs<OpResult.Err>(denied.frames.reply(3)).error.kind)
        assertTrue(backend.pairCalls.isEmpty())
        assertTrue(backend.unpairCalls.isEmpty())

        val allowing = WritePolicy.decode(
            """{"version":1,"principals":{"owner":{"writes":[],"pairing":true}}}""",
            knownPrincipals = setOf("owner"),
        )
        val allowed = Harness(backgroundScope, backend, clientKey = "owner", writePolicy = allowing)
        allowed.connect(4)
        allowed.send(5, Op.Pair(device))
        assertIs<OpResult.Ok>(allowed.frames.reply(5))
        assertEquals(listOf(device), backend.pairCalls)
    }

    @Test
    fun agentStatusReportsWhetherAPolicyIsConfigured() = runTest {
        val permissive = Harness(backgroundScope, FakeBleBackend())
        permissive.send(1, Op.AgentStatus)
        assertFalse(permissive.frames.status(1).settings.writePolicyEnforced)

        val enforced = Harness(
            backgroundScope,
            FakeBleBackend(),
            clientKey = "owner",
            writePolicy = denyingEverything("owner"),
        )
        enforced.send(1, Op.AgentStatus)
        assertTrue(enforced.frames.status(1).settings.writePolicyEnforced)
    }
}
