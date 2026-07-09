package dev.warsha.remoteble.client

import dev.warsha.remoteble.protocol.AgentError
import dev.warsha.remoteble.protocol.CborProtocolCodec
import dev.warsha.remoteble.protocol.CharRef
import dev.warsha.remoteble.protocol.Command
import dev.warsha.remoteble.protocol.DeviceHandle
import dev.warsha.remoteble.protocol.ErrorKind
import dev.warsha.remoteble.protocol.Op
import dev.warsha.remoteble.protocol.OpResult
import dev.warsha.remoteble.protocol.ProtocolCodec
import dev.warsha.remoteble.protocol.Reply
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest

/**
 * Exercises [RetryPolicy] in [DefaultAgentSession.request]: the interplay of transient-vs-permanent
 * errors ([ErrorKind.transient]) and idempotent-vs-not ops ([dev.warsha.remoteble.protocol.isIdempotent]).
 * A scripted agent returns a chosen [OpResult] per attempt, so each retry decision is deterministic;
 * `runTest` virtual time skips the backoff delays.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RetryPolicyTest {

    private val device = DeviceHandle("FA:KE:00:00:00:0A")
    private val char = CharRef(
        service = "0000180d-0000-1000-8000-00805f9b34fb",
        characteristic = "00002a37-0000-1000-8000-00805f9b34fb",
    )

    /** Replies to each `Command` via [reply], invoked with the 1-based count of commands seen. */
    private class ScriptedAgent(
        incoming: Flow<ByteArray>,
        send: suspend (ByteArray) -> Unit,
        scope: CoroutineScope,
        private val codec: ProtocolCodec = CborProtocolCodec(),
        private val reply: (op: Op, attempt: Int) -> OpResult,
    ) {
        var commandsSeen: Int = 0
            private set

        init {
            scope.launch {
                incoming.collect { bytes ->
                    val frame = codec.decode(bytes)
                    if (frame is Command) {
                        commandsSeen++
                        send(codec.encode(Reply(frame.cid, reply(frame.op, commandsSeen))))
                    }
                    // ClientHello and everything else is ignored — request() never waits on it.
                }
            }
        }
    }

    private fun CoroutineScope.session(
        retryPolicyFor: (Op) -> RetryPolicy = ::defaultRetryPolicyFor,
        reply: (op: Op, attempt: Int) -> OpResult,
    ): Pair<DefaultAgentSession, ScriptedAgent> {
        val codec = CborProtocolCodec()
        val transport = InMemoryTransport()
        val agent = ScriptedAgent(transport.agentIncoming, { transport.agentSend(it) }, this, codec, reply)
        val session = DefaultAgentSession(transport.client, codec, this, retryPolicyFor = retryPolicyFor)
        return session to agent
    }

    private suspend fun DefaultAgentSession.awaitConnected() =
        transportState.first { it == TransportState.CONNECTED }

    @Test
    fun defaultResolverRetriesConnectOnTransientError() = runTest {
        // Connect's default policy is retrying; AGENT_BUSY is transient → the 2nd attempt succeeds.
        val (session, agent) = backgroundScope.session { _, n ->
            if (n == 1) OpResult.Err(AgentError(ErrorKind.AGENT_BUSY)) else OpResult.Ok()
        }
        session.awaitConnected()
        assertIs<OpResult.Ok>(session.request(Op.Connect(device)))
        assertEquals(2, agent.commandsSeen, "Connect should have retried once by default")
    }

    @Test
    fun defaultResolverDoesNotRetryWrite() = runTest {
        // Write is non-idempotent, so its default policy is None even though WRITE_FAILED is transient.
        val (session, agent) = backgroundScope.session { _, _ ->
            OpResult.Err(AgentError(ErrorKind.WRITE_FAILED))
        }
        session.awaitConnected()
        val result = session.request(Op.Write(device, char, byteArrayOf(1), withResponse = true))
        assertEquals(ErrorKind.WRITE_FAILED, assertIs<OpResult.Err>(result).error.kind)
        assertEquals(1, agent.commandsSeen, "a write must not be retried by default")
    }

    @Test
    fun perCallOverrideRetriesWrite() = runTest {
        // The deliberate "this write is safe to repeat" opt-in: override the per-op default per call.
        val (session, agent) = backgroundScope.session { _, n ->
            if (n == 1) OpResult.Err(AgentError(ErrorKind.WRITE_FAILED)) else OpResult.Ok()
        }
        session.awaitConnected()
        val result = session.request(
            Op.Write(device, char, byteArrayOf(1), withResponse = true),
            retry = RetryPolicies.maxAttempts(3),
        )
        assertIs<OpResult.Ok>(result)
        assertEquals(2, agent.commandsSeen, "an explicit override must allow the write to be retried")
    }

    @Test
    fun perCallNoneDisablesConnectRetry() = runTest {
        // Override the other way: force a normally-retrying op to attempt exactly once.
        val (session, agent) = backgroundScope.session { _, _ ->
            OpResult.Err(AgentError(ErrorKind.AGENT_BUSY))
        }
        session.awaitConnected()
        val result = session.request(Op.Connect(device), retry = RetryPolicies.None)
        assertEquals(ErrorKind.AGENT_BUSY, assertIs<OpResult.Err>(result).error.kind)
        assertEquals(1, agent.commandsSeen, "RetryPolicies.None must attempt exactly once")
    }

    @Test
    fun permanentErrorIsNeverRetried() = runTest {
        // Even Connect's retrying default must not retry a permanent error.
        val (session, agent) = backgroundScope.session { _, _ ->
            OpResult.Err(AgentError(ErrorKind.UNKNOWN_DEVICE)) // transient = false
        }
        session.awaitConnected()
        val result = session.request(Op.Connect(device))
        assertEquals(ErrorKind.UNKNOWN_DEVICE, assertIs<OpResult.Err>(result).error.kind)
        assertEquals(1, agent.commandsSeen, "a permanent error must not be retried")
    }
}
