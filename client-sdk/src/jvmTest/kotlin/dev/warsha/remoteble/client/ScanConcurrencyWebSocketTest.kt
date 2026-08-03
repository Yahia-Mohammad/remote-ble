package dev.warsha.remoteble.client

import dev.warsha.remoteble.agent.AgentWebSocketServer
import dev.warsha.remoteble.agent.BleAgentBackend
import dev.warsha.remoteble.agent.BleBackend
import dev.warsha.remoteble.agent.ScanConcurrencyMode
import dev.warsha.remoteble.agent.ScanCoordinator
import dev.warsha.remoteble.protocol.AdvertisementDto
import dev.warsha.remoteble.protocol.AgentEvent
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
import dev.warsha.remoteble.protocol.Op
import dev.warsha.remoteble.protocol.OpResult
import dev.warsha.remoteble.protocol.Reply
import dev.warsha.remoteble.protocol.ScanFilter
import dev.warsha.remoteble.protocol.ServerHello
import dev.warsha.remoteble.protocol.ServiceNode
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.header
import io.ktor.websocket.Frame as WsFrame
import io.ktor.websocket.close
import io.ktor.websocket.readBytes
import io.ktor.websocket.send
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Protocol-level scan-concurrency coverage. The backend is deterministic and radio-less, but the
 * test drives the real Ktor WebSocket, CBOR codec, BleAgent, and ScanCoordinator path.
 */
class ScanConcurrencyWebSocketTest {

