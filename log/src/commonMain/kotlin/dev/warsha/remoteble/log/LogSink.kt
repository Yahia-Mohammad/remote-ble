package dev.warsha.remoteble.log

/**
 * Receives logs synchronously on the caller's execution context.
 *
 * Implementations must be thread-safe because all RemoteBLE components can log concurrently, and
 * must not call [Logger] recursively or block on long-running I/O. Queueing/batching belongs inside
 * the sink when needed.
 */
public fun interface LogSink {
    public fun log(level: LogLevel, tag: String, message: String, throwable: Throwable?)
}
