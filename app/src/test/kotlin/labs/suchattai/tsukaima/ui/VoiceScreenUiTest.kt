package labs.suchattai.tsukaima.ui

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import labs.suchattai.tsukaima.domain.chat.AgentStatus
import labs.suchattai.tsukaima.domain.chat.ChatState
import labs.suchattai.tsukaima.domain.voice.VoicePhase
import labs.suchattai.tsukaima.domain.voice.VoiceState
import labs.suchattai.tsukaima.ui.chat.ChatActions
import labs.suchattai.tsukaima.ui.chat.ChatUiState
import labs.suchattai.tsukaima.ui.voice.VoiceScreen
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
}
