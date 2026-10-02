package dev.warsha.remoteble.agent

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

// One DataStore file, one key per secret. The file name and the client key are unchanged, so an
// existing install keeps the client token it already persisted.
private val android.content.Context.tokenDataStore by preferencesDataStore(name = "remote_ble_agent_token")

private fun keyFor(secret: AgentSecret) = when (secret) {
    AgentSecret.CLIENT_TOKEN -> stringPreferencesKey("auth_token")
    AgentSecret.OPERATOR_TOKEN -> stringPreferencesKey("operator_token")
}

actual suspend fun loadPersistedToken(secret: AgentSecret): String? {
    val context = androidAgentContext ?: return null
    return context.tokenDataStore.data.first()[keyFor(secret)]
}

actual suspend fun persistToken(token: String?, secret: AgentSecret) {
    val context = androidAgentContext ?: return
    val key = keyFor(secret)
    context.tokenDataStore.edit { prefs ->
        if (token.isNullOrBlank()) prefs.remove(key) else prefs[key] = token
    }
}

// Beside the tokens, in the same file: a setting, not a secret, but kept and cleared with them.
private val encryptKey = booleanPreferencesKey("encrypt_connections")

actual suspend fun loadEncryptPreference(): Boolean? {
    val context = androidAgentContext ?: return null
    return context.tokenDataStore.data.first()[encryptKey]
}

actual suspend fun persistEncryptPreference(encrypt: Boolean) {
    val context = androidAgentContext ?: return
    context.tokenDataStore.edit { prefs -> prefs[encryptKey] = encrypt }
}
