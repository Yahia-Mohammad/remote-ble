package dev.warsha.remoteble.agent

actual fun platformName(): String = System.getProperty("os.name") ?: "jvm"
