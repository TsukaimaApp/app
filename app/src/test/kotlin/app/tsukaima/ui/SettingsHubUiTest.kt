package app.tsukaima.ui

import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.unit.dp
import app.tsukaima.data.api.SkillDto
import app.tsukaima.data.api.ToolsetDto
import app.tsukaima.data.prefs.NotificationPrefs
import app.tsukaima.data.prefs.ThemeMode
import app.tsukaima.ui.settings.SettingsActions
import app.tsukaima.ui.settings.SettingsContent
import app.tsukaima.ui.settings.SettingsPage
import app.tsukaima.ui.settings.SettingsUiState
import app.tsukaima.ui.settings.SkillFilter
import app.tsukaima.ui.settings.settingsSummary
import app.tsukaima.ui.settings.skillGroups
import org.junit.Assert.assertEquals
import org.junit.Test

/** Settings as a short list of pages (0.14.1): summaries on the list, each topic on a page of its own. */
class SettingsHubUiTest : ComposeUiTest() {
    private val skills = listOf(
        SkillDto("github", category = "dev", enabled = true),
        SkillDto("codex", description = "Run the Codex CLI", category = "dev", enabled = false),
        SkillDto("docx", enabled = true),
        SkillDto("design-md", enabled = true),
    )
    private val state = SettingsUiState(
        storedUrl = "http://hermes.example.net:9119", username = "ada", passwordSaved = true, profile = "default",
        model = "kimi-k3", skills = skills,
        toolsets = listOf(ToolsetDto("web", enabled = true), ToolsetDto("x", enabled = false)),
        offlineSendMinutes = 10, theme = ThemeMode.System,
    )

    @Test
    fun `the list fits its topics as rows with their state, Hermes first`() {
        setThemed { SettingsContent(state, SettingsActions()) }
        compose.onNodeWithText("HERMES").assertExists()
        compose.onNodeWithText("hermes.example.net · signed in as ada").assertExists()
        compose.onNodeWithText("3 of 4 on").assertExists()
        compose.onNodeWithText("1 of 2 on").assertExists()
        compose.onNodeWithText("THIS PHONE").performScrollTo().assertExists()
        compose.onNodeWithText("Send within 10 minutes").performScrollTo().assertExists()
        // No switch of a hundred skills on the list itself.
        compose.onNodeWithText("github").assertDoesNotExist()
    }

    @Test
    fun `a row opens its page, the bar's Back returns to the list`() {
        val toggled = mutableListOf<Pair<String, Boolean>>()
        setThemed { SettingsContent(state, SettingsActions(onSkill = { name, on -> toggled += name to on })) }
        compose.onNodeWithText("Skills").assertHeightIsAtLeast(44.dp).performClick()
        compose.onNodeWithText("DEV").assertExists()
        compose.onNodeWithText("codex").performClick()
        assertEquals(listOf("codex" to true), toggled)

        compose.onNode(hasSetTextAction()).performTextInput("doc")
        compose.onNodeWithText("docx").assertExists()
        compose.onNodeWithText("github").assertDoesNotExist()

        compose.onNodeWithContentDescription("Back to Settings").performClick()
        compose.onNodeWithText("3 of 4 on").assertExists()
    }

    @Test
    @org.robolectric.annotation.Config(qualifiers = "w800dp-h1200dp")
    fun `a wide upright screen starts as one column and the bar's button puts the list beside the page`() {
        setThemed { SettingsContent(state, SettingsActions()) }
        compose.onNodeWithText("Hermes instance").assertDoesNotExist()
        compose.onNodeWithContentDescription("Show the list beside the page").performClick()
        // The list and the first page, side by side.
        compose.onNodeWithText("3 of 4 on").assertExists()
        compose.onNodeWithText("Hermes instance").assertExists()
        compose.onNodeWithText("Model").performClick()
        compose.onNodeWithText("Hermes instance").assertDoesNotExist()
        compose.onNodeWithText("3 of 4 on").assertExists()
        compose.onNodeWithContentDescription("Show one column").assertExists()
    }

    @Test
    @org.robolectric.annotation.Config(qualifiers = "w1200dp-h800dp")
    fun `a wide screen on its side shows two columns from the start`() {
        setThemed { SettingsContent(state, SettingsActions()) }
        compose.onNodeWithText("3 of 4 on").assertExists()
        compose.onNodeWithText("Hermes instance").assertExists()
    }

    @Test
    fun `a phone has no column switch`() {
        setThemed { SettingsContent(state, SettingsActions()) }
        compose.onNodeWithContentDescription("Show the list beside the page").assertDoesNotExist()
    }

    @Test
    fun skillsAreFoundByNameDescriptionOrCategoryAndGrouped() {
        assertEquals(listOf("D", "dev"), skillGroups(skills, "", SkillFilter.All).map { it.first })
        assertEquals(listOf("codex", "github"), skillGroups(skills, "", SkillFilter.All).last().second.map { it.name })
        assertEquals(listOf("codex"), skillGroups(skills, "", SkillFilter.Off).flatMap { it.second }.map { it.name })
        assertEquals(listOf("codex"), skillGroups(skills, "cli", SkillFilter.All).flatMap { it.second }.map { it.name })
        assertEquals(listOf("github"), skillGroups(skills, "DEV", SkillFilter.On).flatMap { it.second }.map { it.name })
        assertEquals(emptyList<String>(), skillGroups(skills, "zzz", SkillFilter.All).map { it.first })
    }

    @Test
    fun summariesSayTheStateInOneLine() {
        assertEquals("Not signed in", settingsSummary(SettingsPage.Connection, SettingsUiState()))
        assertEquals("hermes.example.net · signed in as ada · profile work", settingsSummary(SettingsPage.Connection, state.copy(profile = "work")))
        assertEquals("kimi-k3", settingsSummary(SettingsPage.Model, state))
        assertEquals("Loads once you are signed in", settingsSummary(SettingsPage.Skills, SettingsUiState()))
        assertEquals("No paired accounts", settingsSummary(SettingsPage.Pairing, state))
        assertEquals("All on", settingsSummary(SettingsPage.Notifications, state))
        assertEquals("2 of 4 on", settingsSummary(SettingsPage.Notifications, state.copy(notifications = NotificationPrefs(approvals = false, pairing = false))))
        assertEquals("On this phone · Live voice on", settingsSummary(SettingsPage.Voice, state.copy(liveVoice = true)))
        assertEquals("Offline messages stay drafts", settingsSummary(SettingsPage.Offline, state.copy(offlineSendMinutes = 0)))
        assertEquals("System · Material You", settingsSummary(SettingsPage.Appearance, state.copy(materialYou = true)))
        assertEquals("Logs, usage, problem report", settingsSummary(SettingsPage.Diagnostics, state))
    }
}
