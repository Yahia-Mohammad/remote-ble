package dev.warsha.remoteble.log

import android.util.Log

public object AndroidLogSink : LogSink {
    override fun log(level: LogLevel, tag: String, message: String, throwable: Throwable?) {
        val msg = if (throwable != null) "$message | $throwable" else message
        when (level) {
            LogLevel.TRACE -> Log.v(tag, msg)
            LogLevel.DEBUG -> Log.d(tag, msg)
            LogLevel.INFO -> Log.i(tag, msg)
            LogLevel.WARN -> Log.w(tag, msg)
            LogLevel.ERROR -> Log.e(tag, msg, throwable)
        }
    }
}
