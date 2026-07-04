package dev.warsha.remoteble.agent

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val android.content.Context.tokenDataStore by preferencesDataStore(name = "remote_ble_agent_token")
private val TOKEN_KEY = stringPreferencesKey("auth_token")

actual suspend fun loadPersistedToken(): String? {
    val context = androidAgentContext ?: return null
    return context.tokenDataStore.data.first()[TOKEN_KEY]
}

actual suspend fun persistToken(token: String?) {
    val context = androidAgentContext ?: return
    context.tokenDataStore.edit { prefs ->
        if (token.isNullOrBlank()) prefs.remove(TOKEN_KEY) else prefs[TOKEN_KEY] = token
    }
}
