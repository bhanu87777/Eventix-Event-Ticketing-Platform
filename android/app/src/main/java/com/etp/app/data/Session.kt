package com.etp.app.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first

private val Context.dataStore by preferencesDataStore(name = "session")

sealed interface SessionState {
    data object Loading : SessionState
    data object LoggedOut : SessionState
    data class LoggedIn(val user: User) : SessionState
}

class SessionManager(private val context: Context) {
    private object Keys {
        val TOKEN = stringPreferencesKey("token")
        val USER_ID = longPreferencesKey("user_id")
        val EMAIL = stringPreferencesKey("email")
        val NAME = stringPreferencesKey("name")
        val ROLE = stringPreferencesKey("role")
    }

    private val _state = MutableStateFlow<SessionState>(SessionState.Loading)
    val state: StateFlow<SessionState> = _state

    /** Read synchronously by the OkHttp auth interceptor. */
    @Volatile
    var token: String? = null
        private set

    suspend fun load() {
        val prefs = context.dataStore.data.first()
        val savedToken = prefs[Keys.TOKEN]
        if (savedToken == null) {
            _state.value = SessionState.LoggedOut
            return
        }
        token = savedToken
        _state.value = SessionState.LoggedIn(
            User(
                id = prefs[Keys.USER_ID] ?: 0,
                email = prefs[Keys.EMAIL] ?: "",
                name = prefs[Keys.NAME] ?: "",
                role = prefs[Keys.ROLE] ?: "attendee",
            ),
        )
    }

    suspend fun save(auth: AuthResponse) {
        context.dataStore.edit {
            it[Keys.TOKEN] = auth.token
            it[Keys.USER_ID] = auth.user.id
            it[Keys.EMAIL] = auth.user.email
            it[Keys.NAME] = auth.user.name
            it[Keys.ROLE] = auth.user.role
        }
        token = auth.token
        _state.value = SessionState.LoggedIn(auth.user)
    }

    /** Swap in a fresh token + user (e.g. after a rename re-issues the JWT). */
    suspend fun update(newToken: String, user: User) {
        save(AuthResponse(newToken, user))
    }

    suspend fun logout() {
        context.dataStore.edit { it.clear() }
        token = null
        _state.value = SessionState.LoggedOut
    }
}
