package dev.warsha.remoteble.client

import dev.warsha.remoteble.protocol.CborProtocolCodec
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.test.*

class SessionRetirementTest {
    private class Transport(private val failure: Exception? = null) : AgentTransport {
        override val state = MutableStateFlow(TransportState.CONNECTED)
        override val incoming = MutableSharedFlow<ByteArray>()
        val closing = CompletableDeferred<Unit>()
        val finishClose = CompletableDeferred<Unit>()
        var closeCalls = 0
        var closed = false
        override suspend fun connect() = Unit
        override suspend fun send(frame: ByteArray) = Unit
        override suspend fun close() {
            closeCalls++
            closing.complete(Unit)
            finishClose.await()
            failure?.let { throw it }
            closed = true
            state.value = TransportState.DISCONNECTED
        }
    }

    @Test
    fun cancelledCloseFinishesRetirementAndConcurrentCloseWaits() = runBlocking<Unit> {
        withTimeout(5_000) {
            val parent = SupervisorJob()
            val scope = CoroutineScope(parent + Dispatchers.Unconfined)
            val transport = Transport()
            val session = DefaultAgentSession(transport, CborProtocolCodec(), scope)
            val sessionJob = parent.children.single()
            try {
                transport.incoming.subscriptionCount.first { it == 1 }
                val closing = launch(start = CoroutineStart.UNDISPATCHED) { session.close() }
                transport.closing.await()
                closing.cancel()
                val concurrent = async(start = CoroutineStart.UNDISPATCHED) { session.close() }
                assertFalse(closing.isCompleted, "cancelled teardown must finish releasing its resources")
                assertFalse(concurrent.isCompleted, "close must await the first teardown")
                assertEquals(1, transport.closeCalls)
                transport.finishClose.complete(Unit)
                closing.join()
                concurrent.await()
                assertTrue(transport.closed)
                assertTrue(sessionJob.isCompleted, "all session children must be retired")
                assertTrue(parent.isActive, "the caller's scope remains usable")
                assertEquals(0, transport.incoming.subscriptionCount.value)
                assertEquals(SessionReadiness.CLOSED, session.readiness.value)
                session.close()
                assertEquals(1, transport.closeCalls)
            } finally {
                transport.finishClose.complete(Unit)
                session.close()
                parent.cancel()
            }
        }
    }

    @Test
    fun failedTransportCloseStillRetiresSessionChildren() = runBlocking<Unit> {
        withTimeout(5_000) {
            val parent = SupervisorJob()
            val scope = CoroutineScope(parent + Dispatchers.Unconfined)
            val failure = IllegalStateException("transport close failed")
            val transport = Transport(failure).apply { finishClose.complete(Unit) }
            val session = DefaultAgentSession(transport, CborProtocolCodec(), scope)
            val sessionJob = parent.children.single()
            try {
                transport.incoming.subscriptionCount.first { it == 1 }
                assertEquals(failure.message, assertFailsWith<IllegalStateException> { session.close() }.message)
                assertTrue(sessionJob.isCompleted)
                assertTrue(parent.isActive)
                assertEquals(0, transport.incoming.subscriptionCount.value)
                assertEquals(SessionReadiness.CLOSED, session.readiness.value)
                session.close()
                assertEquals(1, transport.closeCalls)
            } finally {
                parent.cancel()
            }
        }
    }
}
