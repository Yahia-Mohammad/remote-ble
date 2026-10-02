package dev.warsha.remoteble.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield

/** The bounded wait the iOS front puts around its listener becoming ready. */
class AwaitListeningTest {
    private var cancelled = 0
    private val cancel = { cancelled++; Unit }
    private val timedOut = { IllegalStateException("never ready") }

    @Test
    fun aListenerThatNeverBecomesReadyFailsAtTheTimeoutAndIsCancelled() = runTest {
        val failure = assertFailsWith<IllegalStateException> {
            awaitListening(CompletableDeferred(), 10.seconds, cancel, timedOut)
        }
        assertEquals("never ready", failure.message)
        assertEquals(1, cancelled)
    }

    @Test
    fun aReadyListenerGivesItsPortAndIsKept() = runTest {
        assertEquals(8080, awaitListening(CompletableDeferred(8080), 10.seconds, cancel, timedOut))
        assertEquals(0, cancelled)
    }

    @Test
    fun aReportedFailureIsRethrownAndTheListenerCancelled() = runTest {
        val ready = CompletableDeferred<Int>().apply { completeExceptionally(AgentBindException("0.0.0.0", 8080, null)) }
        assertFailsWith<AgentBindException> { awaitListening(ready, 10.seconds, cancel, timedOut) }
        assertEquals(1, cancelled)
    }

    @Test
    fun theCallersCancellationAlsoReleasesTheListener() = runTest {
        val waiting = async { awaitListening(CompletableDeferred(), 10.seconds, cancel, timedOut) }
        yield()
        waiting.cancelAndJoin()
        assertEquals(1, cancelled)
    }
}
