package dev.warsha.remoteble.agent

/** No-op: the desktop CLI takes `--auth-token` fresh on every run (see `Main.kt`). */
actual suspend fun loadPersistedToken(): String? = null

actual suspend fun persistToken(token: String?) = Unit
