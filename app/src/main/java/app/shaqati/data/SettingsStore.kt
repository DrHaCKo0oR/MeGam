package app.shaqati.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

private val Context.settingsDataStore by preferencesDataStore(name = "settings")

class SettingsStore(private val context: Context) {
    private val key = stringPreferencesKey("settings_json")
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        coerceInputValues = true
    }

    private fun decode(text: String?): Settings =
        text?.let { runCatching { json.decodeFromString(Settings.serializer(), it) }.getOrNull() } ?: Settings()

    val flow: Flow<Settings> = context.settingsDataStore.data
        .catch { emit(emptyPreferences()) }
        .map { decode(it[key]) }
        .distinctUntilChanged()

    suspend fun current(): Settings = flow.first()

    suspend fun update(change: (Settings) -> Settings): Settings {
        var out = Settings()
        context.settingsDataStore.edit { prefs ->
            out = change(decode(prefs[key]))
            prefs[key] = json.encodeToString(Settings.serializer(), out)
        }
        return out
    }
}