    private companion object {
        /** Upper bound on any single blocking receive — see [bounded]. */
        val RECEIVE_TIMEOUT = 10.seconds
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val httpClient = defaultWebSocketHttpClient()
    private val codec = CborProtocolCodec()

    @AfterTest
    fun tearDown() {
        httpClient.close()
        scope.cancel()
    }

    private class ScriptedBackend : BleBackend {
        val advertisements = MutableSharedFlow<AdvertisementDto>(extraBufferCapacity = 512)
        val scanFilters = CopyOnWriteArrayList<List<ScanFilter>>()
        val activeScans = AtomicInteger(0)

        override fun scan(filters: List<ScanFilter>): Flow<AdvertisementDto> = flow {
            scanFilters += filters
            activeScans.incrementAndGet()
            try {
                advertisements.collect { emit(it) }
            } finally {
                activeScans.decrementAndGet()
            }
        }

        suspend fun emit(advertisement: AdvertisementDto) {
            advertisements.emit(advertisement)
        }

        override suspend fun connect(device: DeviceHandle) = Unit
        override suspend fun disconnect(device: DeviceHandle) = Unit
        override suspend fun discover(device: DeviceHandle): List<ServiceNode> = emptyList()
        override suspend fun read(device: DeviceHandle, char: CharRef): ByteArray = ByteArray(0)
        override suspend fun write(device: DeviceHandle, char: CharRef, value: ByteArray, withResponse: Boolean) = Unit
        override fun observe(device: DeviceHandle, char: CharRef): Flow<ByteArray> = emptyFlow()
        override suspend fun requestMtu(device: DeviceHandle, mtu: Int): Int = mtu
        override suspend fun readDescriptor(device: DeviceHandle, desc: DescRef): ByteArray = ByteArray(0)
        override suspend fun writeDescriptor(device: DeviceHandle, desc: DescRef, value: ByteArray) = Unit
        override suspend fun pair(device: DeviceHandle) = dev.warsha.remoteble.protocol.BleBondState.BONDED
        override suspend fun unpair(device: DeviceHandle) = Unit
        override suspend fun requestConnectionPriority(device: DeviceHandle, priority: ConnPriority) = Unit
        override suspend fun setConnParams(device: DeviceHandle, profile: ConnProfile, hint: ConnParamHint?) = Unit
    }

    private data class RunningAgent(
        val server: AgentWebSocketServer,
        val backend: ScriptedBackend,
    )

    private suspend fun startAgent(
        mode: ScanConcurrencyMode,
        transportGrace: Duration = 500.milliseconds,
    ): RunningAgent {
        val backend = ScriptedBackend()
        val coordinator = ScanCoordinator(
            backend = backend,
            scope = scope,
            mode = mode,
            transportGrace = transportGrace,
        )
        val port = ServerSocket(0).use { it.localPort }
        val server = AgentWebSocketServer(
            port = port,
            backend = BleAgentBackend(
                backend = backend,
                lifecycleScope = scope,
                scanCoordinator = coordinator,
            ),
        )
        server.startAndAwaitReady(port)
        return RunningAgent(server, backend)
    }

    private fun url(server: AgentWebSocketServer): String = "ws://localhost:${server.portForTest()}/agent"

    /**
     * Bounds a blocking wait so a frame that never arrives fails one test instead of wedging the run.
     *
     * Every receive helper below loops on `socket.incoming.receive()`, which returns only when the
     * awaited frame shows up. These tests also turn on sub-second timings — transport grace,
     * scan-start ordering — that a loaded CI runner can miss. Unbounded, one missed frame suspends
     * `:client-sdk:jvmTest` forever: the task never completes, Gradle prints nothing further, and the
     * CI job sits until its own limit kills it with no indication of which test was stuck. That is
     * exactly what happened on this branch and on the scan-concurrency PR before it.
     *
     * [what] names the wait, so the failure says what never arrived rather than just "timed out".
     * Call sites asserting that nothing arrives use their own much shorter `withTimeoutOrNull`;
     * [RECEIVE_TIMEOUT] is deliberately far longer so it can never pre-empt one of those.
     */
    private suspend fun <T> bounded(what: String, block: suspend () -> T): T =
        withTimeoutOrNull(RECEIVE_TIMEOUT) { block() }
            ?: error("timed out after $RECEIVE_TIMEOUT waiting to $what")

    private suspend fun AgentWebSocketServer.openClient(
        clientId: String,
        capabilities: Set<String> = emptySet(),
        expectedCapabilities: Set<String>? = null,
    ): DefaultClientWebSocketSession = bounded("open a client session for '$clientId'") {
        val socket = httpClient.webSocketSession(urlString = url(this)) {
            header(dev.warsha.remoteble.protocol.CLIENT_ID_HEADER, clientId)
        }
        socket.send(WsFrame.Binary(true, codec.encode(ClientHello(capabilities = capabilities))))
        val hello = receiveFrame(socket)
        val serverHello = assertIs<ServerHello>(hello)
        assertEquals(1, serverHello.version)
        expectedCapabilities?.let { assertEquals(it, serverHello.capabilities) }
        socket
    }

    private suspend fun receiveFrame(socket: DefaultClientWebSocketSession): Frame =
        bounded("receive a protocol frame") {
            while (true) {
                when (val frame = socket.incoming.receive()) {
                    is WsFrame.Binary -> return@bounded codec.decode(frame.readBytes())
                    is WsFrame.Close -> error("WebSocket closed before the expected protocol frame")
                    else -> Unit
                }
            }
            @Suppress("UNREACHABLE_CODE") error("unreachable")
        }

    private suspend fun request(
        socket: DefaultClientWebSocketSession,
        cid: Long,
        op: Op,
    ): OpResult {
        sendCommand(socket, cid, op)
        return receiveReply(socket, cid)
    }

    private suspend fun sendCommand(socket: DefaultClientWebSocketSession, cid: Long, op: Op) {
        socket.send(WsFrame.Binary(true, codec.encode(Command(cid, op))))
    }

    private suspend fun receiveReply(socket: DefaultClientWebSocketSession, cid: Long): OpResult =
        bounded("receive the reply to cid=$cid") {
            while (true) {
                when (val frame = receiveFrame(socket)) {
                    is Reply -> if (frame.cid == cid) return@bounded frame.result
                    else -> Unit
                }
            }
            @Suppress("UNREACHABLE_CODE") error("unreachable")
        }

    private suspend fun receiveReplies(
        socket: DefaultClientWebSocketSession,
        expected: Set<Long>,
    ): Map<Long, OpResult> = bounded("receive replies to cids=$expected") {
        val replies = mutableMapOf<Long, OpResult>()
        while (replies.keys != expected) {
            when (val frame = receiveFrame(socket)) {
                is Reply -> if (frame.cid in expected) replies[frame.cid] = frame.result
                else -> Unit
            }
        }
        replies
    }

    private suspend fun receiveAdvertisement(
        socket: DefaultClientWebSocketSession,
        scanId: Long,
    ): AdvertisementDto = bounded("receive an advertisement for scanId=$scanId") {
        while (true) {
            when (val frame = receiveFrame(socket)) {
                is Event -> when (val event = frame.event) {
                    is AgentEvent.ScanResult if event.scanId == scanId -> return@bounded event.advertisement
                    is AgentEvent.ScanResultBatch if event.scanId == scanId ->
                        return@bounded event.advertisements.first()
                    else -> Unit
                }
                else -> Unit
            }
        }
        @Suppress("UNREACHABLE_CODE") error("unreachable")
    }

    /**
     * Waits until the coordinator's collector is actually subscribed to [ScriptedBackend.advertisements].
     *
     * Gating on `activeScans` alone was a race: [ScriptedBackend.scan] increments that counter
     * *before* it calls `advertisements.collect`, and the flow has `replay = 0`, so anything a test
     * emitted in the window between the two was dropped on the floor with no subscriber to take it.
     * The test then waited for an advertisement that had already been discarded. `subscriptionCount`
     * is the fact the emit actually depends on, so wait on that instead.
     */
    private suspend fun awaitStarted(backend: ScriptedBackend, count: Int = 1) {
        withTimeout(5.seconds) {
            while (
                backend.scanFilters.size < count ||
                backend.activeScans.get() == 0 ||
                backend.advertisements.subscriptionCount.value == 0
            ) {
                delay(1.milliseconds)
            }
        }
    }

    private suspend fun close(socket: DefaultClientWebSocketSession?) {
        runCatching { socket?.close() }
    }

    private fun serviceAdvertisement(device: String, service: String, name: String? = null, rssi: Int = -50) =
        AdvertisementDto(DeviceHandle(device), name, rssi, serviceUuids = listOf(service))

    private val modeCaps = setOf(
        Capabilities.SCAN_CONCURRENCY_MULTIPLEXED,
        Capabilities.SCAN_CONCURRENCY_SINGLE,
        Capabilities.SCAN_CONCURRENCY_UNCONTROLLED,
    )

    @Test
    fun scanConc01DifferentFilteredScansReceiveOnlyTheirOwnMatches() = runBlocking {
        val running = startAgent(ScanConcurrencyMode.MULTIPLEXED)
        var first: DefaultClientWebSocketSession? = null
        var second: DefaultClientWebSocketSession? = null
        try {
            first = running.server.openClient("scan-a", setOf(Capabilities.SCAN_CONCURRENCY_MULTIPLEXED))
            second = running.server.openClient("scan-b", setOf(Capabilities.SCAN_CONCURRENCY_MULTIPLEXED))
            assertIs<OpResult.Ok>(request(first!!, 1, Op.ScanStart(1, listOf(ScanFilter(service = "180d")))))
            assertIs<OpResult.Ok>(request(second!!, 1, Op.ScanStart(1, listOf(ScanFilter(service = "180f")))))
            awaitStarted(running.backend)

            running.backend.emit(serviceAdvertisement("hr", "180d"))
            running.backend.emit(serviceAdvertisement("battery", "180f"))

            assertEquals("hr", receiveAdvertisement(first!!, 1).device.value)
            assertEquals("battery", receiveAdvertisement(second!!, 1).device.value)
            assertNull(withTimeoutOrNull(100.milliseconds) { receiveAdvertisement(first!!, 1) })
            assertNull(withTimeoutOrNull(100.milliseconds) { receiveAdvertisement(second!!, 1) })
        } finally {
            close(first)
            close(second)
            running.server.stop()
        }
    }

    @Test
    fun scanConc02StoppingOneScanLeavesTheSurvivorOnTheSamePhysicalScan() = runBlocking {
        val running = startAgent(ScanConcurrencyMode.MULTIPLEXED)
        var first: DefaultClientWebSocketSession? = null
        var second: DefaultClientWebSocketSession? = null
        try {
            first = running.server.openClient("scan-a", setOf(Capabilities.SCAN_CONCURRENCY_MULTIPLEXED))
            second = running.server.openClient("scan-b", setOf(Capabilities.SCAN_CONCURRENCY_MULTIPLEXED))
            assertIs<OpResult.Ok>(request(first!!, 1, Op.ScanStart(1, listOf(ScanFilter(service = "180d")))))
            assertIs<OpResult.Ok>(request(second!!, 1, Op.ScanStart(1, listOf(ScanFilter(service = "180f")))))
            awaitStarted(running.backend)
            val startsBeforeStop = running.backend.scanFilters.size
            assertIs<OpResult.Ok>(request(first!!, 2, Op.ScanStop(1)))

            running.backend.emit(serviceAdvertisement("battery", "180f"))
            assertEquals("battery", receiveAdvertisement(second!!, 1).device.value)
            assertEquals(startsBeforeStop, running.backend.scanFilters.size)
            assertEquals(1, running.backend.activeScans.get())
        } finally {
            close(first)
            close(second)
            running.server.stop()
        }
    }

    @Test
    fun scanConc03LateJoinReceivesReplayWithinTheWindow() = runBlocking {
        val running = startAgent(ScanConcurrencyMode.MULTIPLEXED)
        var first: DefaultClientWebSocketSession? = null
        var late: DefaultClientWebSocketSession? = null
        try {
            first = running.server.openClient("scan-a", setOf(Capabilities.SCAN_CONCURRENCY_MULTIPLEXED))
            assertIs<OpResult.Ok>(request(first!!, 1, Op.ScanStart(1, listOf(ScanFilter(service = "180d")))))
            awaitStarted(running.backend)
            running.backend.emit(serviceAdvertisement("hr", "180d", "Heart Rate"))
            assertEquals("hr", receiveAdvertisement(first!!, 1).device.value)

            late = running.server.openClient("scan-b", setOf(Capabilities.SCAN_CONCURRENCY_MULTIPLEXED))
            assertIs<OpResult.Ok>(request(late!!, 1, Op.ScanStart(1, listOf(ScanFilter(service = "180d")))))
            assertEquals("hr", receiveAdvertisement(late!!, 1).device.value)
        } finally {
            close(first)
            close(late)
            running.server.stop()
        }
    }

    @Test
    fun scanConc04SparseIdentityIsMergedBeforeLogicalMatching() = runBlocking {
        val running = startAgent(ScanConcurrencyMode.MULTIPLEXED)
        var socket: DefaultClientWebSocketSession? = null
        try {
            socket = running.server.openClient("scan-a", setOf(Capabilities.SCAN_CONCURRENCY_MULTIPLEXED))
            assertIs<OpResult.Ok>(request(socket!!, 1, Op.ScanStart(1, listOf(ScanFilter(service = "180d")))))
            awaitStarted(running.backend)
            running.backend.emit(serviceAdvertisement("hr", "180d", "Heart Rate", -50))
            receiveAdvertisement(socket!!, 1)
            running.backend.emit(AdvertisementDto(DeviceHandle("hr"), null, -41))
            val sparse = receiveAdvertisement(socket!!, 1)
            assertEquals("Heart Rate", sparse.name)
            assertEquals(listOf("180d"), sparse.serviceUuids)
            assertEquals(-41, sparse.rssi)
        } finally {
            close(socket)
            running.server.stop()
        }
        Unit
        Unit
    }

    @Test
    fun scanConc05SingleModeRefusesADifferentKeyWithoutDisturbingIncumbent() = runBlocking {
        val running = startAgent(ScanConcurrencyMode.SINGLE)
        var incumbent: DefaultClientWebSocketSession? = null
        var contender: DefaultClientWebSocketSession? = null
        try {
            val cap = setOf(Capabilities.SCAN_CONCURRENCY_SINGLE)
            incumbent = running.server.openClient("scan-a", cap)
            contender = running.server.openClient("scan-b", cap)
            assertIs<OpResult.Ok>(request(incumbent!!, 1, Op.ScanStart(1, emptyList())))
            awaitStarted(running.backend)
            val refusal = assertIs<OpResult.Err>(request(contender!!, 1, Op.ScanStart(1, emptyList())))
            assertEquals(ErrorKind.SCAN_UNAVAILABLE, refusal.error.kind)
            running.backend.emit(AdvertisementDto(DeviceHandle("survivor"), "survivor", -50))
            assertEquals("survivor", receiveAdvertisement(incumbent!!, 1).device.value)
        } finally {
            close(incumbent)
            close(contender)
            running.server.stop()
        }
    }

    @Test
    fun scanConc06ReissuingTheIncumbentKeyReplacesItAtomically() = runBlocking {
        val running = startAgent(ScanConcurrencyMode.SINGLE)
        var socket: DefaultClientWebSocketSession? = null
        try {
            socket = running.server.openClient("scan-a", setOf(Capabilities.SCAN_CONCURRENCY_SINGLE))
            assertIs<OpResult.Ok>(request(socket!!, 1, Op.ScanStart(1, listOf(ScanFilter(service = "180d")))))
            awaitStarted(running.backend)
            assertIs<OpResult.Ok>(request(socket!!, 2, Op.ScanStart(1, listOf(ScanFilter(service = "180f")))))
            running.backend.emit(serviceAdvertisement("battery", "180f"))
            assertEquals("battery", receiveAdvertisement(socket!!, 1).device.value)
            assertNull(withTimeoutOrNull(100.milliseconds) { receiveAdvertisement(socket!!, 1) })
        } finally {
            close(socket)
            running.server.stop()
        }
    }

    @Test
    fun scanConc07ServerAdvertisesExactlyItsConfiguredMode() = runBlocking {
        val running = startAgent(ScanConcurrencyMode.SINGLE)
        var socket: DefaultClientWebSocketSession? = null
        try {
            socket = running.server.openClient(
                "scan-a",
                modeCaps,
                expectedCapabilities = setOf(Capabilities.SCAN_CONCURRENCY_SINGLE),
            )
            assertIs<OpResult.Ok>(request(socket!!, 1, Op.ScanStart(1, emptyList())))
        } finally {
            close(socket)
            running.server.stop()
        }
        Unit
    }

    @Test
    fun pipelinedSameIdLifecycleCommandsFollowWireOrder() = runBlocking {
        val running = startAgent(ScanConcurrencyMode.MULTIPLEXED)
        var socket: DefaultClientWebSocketSession? = null
        try {
            socket = running.server.openClient("scan-a", setOf(Capabilities.SCAN_CONCURRENCY_MULTIPLEXED))
            sendCommand(socket!!, 1, Op.ScanStart(1, listOf(ScanFilter(service = "180d"))))
            sendCommand(socket!!, 2, Op.ScanStart(1, listOf(ScanFilter(service = "180f"))))
            receiveReplies(socket!!, setOf(1, 2)).values.forEach { assertIs<OpResult.Ok>(it) }
            awaitStarted(running.backend)

            running.backend.emit(serviceAdvertisement("hr", "180d"))
            running.backend.emit(serviceAdvertisement("battery", "180f"))
            assertEquals("battery", receiveAdvertisement(socket!!, 1).device.value)
            assertNull(withTimeoutOrNull(100.milliseconds) { receiveAdvertisement(socket!!, 1) })

            assertIs<OpResult.Ok>(request(socket!!, 3, Op.ScanStop(1)))
            running.backend.emit(serviceAdvertisement("battery-2", "180f"))
            assertNull(withTimeoutOrNull(100.milliseconds) { receiveAdvertisement(socket!!, 1) })
        } finally {
            close(socket)
            running.server.stop()
        }
        Unit
    }

    @Test
    fun scanConc08LegacyClientGetsLegacyAgentBusyOnContention() = runBlocking {
        val running = startAgent(ScanConcurrencyMode.SINGLE)
        var incumbent: DefaultClientWebSocketSession? = null
        var legacy: DefaultClientWebSocketSession? = null
        try {
            incumbent = running.server.openClient("scan-a", modeCaps)
            legacy = running.server.openClient("scan-b", emptySet())
            assertIs<OpResult.Ok>(request(incumbent!!, 1, Op.ScanStart(1, emptyList())))
            awaitStarted(running.backend)
            val refusal = assertIs<OpResult.Err>(request(legacy!!, 1, Op.ScanStart(1, emptyList())))
            assertEquals(ErrorKind.AGENT_BUSY, refusal.error.kind)
        } finally {
            close(incumbent)
            close(legacy)
            running.server.stop()
        }
    }

    @Test
    fun scanConc09DroppedConnectionEntersGraceAndOtherClientsSurvive() = runBlocking {
        val running = startAgent(ScanConcurrencyMode.MULTIPLEXED, transportGrace = 100.milliseconds)
        var dropped: DefaultClientWebSocketSession? = null
        var survivor: DefaultClientWebSocketSession? = null
        var resumed: DefaultClientWebSocketSession? = null
        try {
            dropped = running.server.openClient("scan-a", setOf(Capabilities.SCAN_CONCURRENCY_MULTIPLEXED))
            survivor = running.server.openClient("scan-b", setOf(Capabilities.SCAN_CONCURRENCY_MULTIPLEXED))
            assertIs<OpResult.Ok>(request(dropped!!, 1, Op.ScanStart(1, emptyList())))
            assertIs<OpResult.Ok>(request(survivor!!, 1, Op.ScanStart(1, emptyList())))
            awaitStarted(running.backend)
            close(dropped)
            dropped = null
            delay(50.milliseconds)
            running.backend.emit(AdvertisementDto(DeviceHandle("survivor"), "survivor", -50))
            assertEquals("survivor", receiveAdvertisement(survivor!!, 1).device.value)
            delay(150.milliseconds)

            resumed = running.server.openClient("scan-a", setOf(Capabilities.SCAN_CONCURRENCY_MULTIPLEXED))
            assertIs<OpResult.Ok>(request(resumed!!, 1, Op.ScanStart(1, emptyList())))
        } finally {
            close(dropped)
            close(survivor)
            close(resumed)
            running.server.stop()
        }
        Unit
    }

    @Test
    fun scanConc10ReconnectWithinGraceRebindsOnTheFirstAttempt() = runBlocking {
        val running = startAgent(ScanConcurrencyMode.SINGLE, transportGrace = 500.milliseconds)
        var first: DefaultClientWebSocketSession? = null
        var rebound: DefaultClientWebSocketSession? = null
        var contender: DefaultClientWebSocketSession? = null
        try {
            val cap = setOf(Capabilities.SCAN_CONCURRENCY_SINGLE)
            first = running.server.openClient("scan-a", cap)
            assertIs<OpResult.Ok>(request(first!!, 1, Op.ScanStart(1, emptyList())))
            awaitStarted(running.backend)
            close(first)
            first = null
            delay(50.milliseconds)

            rebound = running.server.openClient("scan-a", cap)
            assertIs<OpResult.Ok>(request(rebound!!, 1, Op.ScanStart(1, emptyList())))
            contender = running.server.openClient("scan-b", cap)
            val refusal = assertIs<OpResult.Err>(request(contender!!, 1, Op.ScanStart(1, emptyList())))
            assertEquals(ErrorKind.SCAN_UNAVAILABLE, refusal.error.kind)
        } finally {
            close(first)
            close(rebound)
            close(contender)
            running.server.stop()
        }
    }

    @Test
    fun graceExpiryReleasesSingleSlotForADifferentKey() = runBlocking {
        val running = startAgent(ScanConcurrencyMode.SINGLE, transportGrace = 100.milliseconds)
        var owner: DefaultClientWebSocketSession? = null
        var contender: DefaultClientWebSocketSession? = null
        try {
            val cap = setOf(Capabilities.SCAN_CONCURRENCY_SINGLE)
            owner = running.server.openClient("scan-a", cap)
            assertIs<OpResult.Ok>(request(owner!!, 1, Op.ScanStart(1, emptyList())))
            close(owner)
            owner = null
            delay(25.milliseconds)

            contender = running.server.openClient("scan-b", cap)
            val beforeExpiry = assertIs<OpResult.Err>(request(contender!!, 1, Op.ScanStart(2, emptyList())))
            assertEquals(ErrorKind.SCAN_UNAVAILABLE, beforeExpiry.error.kind)
            delay(150.milliseconds)
            assertIs<OpResult.Ok>(request(contender!!, 2, Op.ScanStart(2, emptyList())))
        } finally {
            close(owner)
            close(contender)
            running.server.stop()
        }
        Unit
    }

    @Test
    fun scanConc11StaleGraceCleanupCannotRemoveAReboundScan() = runBlocking {
        val running = startAgent(ScanConcurrencyMode.SINGLE, transportGrace = 100.milliseconds)
        var first: DefaultClientWebSocketSession? = null
        var rebound: DefaultClientWebSocketSession? = null
        try {
            val cap = setOf(Capabilities.SCAN_CONCURRENCY_SINGLE)
            first = running.server.openClient("scan-a", cap)
            assertIs<OpResult.Ok>(request(first!!, 1, Op.ScanStart(1, emptyList())))
            awaitStarted(running.backend)
            close(first)
            first = null
            delay(50.milliseconds)
            rebound = running.server.openClient("scan-a", cap)
            assertIs<OpResult.Ok>(request(rebound!!, 1, Op.ScanStart(1, emptyList())))
            delay(150.milliseconds)
            running.backend.emit(AdvertisementDto(DeviceHandle("still-alive"), "still-alive", -50))
            assertEquals("still-alive", receiveAdvertisement(rebound!!, 1).device.value)
        } finally {
            close(first)
            close(rebound)
            running.server.stop()
        }
    }

    @Test
    fun uncontrolledModeUsesTheLegacyBackendPathForBothScans() = runBlocking {
        val running = startAgent(ScanConcurrencyMode.UNCONTROLLED)
        var first: DefaultClientWebSocketSession? = null
        var second: DefaultClientWebSocketSession? = null
        try {
            first = running.server.openClient(
                "scan-a",
                modeCaps,
                expectedCapabilities = setOf(Capabilities.SCAN_CONCURRENCY_UNCONTROLLED),
            )
            second = running.server.openClient(
                "scan-b",
                modeCaps,
                expectedCapabilities = setOf(Capabilities.SCAN_CONCURRENCY_UNCONTROLLED),
            )
            assertIs<OpResult.Ok>(request(first!!, 1, Op.ScanStart(1, emptyList())))
            assertIs<OpResult.Ok>(request(second!!, 1, Op.ScanStart(1, emptyList())))
            awaitStarted(running.backend, count = 2)
            assertEquals(2, running.backend.activeScans.get())

            running.backend.emit(AdvertisementDto(DeviceHandle("legacy"), "legacy", -50))
            assertEquals("legacy", receiveAdvertisement(first!!, 1).device.value)
            assertEquals("legacy", receiveAdvertisement(second!!, 1).device.value)
        } finally {
            close(first)
            close(second)
            running.server.stop()
        }
    }

    @Test
    fun stoppingAScanUnderLoadDoesNotKillTheConnection() = runBlocking {
        // Regression: stopScan closed the coordinator sink before cancelling its collector, and
        // the collector's send() into that closed channel threw and tore down the whole
        // connection instead of just ending the one scan. A scan.stop racing live delivery must
        // still reply, and the connection must keep serving commands afterward.
        val running = startAgent(ScanConcurrencyMode.MULTIPLEXED)
        var socket: DefaultClientWebSocketSession? = null
        val pumpScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            socket = running.server.openClient("scan-a", setOf(Capabilities.SCAN_CONCURRENCY_MULTIPLEXED))
            assertIs<OpResult.Ok>(request(socket!!, 1, Op.ScanStart(1, emptyList())))
            awaitStarted(running.backend)

            val pump = pumpScope.launch {
                var index = 0
                while (isActive) {
                    running.backend.emit(AdvertisementDto(DeviceHandle("d${index++}"), null, -50))
                }
            }
            delay(50.milliseconds)
            assertIs<OpResult.Ok>(withTimeout(5.seconds) { request(socket!!, 2, Op.ScanStop(1)) })
            pump.cancel()
            assertIs<OpResult.Ok>(withTimeout(5.seconds) { request(socket!!, 3, Op.ScanStart(2, emptyList())) })
        } finally {
            pumpScope.cancel()
            close(socket)
            running.server.stop()
        }
        Unit
    }

    /** Test-only access to the private port without changing the production server API. */
    private fun AgentWebSocketServer.portForTest(): Int =
        javaClass.getDeclaredField("port").apply { isAccessible = true }.getInt(this)
}
