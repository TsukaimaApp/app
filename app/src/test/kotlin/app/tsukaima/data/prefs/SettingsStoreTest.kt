package app.tsukaima.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SettingsStoreTest {
    @get:Rule
    val tempDir = TemporaryFolder()

    private fun kotlinx.coroutines.test.TestScope.store() = storeWithDataStore().second

    /** The store and its backing DataStore, for tests that seed raw (legacy) keys. */
    private fun kotlinx.coroutines.test.TestScope.storeWithDataStore(): Pair<DataStore<Preferences>, SettingsStore> {
        val dataStore = PreferenceDataStoreFactory.create(
            scope = backgroundScope,
            produceFile = { File(tempDir.root, "settings.preferences_pb") },
        )
        return dataStore to SettingsStore(dataStore)
    }

    @Test
    fun normalizesServerUrl() = runTest {
        val store = store()
        store.update { it.copy(serverUrl = ServerUrl.normalize("hermes:9119/")!!) }
        assertEquals("http://hermes:9119", store.settings.first().serverUrl)
    }

    @Test
    fun defaultsAreSystemThemeAndDefaultProfile() = runTest {
        val s = store().settings.first()
        assertEquals(ThemeMode.System, s.theme)
        assertEquals("default", s.profile)
        assertEquals("", s.serverUrl)
        assertNull(s.model)
        assertFalse(s.onboarded)
    }

    @Test
    fun roundTripsAllFieldsAndClearsNullables() = runTest {
        val store = store()
        val full = Settings(
            serverUrl = "https://h.example.com",
            username = "mariusz",
            profile = "work",
            model = "m1",
            workspace = "/w",
            theme = ThemeMode.Dark,
            materialYou = true,
            lastSessionId = "abc",
            onboarded = true,
            askedNotifications = true,
        )
        store.update { full }
        assertEquals(full, store.settings.first())
        store.update { it.copy(model = null, workspace = null, lastSessionId = null) }
        val s = store.settings.first()
        assertNull(s.model)
        assertNull(s.workspace)
        assertNull(s.lastSessionId)
        assertEquals(ThemeMode.Dark, s.theme)
    }

    @Test
    fun notificationPrefsDefaultOnAndRoundTrip() = runTest {
        val store = store()
        assertEquals(NotificationPrefs(), store.settings.first().notifications)
        val off = NotificationPrefs(approvals = false, pairing = true, replies = false, alerts = false)
        store.update { it.copy(notifications = off) }
        assertEquals(off, store.settings.first().notifications)
        store.update { it.copy(notifications = it.notifications.copy(approvals = true)) }
        assertEquals(off.copy(approvals = true), store.settings.first().notifications)
    }

    @Test
    fun assistSessionRoundTrips() = runTest {
        val store = store()
        store.update { it.copy(assistSessionId = "s1", assistSessionAt = 42L) }
        val s = store.settings.first()
        assertEquals("s1", s.assistSessionId)
        assertEquals(42L, s.assistSessionAt)
        store.update { it.copy(assistSessionId = null, assistSessionAt = null) }
        val cleared = store.settings.first()
        assertNull(cleared.assistSessionId)
        assertNull(cleared.assistSessionAt)
    }

    @Test
    fun lastChatIdRoundTripsAndClears() = runTest {
        val store = store()
        assertNull(store.settings.first().lastChatId)
        store.update { it.copy(lastChatId = "20261024_101500_ab12cd") }
        assertEquals("20261024_101500_ab12cd", store.settings.first().lastChatId)
        store.update { it.copy(lastChatId = null) }
        assertNull(store.settings.first().lastChatId)
    }

    @Test
    fun voiceLanguageDefaultsToSystemAndRoundTrips() = runTest {
        val store = store()
        assertNull(store.settings.first().voiceLanguage)
        store.update { it.copy(voiceLanguage = "pl-PL") }
        assertEquals("pl-PL", store.settings.first().voiceLanguage)
        store.update { it.copy(voiceLanguage = null) }
        assertNull(store.settings.first().voiceLanguage)
    }

    @Test
    fun voiceProcessingDefaultsToPhoneAndRoundTrips() = runTest {
        val store = store()
        assertEquals(VoiceProcessing.Phone, store.settings.first().voiceProcessing)
        store.update { it.copy(voiceProcessing = VoiceProcessing.Server) }
        assertEquals(VoiceProcessing.Server, store.settings.first().voiceProcessing)
        store.update { it.copy(voiceProcessing = VoiceProcessing.Phone) }
        assertEquals(VoiceProcessing.Phone, store.settings.first().voiceProcessing)
    }

    @Test
    fun legacyServerRecognitionMigratesToServerProcessing() = runTest {
        val (dataStore, store) = storeWithDataStore()
        dataStore.edit { it[stringPreferencesKey("voice_recognition")] = "Server" } // 0.7.x chose Hermes
        assertEquals(VoiceProcessing.Server, store.settings.first().voiceProcessing)
        store.update { it.copy(voiceLanguage = "pl-PL") } // any write persists the migrated value
        assertEquals("Server", dataStore.data.first()[stringPreferencesKey("voice_processing")])
        store.update { it.copy(voiceProcessing = VoiceProcessing.Phone) }
        assertEquals("the new key wins over the legacy one", VoiceProcessing.Phone, store.settings.first().voiceProcessing)
    }

    @Test
    fun voiceProcessingIsPhoneWhenNeitherKeyIsSet() = runTest {
        val (dataStore, store) = storeWithDataStore()
        dataStore.edit { it[stringPreferencesKey("voice_output")] = "Server" } // unreleased key, ignored
        assertEquals(VoiceProcessing.Phone, store.settings.first().voiceProcessing)
        store.update { it }
        assertNull("the unreleased key is dropped", dataStore.data.first()[stringPreferencesKey("voice_output")])
    }
}
