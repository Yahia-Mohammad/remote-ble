package dev.warsha.remoteble.agent

import platform.Foundation.NSUserDefaults

/**
 * Token persistence for the iOS agent. Backed by [NSUserDefaults] (a plaintext plist), which is
 * consistent with this launcher's stated dev/test-only posture — the app already speaks cleartext
 * `ws://` and carries a blanket ATS exception (see `ios-agent/Info.plist`). A shipping build should
 * move this secret to the Keychain (`Security.framework`); it's intentionally not done here to keep
 * the storage layer trivial and to avoid unverifiable `Security` cinterop in a dev tool.
 */
private const val TOKEN_KEY = "remote_ble_agent_token"

actual suspend fun loadPersistedToken(): String? = NSUserDefaults.standardUserDefaults.stringForKey(TOKEN_KEY)

actual suspend fun persistToken(token: String?) {
    val defaults = NSUserDefaults.standardUserDefaults
    if (token.isNullOrBlank()) defaults.removeObjectForKey(TOKEN_KEY) else defaults.setObject(token, TOKEN_KEY)
}
