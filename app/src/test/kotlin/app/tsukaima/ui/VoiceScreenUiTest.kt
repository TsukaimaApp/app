package app.tsukaima.ui

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.tsukaima.domain.chat.AgentStatus
import app.tsukaima.domain.chat.ChatState
import app.tsukaima.domain.voice.LiveInfo
import app.tsukaima.domain.voice.VoicePhase
import app.tsukaima.domain.voice.VoiceState
import app.tsukaima.ui.chat.ChatActions
import app.tsukaima.ui.chat.ChatUiState
import app.tsukaima.ui.voice.VoiceScreen
import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceScreenUiTest : ComposeUiTest() {

    private fun render(phase: VoicePhase, status: AgentStatus = AgentStatus.Idle, actions: ChatActions = ChatActions()) =
        setThemed {
            VoiceScreen(
                state = ChatUiState(chat = ChatState(status = status), model = "Hermes 4 · 405B", isNew = false),
                voice = VoiceState(phase = phase),
                actions = actions,
                onModel = {},
            )
        }

    @Test
    fun `listening shows Listening…`() {
        render(VoicePhase.Listening)
        compose.onNodeWithText("Listening…").assertExists()
    }

    @Test
    fun `thinking shows the chat's thinking flair`() {
        render(VoicePhase.Thinking, AgentStatus.Thinking("Pondering…"))
        compose.onNodeWithText("Pondering…").assertExists()
    }

    @Test
    fun `speaking shows Speaking`() {
        render(VoicePhase.Speaking, AgentStatus.Replying)
        compose.onNodeWithText("Speaking").assertExists()
    }

    @Test
    fun `approval shows the waiting request`() {
        render(VoicePhase.Approval, AgentStatus.Approval)
        compose.onNodeWithText("Waiting for your approval").assertExists()
    }

    @Test
    fun `the End control is labelled End voice and closes voice mode`() {
        var closes = 0
        render(VoicePhase.Listening, actions = ChatActions(onVoiceClose = { closes++ }))
        // The label sits on the icon; the 64dp clickable circle merges it, so the labelled node is the button.
        compose.onNodeWithContentDescription("End voice").assertHasClickAction().performClick()
        assertEquals(1, closes)
    }

    private fun renderLive(voice: VoiceState) = setThemed {
        VoiceScreen(
            state = ChatUiState(chat = ChatState(status = AgentStatus.Idle), model = "Hermes 4 · 405B", isNew = false),
            voice = voice,
            actions = ChatActions(),
            onModel = {},
        )
    }

    @Test
    fun `live mode shows voice chip timer and caption`() {
        renderLive(VoiceState(phase = VoicePhase.Listening, live = LiveInfo("marin", 84), caption = "Sure, checking."))

        compose.onNodeWithText("Live · marin").assertExists().assertHasNoClickAction()
        compose.onNodeWithText("Listening · 1:24").assertExists()
        compose.onNodeWithText("Sure, checking.").assertExists()
        compose.onNodeWithContentDescription("Voice language").assertDoesNotExist()
    }

    @Test
    fun `ended live shows the reason`() {
        renderLive(VoiceState(phase = VoicePhase.Idle, live = LiveInfo("marin", 84, ended = "connection_lost")))

        compose.onNodeWithText("Live voice dropped").assertExists()
    }
}
