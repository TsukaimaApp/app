package app.tsukaima.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class ThemeMode { Warm, Dark, System }

/**
 * Where voice mode runs, both ways: [Phone] = the platform recogniser (live partials) and text-to-speech engine
 * (voice language setting); [Server] = each utterance uploaded to Hermes' `/api/audio/transcribe` relay and each reply
 * sentence synthesised by its `/api/audio/speak` (its configured STT and TTS and their language settings), played here.
 */
enum class VoiceProcessing { Phone, Server }

/**
 * Which kinds of notification the app posts (Settings → Notifications); all on by default. Android's own channel
 * switches still apply on top. [alerts] covers the gateway going down and a cron job failing.
 */
data class NotificationPrefs(
    /** Approvals, questions (`clarify`) and password/secret prompts. */
    val approvals: Boolean = true,
    val pairing: Boolean = true,
    /** "Hermes finished the task" when a reply completes while the app is in the background. */
    val replies: Boolean = true,
    val alerts: Boolean = true,
) {
    /** False when every kind is off: nothing would be posted, so the app does not stay connected in the background. */
    val anyOn: Boolean get() = approvals || pairing || replies || alerts
}

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
    /** Whether the app has already asked for `POST_NOTIFICATIONS` (asked once, after onboarding). */
    val askedNotifications: Boolean = false,
    val notifications: NotificationPrefs = NotificationPrefs(),
    /** Stored id of the chat the device-assistant sheet last used; resumed for a while ([assistSessionAt]). */
    val assistSessionId: String? = null,
    /** When [assistSessionId] was last used, epoch millis. */
    val assistSessionAt: Long? = null,
    /**
     * BCP-47 tag (`pl-PL`, `en-US`) for both speech recognition and text-to-speech in voice mode; null follows the
     * system default.
     */
    val voiceLanguage: String? = null,
    /** Where voice mode recognises speech and speaks replies; read when a voice session opens. */
    val voiceProcessing: VoiceProcessing = VoiceProcessing.Phone,
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
            prefs[ASKED_NOTIFICATIONS] = next.askedNotifications
            prefs[NOTIFY_APPROVALS] = next.notifications.approvals
            prefs[NOTIFY_PAIRING] = next.notifications.pairing
            prefs[NOTIFY_REPLIES] = next.notifications.replies
            prefs[NOTIFY_ALERTS] = next.notifications.alerts
            prefs.putOrRemove(ASSIST_SESSION_ID, next.assistSessionId)
            if (next.assistSessionAt == null) prefs.remove(ASSIST_SESSION_AT) else prefs[ASSIST_SESSION_AT] = next.assistSessionAt
            prefs.putOrRemove(VOICE_LANGUAGE, next.voiceLanguage)
            prefs[VOICE_PROCESSING] = next.voiceProcessing.name
            prefs.remove(LEGACY_VOICE_OUTPUT) // never shipped; its choice is not carried over
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
            askedNotifications = this[ASKED_NOTIFICATIONS] ?: defaults.askedNotifications,
            notifications = NotificationPrefs(
                approvals = this[NOTIFY_APPROVALS] ?: defaults.notifications.approvals,
                pairing = this[NOTIFY_PAIRING] ?: defaults.notifications.pairing,
                replies = this[NOTIFY_REPLIES] ?: defaults.notifications.replies,
                alerts = this[NOTIFY_ALERTS] ?: defaults.notifications.alerts,
            ),
            assistSessionId = this[ASSIST_SESSION_ID],
            assistSessionAt = this[ASSIST_SESSION_AT],
            voiceLanguage = this[VOICE_LANGUAGE],
            // 0.7.x stored only the recognition choice; it decides until the new key is written.
            voiceProcessing = (this[VOICE_PROCESSING] ?: this[LEGACY_VOICE_RECOGNITION])
                ?.let { name -> VoiceProcessing.entries.firstOrNull { it.name == name } }
                ?: defaults.voiceProcessing,
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
        val ASKED_NOTIFICATIONS = booleanPreferencesKey("asked_notifications")
        val NOTIFY_APPROVALS = booleanPreferencesKey("notify_approvals")
        val NOTIFY_PAIRING = booleanPreferencesKey("notify_pairing")
        val NOTIFY_REPLIES = booleanPreferencesKey("notify_replies")
        val NOTIFY_ALERTS = booleanPreferencesKey("notify_alerts")
        val ASSIST_SESSION_ID = stringPreferencesKey("assist_session_id")
        val ASSIST_SESSION_AT = longPreferencesKey("assist_session_at")
        val VOICE_LANGUAGE = stringPreferencesKey("voice_language")
        val VOICE_PROCESSING = stringPreferencesKey("voice_processing")

        /** 0.7.x's "Speech recognition" setting (`Phone`/`Server`): read only while [VOICE_PROCESSING] is unset. */
        val LEGACY_VOICE_RECOGNITION = stringPreferencesKey("voice_recognition")

        /** The unreleased separate speech output setting: removed on the next write. */
        val LEGACY_VOICE_OUTPUT = stringPreferencesKey("voice_output")
    }
}
