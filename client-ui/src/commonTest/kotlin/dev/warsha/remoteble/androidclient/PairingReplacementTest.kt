package dev.warsha.remoteble.androidclient

import dev.warsha.remoteble.androidclient.ble.AgentConnection
import dev.warsha.remoteble.client.*
import dev.warsha.remoteble.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.test.*
import kotlin.time.Duration

/** Exercises real scanning/peripheral orchestration with a controllable agent session. */
class PairingReplacementTest {
    private class Session(initialState: TransportState = TransportState.CONNECTED) : AgentSession {
        override val transportState = MutableStateFlow(initialState)
        override val readiness = MutableStateFlow(SessionReadiness.READY)
        override val reconciliationReport = MutableStateFlow<ReconciliationReport?>(null)
        override val capabilities = MutableStateFlow<Set<String>?>(emptySet())
        val events = MutableSharedFlow<AgentEvent>(extraBufferCapacity = 8)
        val operations = MutableStateFlow<List<Op>>(emptyList())
        val scanStarted = CompletableDeferred<Long>()
        val closeEntered = CompletableDeferred<Unit>()
        val allowClose = CompletableDeferred<Unit>()
        var closeCalls = 0
        override suspend fun request(op: Op, timeout: Duration, retry: RetryPolicy?): OpResult {
            operations.update { it + op }
            if (op is Op.ScanStart) scanStarted.complete(op.scanId)
            return OpResult.Ok(when (op) {
                is Op.Discover -> ResultPayload.Services(emptyList())
                is Op.RequestMtu -> ResultPayload.Mtu(23)
                else -> null
            })
        }
        override suspend fun dispatch(op: Op, timeout: Duration) = CompletableDeferred(request(op, timeout, null))
        override fun events() = events
        override fun nextStreamId() = 1L
        override fun fireAndForget(op: Op) { operations.update { it + op } }
        override suspend fun close() {
            closeCalls++
            closeEntered.complete(Unit)
            allowClose.await()
            transportState.value = TransportState.DISCONNECTED
        }
    }

    @Test
    fun cancellingCloseCannotAbandonTheOwnedSession() = runBlocking<Unit> {
        withTimeout(5_000) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val old = Session()
            val new = Session().apply { allowClose.complete(Unit) }
            var creations = 0
            val connection = AgentConnection(scope) { _, _, _ -> if (++creations == 1) old else new }
            try {
                connection.connect("ws://old:8080/agent", "old")
                val closing = launch(start = CoroutineStart.UNDISPATCHED) { connection.close() }
                old.closeEntered.await()
                closing.cancel()
                val connecting = async(start = CoroutineStart.UNDISPATCHED) {
                    connection.connect("ws://new:8080/agent", "new")
                }
                assertFalse(closing.isCompleted)
                assertFalse(connecting.isCompleted)
                assertEquals(1, creations)
                old.allowClose.complete(Unit)
                closing.join()
                assertSame(new, connecting.await())
                assertEquals(TransportState.DISCONNECTED, old.transportState.value)
                assertEquals(1, old.closeCalls)
                assertEquals(0, new.closeCalls)
            } finally {
                old.allowClose.complete(Unit)
                connection.close()
                scope.cancel()
            }
        }
    }

    @Test
    fun replacementDisconnectsOldDeviceClearsDiscoveriesAndWaitsBeforeScanning() = runBlocking<Unit> {
        withTimeout(5_000) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val old = Session()
            val new = Session().apply { allowClose.complete(Unit) }
            val targets = mutableListOf<Triple<String, String, AgentFingerprint?>>()
            val controller = RemoteBleController(scope) { owner ->
                AgentConnection(owner) { url, token, pin ->
                    targets += Triple(url, token, pin)
                    if (targets.size == 1) old else new
                }
            }
            try {
                controller.startScan()
                val scan = old.scanStarted.await()
                val handle = DeviceHandle("AA:BB:CC:DD:EE:FF")
                old.events.emit(AgentEvent.ScanResult(scan, AdvertisementDto(device = handle, name = "old-device", rssi = -60)))
                controller.uiState.first { it.discovered.isNotEmpty() }
                controller.connectDevice(handle, "old-device")
                controller.uiState.first { it.device?.isConnected == true }
                val pin = AgentFingerprint.ofSpkiSha256(ByteArray(32) { 2 })
                controller.offerPairing(AgentPairing("192.0.2.20", 8443, "new-token", pin).toUri())
                controller.confirmPairing()
                old.closeEntered.await()
                assertNull(controller.uiState.value.device)
                assertTrue(controller.uiState.value.discovered.isEmpty())
                assertTrue(old.operations.value.any { it is Op.Disconnect }, "old agent must receive lease release")
                assertTrue(old.operations.value.any { it is Op.ScanStop })
                controller.startScan()
                assertEquals(1, targets.size, "replacement cannot connect while teardown is suspended")
                old.allowClose.complete(Unit)
                new.scanStarted.await()
                assertEquals(Triple("wss://192.0.2.20:8443/agent", "new-token", pin), targets.last())
                assertEquals(1, old.closeCalls)
                assertEquals(TransportState.CONNECTED, controller.uiState.first { it.agentState == TransportState.CONNECTED }.agentState)
            } finally {
                old.allowClose.complete(Unit)
                controller.close()
                scope.cancel()
            }
        }
    }

    @Test
    fun replacementCancelsADeviceConnectionStillWaitingForTheOldTransport() = runBlocking<Unit> {
        withTimeout(5_000) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val old = Session(TransportState.CONNECTING).apply { allowClose.complete(Unit) }
            val new = Session().apply { allowClose.complete(Unit) }
            var creations = 0
            val controller = RemoteBleController(scope) { owner ->
                AgentConnection(owner) { _, _, _ -> if (++creations == 1) old else new }
            }
            try {
                controller.connectDevice(DeviceHandle("AA:BB:CC:DD:EE:FF"), "pending")
                assertEquals(1, creations)
                controller.offerPairing("remoteble://192.0.2.20:8080?token=new")
                controller.confirmPairing()
                old.closeEntered.await()
                controller.startScan()
                new.scanStarted.await()
                assertNull(controller.uiState.value.device)
                assertFalse(old.operations.value.any { it is Op.Connect })
                assertEquals(2, creations)
            } finally {
                controller.close()
                scope.cancel()
            }
        }
    }

    @Test
    fun concurrentCloseCannotRetireANewlyCreatedAgentSession() = runBlocking<Unit> {
        withTimeout(5_000) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val old = Session()
            val new = Session().apply { allowClose.complete(Unit) }
            var creations = 0
            val connection = AgentConnection(scope) { _, _, _ -> if (++creations == 1) old else new }
            try {
                assertSame(old, connection.connect("ws://old:8080/agent", "old"))
                val closing = async { connection.close() }
                old.closeEntered.await()
                val connecting = async { connection.connect("ws://new:8080/agent", "new") }
                yield()
                assertEquals(1, creations)
                old.allowClose.complete(Unit)
                closing.await()
                assertSame(new, connecting.await())
                assertEquals(0, new.closeCalls)
                assertEquals(TransportState.CONNECTED, new.transportState.value)
            } finally {
                old.allowClose.complete(Unit)
                connection.close()
                scope.cancel()
            }
        }
    }
}
