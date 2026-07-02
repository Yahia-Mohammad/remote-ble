package dev.warsha.ble.remoteble.client

import dev.warsha.ble.remoteble.agent.FakeAgent
import dev.warsha.ble.remoteble.protocol.AdvertisementDto
import dev.warsha.ble.remoteble.protocol.AgentEvent
import dev.warsha.ble.remoteble.protocol.AgentException
import dev.warsha.ble.remoteble.protocol.BleBondState
import dev.warsha.ble.remoteble.protocol.Capabilities
import dev.warsha.ble.remoteble.protocol.CborProtocolCodec
import dev.warsha.ble.remoteble.protocol.CharRef
import dev.warsha.ble.remoteble.protocol.Command
import dev.warsha.ble.remoteble.protocol.ConnPriority
import dev.warsha.ble.remoteble.protocol.DescRef
import dev.warsha.ble.remoteble.protocol.DeviceHandle
import dev.warsha.ble.remoteble.protocol.ErrorKind
import dev.warsha.ble.remoteble.protocol.Event
import dev.warsha.ble.remoteble.protocol.Op
import dev.warsha.ble.remoteble.protocol.OpResult
import dev.warsha.ble.remoteble.protocol.Reply
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runCurrent
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
}
