package dev.warsha.ble.remoteble.client

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * Two [AgentTransport] endpoints joined by channels in one process, with a way to
 * simulate a transport drop. The client end is a real [AgentTransport]; the agent
 * end is exposed as a raw byte flow + send for a [dev.warsha.ble.remoteble.agent.FakeAgent].
 */
class InMemoryTransport {
    private val clientToAgent = Channel<ByteArray>(Channel.UNLIMITED)
    private val agentToClient = Channel<ByteArray>(Channel.UNLIMITED)

    inner class Client : AgentTransport {
        val mutableState = MutableStateFlow(TransportState.DISCONNECTED)
        override val state: StateFlow<TransportState> = mutableState.asStateFlow()
        override val incoming: Flow<ByteArray> = agentToClient.receiveAsFlow()

        override suspend fun connect() {
            if (mutableState.value == TransportState.CONNECTED) return
            mutableState.value = TransportState.CONNECTING
            mutableState.value = TransportState.CONNECTED
        }

        override suspend fun send(frame: ByteArray) {
            if (mutableState.value != TransportState.CONNECTED) {
                throw TransportClosedException("not connected")
            }
            val result = clientToAgent.trySend(frame)
            if (result.isClosed) throw TransportClosedException("link dropped")
        }

        override suspend fun close() {
            mutableState.value = TransportState.DISCONNECTED
        }
    }

    val client: Client = Client()

    /** Commands from the client, for the fake agent to consume. */
    val agentIncoming: Flow<ByteArray> = clientToAgent.receiveAsFlow()

    /** Replies/events from the fake agent back to the client. */
    suspend fun agentSend(frame: ByteArray) {
        agentToClient.trySend(frame)
    }

    /** Simulate an abrupt transport loss: client goes DISCONNECTED, both pipes close. */
    fun drop() {
        client.mutableState.value = TransportState.DISCONNECTED
        clientToAgent.close()
        agentToClient.close()
    }
}
