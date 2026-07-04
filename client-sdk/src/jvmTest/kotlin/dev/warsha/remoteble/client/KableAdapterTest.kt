package dev.warsha.remoteble.client

import dev.warsha.remoteble.agent.AgentWebSocketServer
import dev.warsha.remoteble.agent.BleAgentBackend
import dev.warsha.remoteble.protocol.CborProtocolCodec
import dev.warsha.remoteble.protocol.DeviceHandle
import com.juul.kable.Peripheral
import com.juul.kable.State
import com.juul.kable.WriteType
import java.net.ServerSocket
import kotlin.coroutines.CoroutineContext
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Proves the "unchanged app code" promise: [appReadFirstCharacteristic] is written
 * purely against Kable's [Peripheral] and runs against a [RemotePeripheral] (remote
 * agent) with no changes. The same function compiles and runs against a local Kable
 * peripheral from `peripheralFor(BleMode.LOCAL, ...)` — that path needs a real radio,
 * so only the remote path is asserted here.
 */
class KableAdapterTest {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val httpClient = defaultWebSocketHttpClient()

    /** A dispatcher that records whether it ever dispatched, delegating execution. */
    private class TrackingDispatcher(
        private val delegate: CoroutineDispatcher = Dispatchers.Default,
    ) : CoroutineDispatcher() {
        @Volatile var dispatched = false
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            dispatched = true
            delegate.dispatch(context, block)
        }
    }

    @AfterTest
    fun tearDown() {
        httpClient.close()
        scope.cancel()
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private suspend fun connectedSession(port: Int): AgentSession {
        val session = DefaultAgentSession(
            WebSocketAgentTransport("ws://localhost:$port/agent", scope, httpClient),
            CborProtocolCodec(),
            scope,
        )
        withTimeout(10.seconds) { session.transportState.first { it == TransportState.CONNECTED } }
        return session
    }

    /** App logic written against Kable's [Peripheral] — knows nothing about "remote". */
    private suspend fun appReadFirstCharacteristic(peripheral: Peripheral): ByteArray {
        peripheral.connect()
        val services = peripheral.services.first { it != null }!!
        val characteristic = services.flatMap { it.characteristics }.first()
        return peripheral.read(characteristic)
    }

    @Test
    fun appLogicRunsUnchangedAgainstRemotePeripheral() = runBlocking {
        val port = freePort()
        val server = AgentWebSocketServer(port, backend = BleAgentBackend(StubBleBackend())).also { it.start() }
        try {
            val session = connectedSession(port)
            val peripheral: Peripheral = RemotePeripheral(DeviceHandle(StubBleBackend.DEVICE), session)

            val value = withTimeout(10.seconds) { appReadFirstCharacteristic(peripheral) }

            assertEquals(listOf<Byte>(0x11, 0x22), value.toList())
            assertIs<State.Connected>(peripheral.state.value)
            peripheral.close()
        } finally {
            server.stop()
        }
        Unit
    }

    @Test
    fun observeViaKableCharacteristicStreamsNotifications() = runBlocking {
        val port = freePort()
        val server = AgentWebSocketServer(port, backend = BleAgentBackend(StubBleBackend())).also { it.start() }
        try {
            val session = connectedSession(port)
            val peripheral: Peripheral = RemotePeripheral(DeviceHandle(StubBleBackend.DEVICE), session)
            peripheral.connect()
            val characteristic = peripheral.services.first { it != null }!!.flatMap { it.characteristics }.first()

            val notifications = withTimeout(10.seconds) { peripheral.observe(characteristic).take(2).toList() }

            assertEquals(2, notifications.size)
            assertEquals(listOf<Byte>(0x01), notifications[0].toList())
            peripheral.close()
        } finally {
            server.stop()
        }
        Unit
    }

    @Test
    fun negotiatedMtuFeedsMaximumWriteValueLength() = runBlocking {
        val port = freePort()
        val server = AgentWebSocketServer(port, backend = BleAgentBackend(StubBleBackend())).also { it.start() }
        try {
            val session = connectedSession(port)
            val peripheral = RemotePeripheral(DeviceHandle(StubBleBackend.DEVICE), session, requestedMtu = 185)

            // Before connect: the ATT default (23 - 3).
            assertEquals(20, peripheral.maximumWriteValueLengthForType(WriteType.WithoutResponse))

            peripheral.connect()
            // The stub echoes the requested MTU, so the negotiated value (185) now drives it.
            assertEquals(185 - 3, peripheral.maximumWriteValueLengthForType(WriteType.WithoutResponse))

            // After a disconnect the link is gone — fall back to the ATT default again.
            peripheral.disconnect()
            assertEquals(20, peripheral.maximumWriteValueLengthForType(WriteType.WithResponse))
            peripheral.close()
        } finally {
            server.stop()
        }
        Unit
    }

    @Test
    fun factoryThreadsInjectedDispatcherIntoPeripheralScope() = runBlocking {
        val port = freePort()
        val server = AgentWebSocketServer(port, backend = BleAgentBackend(StubBleBackend())).also { it.start() }
        try {
            val session = connectedSession(port)
            // A provider whose `default` is a distinct, tracked dispatcher: prove the factory
            // hands it through to the RemotePeripheral.scope (not the hard-coded default).
            val tracker = TrackingDispatcher()
            val factory = RemotePeripheralFactory(
                session,
                dispatchers = object : DispatcherProvider {
                    override val default = tracker
                },
            )

            val peripheral = factory.create(DeviceHandle(StubBleBackend.DEVICE))
            // Connecting launches the agent-event observer onto peripheral.scope, which is
            // built on the injected dispatcher — so the tracker must have run work.
            peripheral.connect()
            assertTrue(tracker.dispatched, "injected dispatcher should back the peripheral scope")
            peripheral.close()
        } finally {
            server.stop()
        }
        Unit
    }

    @Test
    fun scannerEmitsRemoteAdvertisementsAndFactoryBuildsPeripheral() = runBlocking {
        val port = freePort()
        val server = AgentWebSocketServer(port, backend = BleAgentBackend(StubBleBackend())).also { it.start() }
        try {
            val session = connectedSession(port)

            val advertisement = withTimeout(10.seconds) { RemoteScanner(session).advertisements.first() }
            assertEquals("Stub", advertisement.name)

            // The factory is the only place the local-vs-remote decision lives.
            val peripheral = peripheralFor(BleMode.REMOTE, advertisement, session)
            peripheral.connect()
            assertIs<State.Connected>(peripheral.state.value)
            peripheral.close()
        } finally {
            server.stop()
        }
        Unit
    }
}
