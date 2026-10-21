package io.github.wzehirx.bpdf.core.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** What the result actions offer first: both Save and Share are always available; this only picks the primary one. */
enum class SaveBehavior { ASK, SHARE }

data class AppSettings(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val highContrast: Boolean = false,
    val saveBehavior: SaveBehavior = SaveBehavior.ASK,
)

/** User preferences, kept in their own DataStore file. Unknown stored values read as the defaults. */
class SettingsStore(private val store: DataStore<Preferences>) {
    val settings: Flow<AppSettings> = store.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { prefs ->
            AppSettings(
                theme = enumOrDefault(prefs[THEME], ThemeMode.SYSTEM),
                highContrast = prefs[HIGH_CONTRAST] ?: false,
                saveBehavior = enumOrDefault(prefs[SAVE_BEHAVIOR], SaveBehavior.ASK),
            )
        }

    suspend fun setTheme(mode: ThemeMode) { store.edit { it[THEME] = mode.name } }

    suspend fun setHighContrast(enabled: Boolean) { store.edit { it[HIGH_CONTRAST] = enabled } }

    suspend fun setSaveBehavior(behavior: SaveBehavior) { store.edit { it[SAVE_BEHAVIOR] = behavior.name } }

    private inline fun <reified E : Enum<E>> enumOrDefault(name: String?, default: E): E =
        enumValues<E>().firstOrNull { it.name == name } ?: default

    private companion object {
        val THEME = stringPreferencesKey("theme")
        val HIGH_CONTRAST = booleanPreferencesKey("high_contrast")
        val SAVE_BEHAVIOR = stringPreferencesKey("save_behavior")
    }
}
