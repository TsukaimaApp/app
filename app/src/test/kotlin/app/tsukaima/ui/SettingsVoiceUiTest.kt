package app.tsukaima.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import app.tsukaima.data.prefs.VoiceProcessing
import app.tsukaima.ui.settings.SettingsActions
import app.tsukaima.ui.settings.SettingsContent
import app.tsukaima.ui.settings.SettingsUiState
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsVoiceUiTest : ComposeUiTest() {

    @Test
    fun `the Voice section lists processing, language and assistant`() {
        setThemed { SettingsContent(SettingsUiState(voiceProcessing = VoiceProcessing.Phone), SettingsActions()) }

        compose.onNodeWithText("Voice").performScrollTo().assertExists()
        compose.onNodeWithText("Voice processing").performScrollTo().assertExists()
        compose.onNodeWithText("Language").performScrollTo().assertExists()
        compose.onNodeWithText("Use as device assistant").performScrollTo().assertExists()
    }

    @Test
    fun `tapping Voice processing offers On this phone and On Hermes`() {
        var opened = 0
        setThemed {
            SettingsContent(
                SettingsUiState(voiceProcessing = VoiceProcessing.Phone),
                SettingsActions(onVoiceProcessingOpen = { opened++ }),
            )
        }
        compose.onNodeWithText("On Hermes").assertDoesNotExist()

        compose.onNodeWithText("Voice processing").performScrollTo().performClick()

        // "On this phone" is also the row's subtitle: the sheet adds the second one.
        compose.onAllNodesWithText("On this phone").assertCountEquals(2)
        compose.onNodeWithText("On Hermes").assertExists()
        assertEquals(1, opened)
    }
}
