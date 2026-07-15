package dev.warsha.remoteble.log

public fun interface LogSink {
    public fun log(level: LogLevel, tag: String, message: String, throwable: Throwable?)
}
