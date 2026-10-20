package labs.suchattai.tsukaima.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class ThemeMode { Warm, Dark, System }

data class Settings(
    val serverUrl: String = "",
    val username: String = "",
    val profile: String = "default",
    val model: String? = null,
    /** Provider slug of [model] (`model.options`); sent with it so the server routes the model correctly. */
    val modelProvider: String? = null,
    val workspace: String? = null,
    val theme: ThemeMode = ThemeMode.System,
    val materialYou: Boolean = false,
    val lastSessionId: String? = null,
    val onboarded: Boolean = false,
)

class SettingsStore(private val dataStore: DataStore<Preferences>) {
    val settings: Flow<Settings> = dataStore.data.map { it.toSettings() }

    suspend fun update(block: (Settings) -> Settings) {
        dataStore.edit { prefs ->
            val next = block(prefs.toSettings())
            prefs[SERVER_URL] = next.serverUrl
            prefs[USERNAME] = next.username
            prefs[PROFILE] = next.profile
            prefs.putOrRemove(MODEL, next.model)
            prefs.putOrRemove(MODEL_PROVIDER, next.modelProvider)
            prefs.putOrRemove(WORKSPACE, next.workspace)
            prefs[THEME] = next.theme.name
            prefs[MATERIAL_YOU] = next.materialYou
            prefs.putOrRemove(LAST_SESSION_ID, next.lastSessionId)
            prefs[ONBOARDED] = next.onboarded
        }
    }

    private fun androidx.datastore.preferences.core.MutablePreferences.putOrRemove(
        key: Preferences.Key<String>,
        value: String?,
    ) {
        if (value == null) remove(key) else this[key] = value
    }

    private fun Preferences.toSettings(): Settings {
        val defaults = Settings()
        return Settings(
            serverUrl = this[SERVER_URL] ?: defaults.serverUrl,
            username = this[USERNAME] ?: defaults.username,
            profile = this[PROFILE] ?: defaults.profile,
            model = this[MODEL],
            modelProvider = this[MODEL_PROVIDER],
            workspace = this[WORKSPACE],
            theme = this[THEME]?.let { name -> ThemeMode.entries.firstOrNull { it.name == name } }
                ?: defaults.theme,
            materialYou = this[MATERIAL_YOU] ?: defaults.materialYou,
            lastSessionId = this[LAST_SESSION_ID],
            onboarded = this[ONBOARDED] ?: defaults.onboarded,
        )
    }

    private companion object {
        val SERVER_URL = stringPreferencesKey("server_url")
        val USERNAME = stringPreferencesKey("username")
        val PROFILE = stringPreferencesKey("profile")
        val MODEL = stringPreferencesKey("model")
        val MODEL_PROVIDER = stringPreferencesKey("model_provider")
        val WORKSPACE = stringPreferencesKey("workspace")
        val THEME = stringPreferencesKey("theme")
        val MATERIAL_YOU = booleanPreferencesKey("material_you")
        val LAST_SESSION_ID = stringPreferencesKey("last_session_id")
        val ONBOARDED = booleanPreferencesKey("onboarded")
    }
}
