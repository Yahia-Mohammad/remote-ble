package dev.warsha.remoteble.client

import dev.warsha.remoteble.agent.FakeAgent
import dev.warsha.remoteble.protocol.AdvertisementDto
import dev.warsha.remoteble.protocol.AgentEvent
import dev.warsha.remoteble.protocol.AgentException
import dev.warsha.remoteble.protocol.BleBondState
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
import dev.warsha.remoteble.protocol.Op
import dev.warsha.remoteble.protocol.OpResult
import dev.warsha.remoteble.protocol.Reply
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class SessionEndToEndTest {

    private val char = CharRef(
        service = "0000180d-0000-1000-8000-00805f9b34fb",
        characteristic = "00002a37-0000-1000-8000-00805f9b34fb",
    )
    private val device = DeviceHandle("FA:KE:00:00:00:0A")

    /** Wires a client session against a [FakeAgent] over an [InMemoryTransport]. */
    private class Harness(
        scope: CoroutineScope,
        config: FakeAgent.Config = FakeAgent.Config(),
        clientCapabilities: Set<String> = emptySet(),
    ) {
        val codec = CborProtocolCodec()
        val transport = InMemoryTransport()
        val session = DefaultAgentSession(transport.client, codec, scope, clientCapabilities)
        val fakeAgent = FakeAgent(
            incoming = transport.agentIncoming,
            outgoing = { transport.agentSend(it) },
            scope = scope,
            config = config,
            codec = codec,
        ).also { it.start() }

        suspend fun awaitConnected() {
            session.transportState.first { it == TransportState.CONNECTED }
        }
    }

    /**
     * A minimal transport supporting repeated connect cycles that records every frame the
     * session sends, in order, and answers every Command with `Ok` (so ops succeed and enter
     * the replay set). [InMemoryTransport] can't reconnect (its pipes close on drop), and this
     * ordering property is about *what the client puts on the wire*, not agent behavior.
     */
    private class RecordingTransport(private val codec: CborProtocolCodec) : AgentTransport {
        val sent = mutableListOf<dev.warsha.remoteble.protocol.Frame>()
        private val mutableState = MutableStateFlow(TransportState.DISCONNECTED)
        private val replies = Channel<ByteArray>(Channel.UNLIMITED)
        var failConnects = false
        override val state: StateFlow<TransportState> = mutableState.asStateFlow()
        override val incoming: Flow<ByteArray> = replies.receiveAsFlow()

        override suspend fun connect() {
            mutableState.value = TransportState.CONNECTED
        }

        override suspend fun send(frame: ByteArray) {
            val decoded = codec.decode(frame)
            sent += decoded
            if (decoded is Command) {
                val result = if (failConnects && decoded.op is Op.Connect) {
                    // Use a non-retryable failed prerequisite so reconcile reaches its dependent
                    // replay decision in this deterministic transport test.
                    OpResult.Err(dev.warsha.remoteble.protocol.AgentError(ErrorKind.UNKNOWN_DEVICE))
                } else {
                    OpResult.Ok()
                }
                replies.trySend(codec.encode(Reply(decoded.cid, result)))
            }
        }

        override suspend fun close() {
            mutableState.value = TransportState.DISCONNECTED
        }

        // Split (not one call): a synchronous DISCONNECTED→CONNECTED flip gets conflated
        // away by the StateFlow — the session's collector must observe the drop first.
        fun drop() {
            mutableState.value = TransportState.DISCONNECTED
        }

        fun reconnect() {
            mutableState.value = TransportState.CONNECTED
        }
    }

    @Test
    fun reconnectSendsHelloBeforeReplayedOps() = runTest {
        val codec = CborProtocolCodec()
        val transport = RecordingTransport(codec)
        val session = DefaultAgentSession(transport, codec, backgroundScope)
        session.transportState.first { it == TransportState.CONNECTED }

        // Establish something to replay.
        assertIs<OpResult.Ok>(session.request(Op.Connect(device)))

        transport.drop()
        runCurrent() // the collector must observe the drop before the reconnect
        val sentBeforeDrop = transport.sent.size
        transport.reconnect()
        runCurrent() // let the hello + reconcile coroutine run to completion

        // Everything sent after the reconnect: the hello MUST precede the replayed Connect —
        // a replayed op served at the agent's pre-hello baseline would carry a translated
        // handle the agent couldn't route (the agent re-seeds its translator on the hello).
        val afterReconnect = transport.sent.drop(sentBeforeDrop)
        assertIs<ClientHello>(
            afterReconnect.firstOrNull(),
            "the reconnect's first frame must be the hello, before any replayed op",
        )
        assertTrue(
            afterReconnect.drop(1).any { it is Command && it.op is Op.Connect },
            "the replayed Connect must follow the hello",
        )
    }

    @Test
    fun failedReconnectSkipsDependentReplayButKeepsIndependentScans() = runTest {
        val codec = CborProtocolCodec()
        val transport = RecordingTransport(codec)
        val session = DefaultAgentSession(transport, codec, backgroundScope)
        session.transportState.first { it == TransportState.CONNECTED }

        assertIs<OpResult.Ok>(session.request(Op.Connect(device)))
        assertIs<OpResult.Ok>(session.request(Op.ObserveStart(7, device, char)))
        assertIs<OpResult.Ok>(session.request(Op.SetConnParams(device, ConnProfile.LOW_LATENCY)))
        assertIs<OpResult.Ok>(session.request(Op.ScanStart(9)))

        transport.drop()
        runCurrent()
        val beforeReconnect = transport.sent.size
        transport.failConnects = true
        transport.reconnect()
        advanceUntilIdle()
        runCurrent() // run continuations scheduled by the failed reconnect reply
        advanceTimeBy(1)
        runCurrent()

        val replay = transport.sent.drop(beforeReconnect).filterIsInstance<Command>().map { it.op }
        assertTrue(replay.any { it is Op.Connect })
        assertTrue(replay.any { it is Op.ScanStart }, "independent scans must replay; got $replay")
        assertFalse(replay.any { it is Op.ObserveStart }, "subscriptions require a successful reconnect")
        assertFalse(replay.any { it is Op.SetConnParams }, "connection parameters require a successful reconnect")
        assertEquals(SessionReadiness.DEGRADED, session.readiness.value)
        assertEquals(
            ReconciliationReport(
                connectionsAttempted = 1,
                connectionsRestored = 0,
                connectionsFailed = 1,
                dependentOperationsReplayed = 0,
                dependentOperationsSkipped = 2,
                scansReplayed = 1,
            ),
            session.reconciliationReport.value,
        )
    }

    @Test
    fun close_retires_session_and_prevents_later_reconnect_activity() = runTest {
        val codec = CborProtocolCodec()
        val transport = RecordingTransport(codec)
        val session = DefaultAgentSession(transport, codec, backgroundScope)
        session.transportState.first { it == TransportState.CONNECTED }
        runCurrent()
        val sentBeforeClose = transport.sent.size

        session.close()
        transport.reconnect()
        runCurrent()

        assertEquals(sentBeforeClose, transport.sent.size, "retired session must not send a new hello or replay")
        assertIs<OpResult.Err>(session.request(Op.Connect(device)))
    }

    @Test
    fun repeated_session_replacement_leaves_no_retired_session_active() = runTest {
        val codec = CborProtocolCodec()
        repeat(100) {
            val transport = RecordingTransport(codec)
            val session = DefaultAgentSession(transport, codec, backgroundScope)
            session.transportState.first { it == TransportState.CONNECTED }
            runCurrent()
            val sentBeforeClose = transport.sent.size
            session.close()
            transport.reconnect()
            runCurrent()
            assertEquals(sentBeforeClose, transport.sent.size)
        }
    }

    @Test
    fun connectReadWriteDiscoverMtuResolve() = runTest {
        val h = Harness(backgroundScope)
        h.awaitConnected()
        val peripheral = RemoteGattClient(device, h.session)

        peripheral.connect() // resolves (no throw)

        assertEquals(listOf<Byte>(0x42, 0x07), peripheral.read(char).toList())

        peripheral.write(char, byteArrayOf(1, 2, 3), withResponse = true) // resolves

        assertEquals(FakeAgent.DEFAULT_SERVICES, peripheral.discover())

        assertEquals(247, peripheral.requestMtu(247))
    }

    @Test
    fun readRssiRoundTrips() = runTest {
        val h = Harness(backgroundScope, FakeAgent.Config(rssi = -63))
        h.awaitConnected()
        val peripheral = RemoteGattClient(device, h.session)

        peripheral.connect()

        assertEquals(-63, peripheral.readRssi())
    }

    @Test
    fun handshakeNegotiatesCapabilityIntersection() = runTest {
        // Client understands two capabilities; the agent only supports one. The negotiated
        // set the client observes must be exactly the intersection.
        val h = Harness(
            backgroundScope,
            FakeAgent.Config(capabilities = setOf(Capabilities.DESCRIPTORS)),
            clientCapabilities = setOf(Capabilities.DESCRIPTORS, Capabilities.PAIRING),
        )
        h.awaitConnected()

        val negotiated = h.session.capabilities.first { it != null }
        assertEquals(setOf(Capabilities.DESCRIPTORS), negotiated)
    }

    @Test
    fun readinessBecomesReadyOnlyAfterServerHello() = runTest {
        val h = Harness(backgroundScope)
        h.awaitConnected()

        assertEquals(SessionReadiness.READY, h.session.readiness.first { it == SessionReadiness.READY })
    }

    @Test
    fun capabilityHelpersReflectNegotiatedSet() = runTest {
        val h = Harness(
            backgroundScope,
            FakeAgent.Config(capabilities = setOf(Capabilities.DESCRIPTORS)),
            clientCapabilities = setOf(Capabilities.DESCRIPTORS, Capabilities.PAIRING),
        )
        h.awaitConnected()

        // awaitCapabilities() suspends until the handshake lands, then yields the intersection.
        assertEquals(setOf(Capabilities.DESCRIPTORS), h.session.awaitCapabilities())
        assertTrue(h.session.supportsCapability(Capabilities.DESCRIPTORS))
        assertFalse(h.session.supportsCapability(Capabilities.PAIRING))
    }

    @Test
    fun descriptorReadWriteRoundTrips() = runTest {
        val h = Harness(backgroundScope, FakeAgent.Config(descriptorValue = byteArrayOf(0x01, 0x00)))
        h.awaitConnected()
        val peripheral = RemoteGattClient(device, h.session)
        val desc = DescRef(
            service = char.service,
            characteristic = char.characteristic,
            descriptor = "00002902-0000-1000-8000-00805f9b34fb",
        )

        assertEquals(listOf<Byte>(0x01, 0x00), peripheral.readDescriptor(desc).toList())
        peripheral.writeDescriptor(desc, byteArrayOf(0x00, 0x00)) // resolves (no throw)
    }

    @Test
    fun pairReturnsBondedAndUnpairResolves() = runTest {
        val h = Harness(backgroundScope)
        h.awaitConnected()
        val peripheral = RemoteGattClient(device, h.session)

        assertEquals(BleBondState.BONDED, peripheral.pair())
        peripheral.unpair() // resolves (no throw)
    }

    @Test
    fun scanSourceFlattensBatchedAdvertisements() = runTest {
        // Drive a hand-rolled agent that replies Ok and then pushes one ScanResultBatch,
        // proving RemoteScanSource flattens a batch into individual advertisements.
        val transport = InMemoryTransport()
        val codec = CborProtocolCodec()
        val session = DefaultAgentSession(transport.client, codec, backgroundScope)
        session.transportState.first { it == TransportState.CONNECTED }

        val adA = AdvertisementDto(device = DeviceHandle("AA"), name = "A", rssi = -40)
        val adB = AdvertisementDto(device = DeviceHandle("BB"), name = "B", rssi = -50)
        backgroundScope.launch {
            transport.agentIncoming.collect { bytes ->
                val frame = codec.decode(bytes)
                if (frame is Command) {
                    transport.agentSend(codec.encode(Reply(frame.cid, OpResult.Ok())))
                    val op = frame.op
                    if (op is Op.ScanStart) {
                        transport.agentSend(
                            codec.encode(Event(AgentEvent.ScanResultBatch(op.scanId, listOf(adA, adB)))),
                        )
                    }
                }
            }
        }

        val received = RemoteScanSource(session).advertisements().take(2).toList()
        assertEquals(listOf(adA, adB), received)
    }

    @Test
    fun requestConnectionPriorityResolves() = runTest {
        val h = Harness(backgroundScope)
        h.awaitConnected()
        val peripheral = RemoteGattClient(device, h.session)

        peripheral.requestConnectionPriority(ConnPriority.HIGH) // resolves (no throw)
    }

    @Test
    fun setConnParamsResolvesWithAndWithoutHint() = runTest {
        val h = Harness(backgroundScope)
        h.awaitConnected()
        val peripheral = RemoteGattClient(device, h.session)

        peripheral.setConnParams(ConnProfile.LOW_LATENCY) // resolves (no throw)
        peripheral.setConnParams(
            ConnProfile.BALANCED,
            ConnParamHint(minIntervalMs = 20.0, maxIntervalMs = 40.0, latency = 0, supervisionTimeoutMs = 5000),
        )
    }

    @Test
    fun observeStreamsNotificationsAndUnsubscribesOnCancel() = runTest {
        val h = Harness(backgroundScope)
        h.awaitConnected()
        val peripheral = RemoteGattClient(device, h.session)

        val received = peripheral.observe(char).take(4).toList()

        // Cycles through the configured notification values, filtered to this subId.
        val expected = (0 until 4).map { FakeAgent.DEFAULT_NOTIFICATIONS[it % 3].toList() }
        assertEquals(expected, received.map { it.toList() })

        // Cancellation (via take) issues observe.stop; the agent tears the subscription down.
        runCurrent()
        assertEquals(0, h.fakeAgent.activeNotifyCount, "subscription should be torn down on cancel")
    }

    @Test
    fun scannerStreamsAdvertisementsAndStopsOnCancel() = runTest {
        val h = Harness(backgroundScope)
        h.awaitConnected()
        val scanner = RemoteScanSource(h.session)

        val received = scanner.advertisements().take(4).toList()

        val expected = (0 until 4).map { FakeAgent.DEFAULT_ADVERTISEMENTS[it % 2] }
        assertEquals(expected, received)

        runCurrent()
        assertEquals(0, h.fakeAgent.activeScanCount, "scan should be stopped on cancel")
    }

    @Test
    fun concurrentScansAreDemuxedByScanId() = runTest {
        val h = Harness(backgroundScope)
        h.awaitConnected()
        val scanner = RemoteScanSource(h.session)

        // Two independent scans run at once; each must receive its own stream.
        val first = async { scanner.advertisements().take(2).toList() }
        val second = async { scanner.advertisements().take(2).toList() }

        assertEquals(2, first.await().size)
        assertEquals(2, second.await().size)
    }

    @Test
    fun connectGetsMoreHeadroomThanOrdinaryOps() = runTest {
        // The agent replies to everything after 200ms. With these (deliberately tight)
        // deadlines, connect's 1s budget absorbs it while a 50ms read does not — proving
        // each op class honors its own [RemoteTimeouts].
        val h = Harness(backgroundScope, FakeAgent.Config(replyDelay = 200.milliseconds))
        h.awaitConnected()
        val peripheral = RemoteGattClient(
            device,
            h.session,
            RemoteTimeouts(connect = 1.seconds, discover = 1.seconds, op = 50.milliseconds),
        )

        peripheral.connect() // within the connect budget — no throw

        val failure = assertFailsWith<AgentException> { peripheral.read(char) }
        assertEquals(ErrorKind.TIMEOUT, failure.error.kind)
    }

    @Test
    fun timeoutYieldsTimeoutError() = runTest {
        // No FakeAgent attached — nothing ever replies.
        val transport = InMemoryTransport()
        val session = DefaultAgentSession(transport.client, CborProtocolCodec(), backgroundScope)
        session.transportState.first { it == TransportState.CONNECTED }

        val result = session.request(Op.Read(device, char), timeout = 100.milliseconds)

        val err = assertIs<OpResult.Err>(result)
        assertEquals(ErrorKind.TIMEOUT, err.error.kind)
    }

    @Test
    fun transportDropFailsInFlightWithTransportLostAndDoesNotHang() = runTest {
        // Hold the reply far out so the request is genuinely in-flight when we drop.
        val h = Harness(backgroundScope, FakeAgent.Config(replyDelay = 100.seconds))
        h.awaitConnected()

        val inFlight = async { h.session.request(Op.Connect(device)) }
        runCurrent() // let the request register and suspend awaiting its reply
        h.transport.drop()

        val result = inFlight.await()
        val err = assertIs<OpResult.Err>(result)
        assertEquals(ErrorKind.TRANSPORT_LOST, err.error.kind)
        assertTrue(
            testScheduler.currentTime < 100.seconds.inWholeMilliseconds,
            "should fail immediately on drop, not wait for the reply",
        )
    }

    @Test
    fun requestAfterDropFailsWithTransportLost() = runTest {
        val h = Harness(backgroundScope)
        h.awaitConnected()
        h.transport.drop()

        val result = h.session.request(Op.Connect(device), timeout = 1.seconds)

        val err = assertIs<OpResult.Err>(result)
        assertEquals(ErrorKind.TRANSPORT_LOST, err.error.kind)
    }

    @Test
    fun requestAfterIncompatibleProtocolFailsWithoutTransportRetry() = runTest {
        val h = Harness(backgroundScope)
        h.awaitConnected()
        h.transport.client.mutableState.value = TransportState.INCOMPATIBLE_PROTOCOL
        h.session.transportState.first { it == TransportState.INCOMPATIBLE_PROTOCOL }

        val result = h.session.request(Op.Connect(device), timeout = 1.seconds)

        val err = assertIs<OpResult.Err>(result)
        assertEquals(ErrorKind.INCOMPATIBLE_PROTOCOL, err.error.kind)
    }

    @Test
    fun writeDropBeforeReplySurfacesTransportLostAndIsNotRetried() = runTest {
        // The completion-contract safety property (client-sdk.md): a write's Reply can be lost
        // in the window after the agent completes it but before it lands. That's ambiguous —
        // the write may have already succeeded on the radio — so the SDK deliberately does NOT
        // paper over it with a silent retry (Write is non-idempotent → RetryPolicies.None).
        var commandsSeen = 0
        val transport = InMemoryTransport()
        val codec = CborProtocolCodec()
        val session = DefaultAgentSession(transport.client, codec, backgroundScope)
        backgroundScope.launch {
            transport.agentIncoming.collect { bytes ->
                if (codec.decode(bytes) is Command) commandsSeen++
                // Never reply — simulates the Reply being lost, not merely slow.
            }
        }
        session.transportState.first { it == TransportState.CONNECTED }

        val inFlight = async {
            session.request(Op.Write(device, char, byteArrayOf(1, 2, 3), withResponse = true))
        }
        runCurrent() // let the write dispatch and register as pending before we drop the link
        transport.drop()

        val result = inFlight.await()
        val err = assertIs<OpResult.Err>(result)
        assertEquals(ErrorKind.TRANSPORT_LOST, err.error.kind)
        assertEquals(
            1,
            commandsSeen,
            "a write whose reply is lost must not be silently retried — the app owns the reconcile",
        )
    }
}
