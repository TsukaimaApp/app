package app.tsukaima.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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

    @Test
    fun `the Live voice switch toggles and shows the caption from state`() {
        var set: Boolean? = null
        val captions = listOf(
            "On this server: GPT-Live, voice marin",
            "On the server but not ready",
            "Off on this server (chained voice)",
            "Couldn’t ask the server",
        )
        var caption by mutableStateOf(captions[0])
        var shown = 0
        setThemed {
            SettingsContent(
                SettingsUiState(liveVoice = true, liveVoiceCaption = caption),
                SettingsActions(onLiveVoice = { set = it }, onVoiceSectionShown = { shown++ }),
            )
        }
        assertEquals(1, shown)
        captions.forEach {
            caption = it
            compose.waitForIdle()
            compose.onNodeWithText(it).performScrollTo().assertExists()
        }
        compose.onNodeWithText("Live voice (GPT-Live)").performScrollTo().performClick()
        assertEquals(false, set)
    }
}
