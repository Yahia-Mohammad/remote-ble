package dev.warsha.remoteble.log

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

public class RateLimitedLog(
    private val window: Duration = 10.seconds,
) {
    private var firstAt: TimeSource.Monotonic.ValueTimeMark? = null
    private var suppressed: Int = 0

    public fun shouldLogAndIncrement(): Boolean {
        val now = TimeSource.Monotonic.markNow()
        val firstMark = firstAt
        if (firstMark == null) {
            firstAt = now
            suppressed = 0
            return true
        }
        if (now - firstMark > window) {
            firstAt = now
            suppressed = 0
            return true
        }
        suppressed++
        return false
    }

    public fun reset(): Pair<Boolean, Int> {
        val count = suppressed
        if (count > 0) {
            firstAt = null
            suppressed = 0
            return true to count
        }
        return false to 0
    }
}
