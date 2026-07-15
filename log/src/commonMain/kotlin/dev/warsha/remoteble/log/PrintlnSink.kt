package dev.warsha.remoteble.log

public object PrintlnSink : LogSink {
    override fun log(level: LogLevel, tag: String, message: String, throwable: Throwable?) {
        val sb = StringBuilder()
        sb.append(level.name)
        sb.append(" [")
        sb.append(tag)
        sb.append("] ")
        sb.append(message)
        if (throwable != null) {
            sb.append(" | ")
            sb.append(throwable.toString())
        }
        println(sb.toString())
    }
}
