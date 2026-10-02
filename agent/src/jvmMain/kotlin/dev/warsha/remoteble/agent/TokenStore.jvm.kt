package dev.warsha.remoteble.agent

/**
 * No-op: the desktop CLI takes both secrets fresh on every run — `--auth-token` and
 * `REMOTE_BLE_OPERATOR_TOKEN` (see `Main.kt`).
 */
actual suspend fun loadPersistedToken(secret: AgentSecret): String? = null

actual suspend fun persistToken(token: String?, secret: AgentSecret) = Unit

/** No-op: the desktop CLI takes `--tls` fresh on every run. */
actual suspend fun loadEncryptPreference(): Boolean? = null

actual suspend fun persistEncryptPreference(encrypt: Boolean) = Unit
