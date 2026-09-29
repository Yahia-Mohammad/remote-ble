package dev.warsha.remoteble.log

import platform.Foundation.NSLog

public object AppleLogSink : LogSink {
    override fun log(level: LogLevel, tag: String, message: String, throwable: Throwable?) {
        val msg = if (throwable != null) "$message | $throwable" else message
        NSLog("[%s] [%s] %s", level.name, tag, msg)
    }
}
