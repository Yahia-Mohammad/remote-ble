package dev.warsha.remoteble.androidclient

import dev.warsha.remoteble.androidclient.ble.AgentConnection
import dev.warsha.remoteble.client.*
import dev.warsha.remoteble.protocol.CborProtocolCodec
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.test.*

class AgentConnectionRetirementTest {
    private class Transport : AgentTransport {
        override val state = MutableStateFlow(TransportState.CONNECTED)
        override val incoming = MutableSharedFlow<ByteArray>()
        val closing = CompletableDeferred<Unit>()
        val finishClose = CompletableDeferred<Unit>()
        var closed = false
        override suspend fun connect() = Unit
        override suspend fun send(frame: ByteArray) = Unit
        override suspend fun close() {
            closing.complete(Unit)
            finishClose.await()
            closed = true
            state.value = TransportState.DISCONNECTED
        }
    }

    @Test
    fun cancelledReplacementFinishesOldSdkTeardownWithoutPublishingANewSession() = runBlocking<Unit> {
        withTimeout(5_000) {
            val parent = SupervisorJob()
            val scope = CoroutineScope(parent + Dispatchers.Unconfined)
            val transport = Transport()
            val old = DefaultAgentSession(transport, CborProtocolCodec(), scope)
            val oldJob = parent.children.single()
            val replacementTransport = Transport().apply { finishClose.complete(Unit) }
            var creations = 0
            val connection = AgentConnection(scope) { _, _, _ ->
                if (++creations == 1) old else DefaultAgentSession(replacementTransport, CborProtocolCodec(), scope)
            }
            try {
                assertSame(old, connection.connect("ws://old:8080/agent", "old"))
                val changing = launch(start = CoroutineStart.UNDISPATCHED) {
                    connection.connect("ws://new:8080/agent", "new")
                }
                transport.closing.await()
                changing.cancel()
                val closing = async(start = CoroutineStart.UNDISPATCHED) { connection.close() }
                assertFalse(changing.isCompleted)
                assertFalse(closing.isCompleted)
                assertEquals(1, creations)
                transport.finishClose.complete(Unit)
                changing.join()
                closing.await()
                assertTrue(transport.closed)
                assertTrue(oldJob.isCompleted)
                assertEquals(0, transport.incoming.subscriptionCount.value)
                assertEquals(1, creations, "the cancelled request cannot create a replacement")
                assertNotSame(old, connection.connect("ws://replacement:8080/agent", "replacement"))
                assertEquals(2, creations)
            } finally {
                transport.finishClose.complete(Unit)
                connection.close()
                parent.cancel()
            }
        }
    }
}
