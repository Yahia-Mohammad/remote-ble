package dev.warsha.remoteble.client

import dev.warsha.remoteble.protocol.CborProtocolCodec
import dev.warsha.remoteble.protocol.CharRef
import dev.warsha.remoteble.protocol.Command
import dev.warsha.remoteble.protocol.DeviceHandle
import dev.warsha.remoteble.protocol.Op
import dev.warsha.remoteble.protocol.OpResult
import dev.warsha.remoteble.protocol.ProtocolCodec
import dev.warsha.remoteble.protocol.Reply
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest

/**
 * Exercises [RemoteGattClient.writeWithoutResponseBurst] (0.8.3 / feature C, §2c option 1): the
 * client-side pipelining that keeps up to `window` WithoutResponse writes in flight instead of
 * serially awaiting each Reply. [SlowAgent] replies to every write only after [SlowAgent.replyDelay],
 * so a burst that pipelines correctly must send several frames before the first Reply lands —
 * `runTest`'s virtual time makes that overlap deterministic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WriteWithoutResponseBurstTest {

    private val device = DeviceHandle("FA:KE:00:00:00:0A")
    private val char = CharRef(
        service = "0000180d-0000-1000-8000-00805f9b34fb",
        characteristic = "00002a37-0000-1000-8000-00805f9b34fb",
    )

    /** Replies OK to every `Op.Write` after [replyDelay], recording arrival order and concurrency. */
    private class SlowAgent(
        incoming: Flow<ByteArray>,
        send: suspend (ByteArray) -> Unit,
        scope: CoroutineScope,
        private val replyDelay: Duration,
        private val codec: ProtocolCodec = CborProtocolCodec(),
    ) {
        val receivedValues = mutableListOf<ByteArray>()
        var peakOutstanding = 0
            private set
        private var outstanding = 0

        init {
            scope.launch {
                incoming.collect { bytes ->
                    val frame = codec.decode(bytes)
                    val op = (frame as? Command)?.op
                    if (frame is Command && op is Op.Write) {
                        receivedValues += op.value
                        outstanding++
                        peakOutstanding = maxOf(peakOutstanding, outstanding)
                        // A real agent handles commands concurrently — don't block this collector
                        // on the delay, or nothing else could ever be "in flight" at once.
                        scope.launch {
                            delay(replyDelay)
                            outstanding--
                            send(codec.encode(Reply(frame.cid, OpResult.Ok())))
                        }
                    }
                }
            }
        }
    }

    private class Harness(val session: AgentSession, val gatt: RemoteGattClient, val agent: SlowAgent)

    private fun CoroutineScope.harness(replyDelay: Duration): Harness {
        val codec = CborProtocolCodec()
        val transport = InMemoryTransport()
        val agent = SlowAgent(transport.agentIncoming, { transport.agentSend(it) }, this, replyDelay, codec)
        val session = DefaultAgentSession(transport.client, codec, this)
        return Harness(session, RemoteGattClient(device, session), agent)
    }

    private suspend fun Harness.awaitConnected() {
        session.transportState.first { it == TransportState.CONNECTED }
    }

    @Test
    fun burstSendsInSubmissionOrderAndCollectsEveryReply() = runTest {
        val h = backgroundScope.harness(replyDelay = 20.milliseconds)
        h.awaitConnected()
        val (gatt, agent) = h.gatt to h.agent

        val values = (0 until 12).map { byteArrayOf(it.toByte()) }
        val results = gatt.writeWithoutResponseBurst(char, values, window = 4)

        assertEquals(12, results.size)
        assertTrue(results.all { it is OpResult.Ok }, "every write in the burst should succeed")
        assertEquals(
            values.map { it.toList() },
            agent.receivedValues.map { it.toList() },
            "frames must reach the agent in the same order they were submitted",
        )
    }

    @Test
    fun burstBoundsInFlightRequestsToTheWindow() = runTest {
        val h = backgroundScope.harness(replyDelay = 50.milliseconds)
        h.awaitConnected()

        h.gatt.writeWithoutResponseBurst(char, (0 until 20).map { byteArrayOf(it.toByte()) }, window = 3)

        assertTrue(
            h.agent.peakOutstanding in 1..3,
            "at most `window` (3) writes should ever be outstanding at once, was ${h.agent.peakOutstanding}",
        )
    }

    @Test
    fun windowOfOneDegeneratesToSerialAwait() = runTest {
        val h = backgroundScope.harness(replyDelay = 10.milliseconds)
        h.awaitConnected()

        h.gatt.writeWithoutResponseBurst(char, (0 until 5).map { byteArrayOf(it.toByte()) }, window = 1)

        assertEquals(1, h.agent.peakOutstanding, "window=1 must never have more than one write in flight")
    }
}
