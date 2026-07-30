package dev.warsha.remoteble.agent

/**
 * No-op: the desktop CLI takes both secrets fresh on every run — `--auth-token` and
 * `REMOTE_BLE_OPERATOR_TOKEN` (see `Main.kt`).
 */
actual suspend fun loadPersistedToken(secret: AgentSecret): String? = null

actual suspend fun persistToken(token: String?, secret: AgentSecret) = Unit
