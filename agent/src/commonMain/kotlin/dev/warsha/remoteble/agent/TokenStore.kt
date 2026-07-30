package dev.warsha.remoteble.agent

/** Which of the agent's two independent secrets a [loadPersistedToken]/[persistToken] call means. */
enum class AgentSecret {
    /** The client bearer token, required before Start. Authorizes one client's own BLE session. */
    CLIENT_TOKEN,

    /**
     * The optional operator credential that enables the read-only HTTP status dashboard.
     *
     * Deliberately a *second* secret rather than a reuse of [CLIENT_TOKEN]: the dashboard exposes
     * every client's address, every peripheral lease and the rolling activity log — precisely the
     * cross-client information the op plane refuses to give a client (Rig A case 3 proved that
     * isolation on real radio). Reusing one token would hand every client app instance an observer of
     * all the others. `AgentWebSocketServer.init` enforces the distinction with a `require`.
     */
    OPERATOR_TOKEN,
}

/**
 * Persists the mobile agent's secrets across restarts — whether the user typed one in or it
 * was auto-generated on a previous run, so relaunching the app doesn't silently drop back to
 * running token-free. Only
 * meaningful for the restartable mobile UI ([dev.warsha.remoteble.agent.ui.AgentApp]); the
 * `jvm()` CLI target's `actual` is a no-op since the tokens are passed fresh each run.
 *
 * The default keeps the common call — the client token — a one-argument call.
 */
expect suspend fun loadPersistedToken(secret: AgentSecret = AgentSecret.CLIENT_TOKEN): String?

expect suspend fun persistToken(token: String?, secret: AgentSecret = AgentSecret.CLIENT_TOKEN)
