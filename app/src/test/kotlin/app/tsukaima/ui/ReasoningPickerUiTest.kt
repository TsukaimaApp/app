package app.tsukaima.ui

import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import app.tsukaima.domain.chat.AgentStatus
import app.tsukaima.domain.chat.ChatItem
import app.tsukaima.domain.chat.ChatState
import app.tsukaima.ui.chat.ChatActions
import app.tsukaima.ui.chat.ChatContent
import app.tsukaima.ui.chat.ChatUiState
import org.junit.Assert.assertEquals
import org.junit.Test

class ReasoningPickerUiTest : ComposeUiTest() {
    private fun chat(status: AgentStatus = AgentStatus.Idle) = ChatState(
        items = listOf(ChatItem.UserMessage("u1", "hello", rowId = 1), ChatItem.AssistantMessage("a1", "hi", streaming = false, reasoning = null)),
        status = status,
        liveSessionId = "d4fb0dea",
        reasoningEffort = "high",
    )

    @Test
    fun `on the user's turn the status line carries the level, and a tap on it opens the levels and sets one`() {
        val picked = mutableListOf<String>()
        setThemed {
            ChatContent(
                state = ChatUiState(chat = chat(), isNew = false),
                showMenu = false, onDrawer = {}, onStatus = {},
                actions = ChatActions(onReasoning = { picked += it }),
            )
        }
        compose.onNodeWithText("Hermes awaits,").assertExists()
        compose.onNodeWithText("Ultra").assertDoesNotExist()
        compose.onNodeWithContentDescription("Reasoning level: High").assertHeightIsAtLeast(44.dp).performClick()
        compose.onNodeWithText("going beyond its limits").assertExists()
        compose.onNodeWithText("Ultra").performClick()
        compose.waitForIdle()
        assertEquals(listOf("ultra"), picked)
        compose.onNodeWithText("Ultra").assertDoesNotExist()
    }

    @Test
    fun `during the agent's turn the line is the plain status`() {
        setThemed {
            ChatContent(
                state = ChatUiState(chat = chat(AgentStatus.Replying), isNew = false),
                showMenu = false, onDrawer = {}, onStatus = {}, actions = ChatActions(),
            )
        }
        compose.onNodeWithText("Replying…").assertExists()
        compose.onNodeWithText("Hermes awaits,").assertDoesNotExist()
        compose.onNodeWithContentDescription("Reasoning level: High").assertDoesNotExist()
    }
}
