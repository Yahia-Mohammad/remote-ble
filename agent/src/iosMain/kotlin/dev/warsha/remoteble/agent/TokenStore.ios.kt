package dev.warsha.remoteble.agent

import platform.Foundation.NSUserDefaults

/**
 * Token persistence for the iOS agent. Backed by [NSUserDefaults] (a plaintext plist), which is
 * consistent with this launcher's stated dev/test-only posture — the app already speaks cleartext
 * `ws://` and carries a blanket ATS exception (see `ios-agent/Info.plist`). A shipping build should
 * move these secrets to the Keychain (`Security.framework`); it's intentionally not done here to keep
 * the storage layer trivial and to avoid unverifiable `Security` cinterop in a dev tool.
 */
private fun keyFor(secret: AgentSecret): String = when (secret) {
    AgentSecret.CLIENT_TOKEN -> "remote_ble_agent_token"
    AgentSecret.OPERATOR_TOKEN -> "remote_ble_agent_operator_token"
}

actual suspend fun loadPersistedToken(secret: AgentSecret): String? =
    NSUserDefaults.standardUserDefaults.stringForKey(keyFor(secret))

actual suspend fun persistToken(token: String?, secret: AgentSecret) {
    val defaults = NSUserDefaults.standardUserDefaults
    val key = keyFor(secret)
    if (token.isNullOrBlank()) defaults.removeObjectForKey(key) else defaults.setObject(token, key)
}

private const val ENCRYPT_KEY = "remote_ble_agent_encrypt"

// Checked with objectForKey first: boolForKey reads an unset key as off, where it means "never chosen".
actual suspend fun loadEncryptPreference(): Boolean? {
    val defaults = NSUserDefaults.standardUserDefaults
    return if (defaults.objectForKey(ENCRYPT_KEY) == null) null else defaults.boolForKey(ENCRYPT_KEY)
}

actual suspend fun persistEncryptPreference(encrypt: Boolean) {
    NSUserDefaults.standardUserDefaults.setBool(encrypt, ENCRYPT_KEY)
}
