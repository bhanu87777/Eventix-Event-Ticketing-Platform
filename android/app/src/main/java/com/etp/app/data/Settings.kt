package com.etp.app.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private val Context.settingsStore by preferencesDataStore(name = "settings")

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** App-level preferences (theme), mirroring SessionManager's DataStore style. */
class SettingsManager(private val context: Context, scope: CoroutineScope) {
    private object Keys {
        val THEME = stringPreferencesKey("theme_mode")
    }

    private val _themeMode = MutableStateFlow(ThemeMode.SYSTEM)
    val themeMode: StateFlow<ThemeMode> = _themeMode

    init {
        scope.launch {
            val prefs = context.settingsStore.data.first()
            _themeMode.value = prefs[Keys.THEME]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
                ?: ThemeMode.SYSTEM
        }
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        _themeMode.value = mode
        context.settingsStore.edit { it[Keys.THEME] = mode.name }
    }
}
