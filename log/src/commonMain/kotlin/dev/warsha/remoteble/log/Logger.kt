package dev.warsha.remoteble.log

import kotlin.concurrent.Volatile

public object Logger {
    /** One atomically published logging configuration. */
    public data class Configuration(
        val level: LogLevel? = null,
        val sink: LogSink = SilentSink,
    )

    private val SilentSink = LogSink { _, _, _, _ -> }

    @PublishedApi
    @Volatile
    internal var configuration: Configuration = Configuration()

    /**
     * Compatibility accessor. Prefer [configure] when changing both level and sink so callers
     * never publish an intermediate configuration.
     */
    public var level: LogLevel?
        get() = configuration.level
        set(value) {
            configuration = configuration.copy(level = value)
        }

    /**
     * Compatibility accessor. Prefer [configure] when changing both level and sink so callers
     * never publish an intermediate configuration.
     */
    public var sink: LogSink
        get() = configuration.sink
        set(value) {
            configuration = configuration.copy(sink = value)
        }

    /** Publishes [level] and [sink] together as one immutable configuration snapshot. */
    public fun configure(level: LogLevel?, sink: LogSink = SilentSink) {
        configuration = Configuration(level, sink)
    }

    public inline fun trace(
        tag: String,
        throwable: Throwable? = null,
        message: () -> String,
    ) {
        at(LogLevel.TRACE, tag, throwable, message)
    }

    public inline fun debug(
        tag: String,
        throwable: Throwable? = null,
        message: () -> String,
    ) {
        at(LogLevel.DEBUG, tag, throwable, message)
    }

    public inline fun info(
        tag: String,
        throwable: Throwable? = null,
        message: () -> String,
    ) {
        at(LogLevel.INFO, tag, throwable, message)
    }

    public inline fun warn(
        tag: String,
        throwable: Throwable? = null,
        message: () -> String,
    ) {
        at(LogLevel.WARN, tag, throwable, message)
    }

    public inline fun error(
        tag: String,
        throwable: Throwable? = null,
        message: () -> String,
    ) {
        at(LogLevel.ERROR, tag, throwable, message)
    }

    public inline fun at(
        level: LogLevel,
        tag: String,
        throwable: Throwable?,
        message: () -> String,
    ) {
        val config = configuration
        val min = config.level ?: return
        if (level >= min) config.sink.log(level, tag, message(), throwable)
    }
}
