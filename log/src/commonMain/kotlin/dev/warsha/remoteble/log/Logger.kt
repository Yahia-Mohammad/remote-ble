package dev.warsha.remoteble.log

public object Logger {
    public var level: LogLevel? = null
    public var sink: LogSink = LogSink { _, _, _, _ -> }

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
        val min = this.level ?: return
        if (level >= min) sink.log(level, tag, message(), throwable)
    }
}
