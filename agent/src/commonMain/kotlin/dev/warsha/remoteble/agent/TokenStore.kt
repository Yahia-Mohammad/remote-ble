package dev.warsha.remoteble.agent

/**
 * Persists the mobile agent's auth token across restarts — whether the user typed one in or it
 * was auto-generated on a previous run, so relaunching the app doesn't silently drop back to
 * running token-free. Only
 * meaningful for the restartable mobile UI ([dev.warsha.remoteble.agent.ui.AgentApp]); the
 * `jvm()` CLI target's `actual` is a no-op since `--auth-token` is passed fresh each run.
 */
expect suspend fun loadPersistedToken(): String?

expect suspend fun persistToken(token: String?)
