package labs.suchattai.tsukaima.data.prefs

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
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

    private fun kotlinx.coroutines.test.TestScope.store() = SettingsStore(
        PreferenceDataStoreFactory.create(
            scope = backgroundScope,
            produceFile = { File(tempDir.root, "settings.preferences_pb") },
        ),
    )

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
}
