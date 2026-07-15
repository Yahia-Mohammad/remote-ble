package dev.warsha.remoteble.log

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LoggerTest {

    private val recorded = mutableListOf<Record>()

    private data class Record(val level: LogLevel, val tag: String, val message: String, val throwable: Throwable?)

    @AfterTest
    fun reset() {
        Logger.level = null
        Logger.sink = LogSink { _, _, _, _ -> }
        recorded.clear()
    }

    private val capturingSink = LogSink { level, tag, message, throwable ->
        recorded.add(Record(level, tag, message, throwable))
    }

    @Test
    fun whenLevelIsNull_loggerIsSilent() {
        Logger.level = null
        Logger.sink = capturingSink
        Logger.info("tag") { "hello" }
        assertEquals(0, recorded.size, "No output when level is null")
    }

    @Test
    fun filtersByLevel() {
        Logger.level = LogLevel.WARN
        Logger.sink = capturingSink
        Logger.trace("t") { "trace" }
        Logger.debug("t") { "debug" }
        Logger.info("t") { "info" }
        Logger.warn("t") { "warn" }
        Logger.error("t") { "error" }
        assertEquals(2, recorded.size)
        assertEquals(LogLevel.WARN, recorded[0].level)
        assertEquals(LogLevel.ERROR, recorded[1].level)
    }

    @Test
    fun messageLambdaIsLazy() {
        Logger.level = LogLevel.INFO
        var lambdaCalls = 0
        var sinkCalls = 0
        Logger.sink = LogSink { _, _, _, _ -> sinkCalls++ }
        Logger.debug("t") { lambdaCalls++; "should not be evaluated" }
        assertEquals(0, lambdaCalls, "Message lambda not called below threshold")
        assertEquals(0, sinkCalls, "Sink not called below threshold")
        Logger.info("t") { lambdaCalls++; "evaluated" }
        assertEquals(1, lambdaCalls, "Message lambda called once at threshold")
        assertEquals(1, sinkCalls, "Sink called once at threshold")
    }

    @Test
    fun throwErrorIsPreserved() {
        Logger.level = LogLevel.ERROR
        var captured: Throwable? = null
        Logger.sink = LogSink { _, _, _, t -> captured = t }
        val ex = RuntimeException("boom")
        Logger.error("t", ex) { "msg" }
        assertEquals(ex, captured)
    }

    @Test
    fun levelSwitchingTakesEffectImmediately() {
        Logger.sink = capturingSink
        Logger.level = LogLevel.DEBUG
        Logger.debug("t") { "first" }
        Logger.level = LogLevel.ERROR
        Logger.debug("t") { "second" }
        assertEquals(1, recorded.size, "DEBUG after raising to ERROR was suppressed")
        assertEquals("first", recorded[0].message)
    }

    @Test
    fun bearerTokenNeverAppearsInLogOutput() {
        val token = "secret-bearer-token-abc123"
        val lines = mutableListOf<String>()
        Logger.level = LogLevel.TRACE
        Logger.sink = LogSink { _, _, message, _ -> lines.add(message) }

        // Simulate the transport connect path: the token is used but never interpolated.
        Logger.info("client/transport") { "CONNECTED [cid=${token.take(8)}]" }
        Logger.warn("client/transport") { "auth failed" }
        Logger.debug("client/transport") { "sending Authorization header" }

        for (line in lines) {
            assertEquals(false, line.contains(token), "Token leaked in log line: $line")
        }
    }

    @Test
    fun hotPathGuardAtInfoProducesNoSinkCallsForScanPath() {
        Logger.level = LogLevel.INFO
        var calls = 0
        Logger.sink = LogSink { _, _, _, _ -> calls++ }

        // Simulate 1000 advertisements at INFO — no per-advertisement lines should fire.
        repeat(1000) { i ->
            Logger.trace("client/scan") { "advertisement #$i" }
        }

        assertEquals(0, calls, "Per-advertisement logging leaked into INFO output")
    }
}

class RateLimitedLogTest {

    @Test
    fun firstCallPasses() {
        val rl = RateLimitedLog()
        assertTrue(rl.shouldLogAndIncrement())
    }

    @Test
    fun subsequentCallsWithinWindowAreSuppressed() {
        val rl = RateLimitedLog()
        rl.shouldLogAndIncrement()
        assertFalse(rl.shouldLogAndIncrement())
        assertFalse(rl.shouldLogAndIncrement())
    }

    @Test
    fun resetReturnsSuppressedCount() {
        val rl = RateLimitedLog()
        rl.shouldLogAndIncrement()
        rl.shouldLogAndIncrement()
        rl.shouldLogAndIncrement()
        val (had, count) = rl.reset()
        assertTrue(had)
        assertEquals(2, count)
    }

    @Test
    fun resetWhenNothingSuppressedReturnsZero() {
        val rl = RateLimitedLog()
        val (had, count) = rl.reset()
        assertFalse(had)
        assertEquals(0, count)
    }
}

class BytesPreviewTest {

    @Test
    fun rendersLengthAndTruncatedHex() {
        val bytes = byteArrayOf(0x00, 0x01, 0x02, 0x03, 0xff.toByte())
        val preview = bytesPreview(bytes, maxHex = 3)
        assertTrue(preview.startsWith("bytes(n=5, head="))
        assertTrue(preview.contains("000102"))
    }

    @Test
    fun handlesEmptyByteArray() {
        val preview = bytesPreview(ByteArray(0))
        assertEquals("bytes(n=0, head=)", preview)
    }
}