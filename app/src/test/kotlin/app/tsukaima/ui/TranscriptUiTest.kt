package app.tsukaima.ui

import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import app.tsukaima.domain.chat.ChatItem
import app.tsukaima.domain.chat.ChatState
import app.tsukaima.domain.chat.ToolStatus
import app.tsukaima.ui.chat.ChatActions
import app.tsukaima.ui.chat.ChatContent
import app.tsukaima.ui.chat.ChatUiState
import org.junit.Assert.assertEquals
import org.junit.Test

/** The 0.13 transcript: reasoning as a margin note, earlier turns' work folded to one line, tool durations. */
class TranscriptUiTest : ComposeUiTest() {
    private fun tool(id: String, status: ToolStatus = ToolStatus.Done, durationS: Double? = null) =
        ChatItem.ToolCall(id, "terminal", "journalctl -u $id", null, status, "out", 0, durationS)

    private val items = listOf(
        ChatItem.UserMessage("u1", "Is the backup healthy?", rowId = 1),
        tool("t1"), tool("t2", ToolStatus.Failed), tool("t3"),
        ChatItem.AssistantMessage("a1", "It ran last night.", streaming = false, reasoning = null),
        ChatItem.UserMessage("u2", "Why does it fail on Sundays?", rowId = 2),
        tool("t4", durationS = 3.2),
        ChatItem.AssistantMessage("a2", "The snapshot holds the lock.", streaming = false, reasoning = "Compare the start times.", thoughtMs = 12_000),
    )

    @Test
    fun `an earlier turn's steps are one line that unfolds, and the current turn stays open`() {
        val chat = ChatState(items = items, liveSessionId = "live")
        val toggled = mutableListOf<String>()
        setThemed {
            ChatContent(
                state = ChatUiState(chat = chat, isNew = false),
                showMenu = false, onDrawer = {}, onStatus = {},
                actions = ChatActions(onToggleSteps = { toggled += it }),
            )
        }
        compose.onNodeWithContentDescription("3 steps · 1 failed").assertHeightIsAtLeast(44.dp).performClick()
        assertEquals(listOf("steps-t1"), toggled)
        compose.onNodeWithText("journalctl -u t1", substring = true).assertDoesNotExist()
        // The current turn: its tool row is there, with how long it ran.
        compose.onNodeWithText("journalctl -u t4", substring = true).assertExists()
        compose.onNodeWithText("3.2s").assertExists()
    }

    @Test
    fun `an unfolded group shows its steps under the line`() {
        setThemed {
            ChatContent(
                // A short transcript: every row fits the test window.
                state = ChatUiState(chat = ChatState(items = items.take(6), liveSessionId = "live", openSteps = setOf("steps-t1")), isNew = false),
                showMenu = false, onDrawer = {}, onStatus = {}, actions = ChatActions(),
            )
        }
        compose.onNodeWithContentDescription("3 steps · 1 failed").assertExists()
        compose.onNodeWithText("journalctl -u t1", substring = true).assertExists()
    }

    @Test
    fun `reasoning shows its header and a preview, and a tap asks for all of it`() {
        val toggled = mutableListOf<String>()
        setThemed {
            ChatContent(
                state = ChatUiState(chat = ChatState(items = items, liveSessionId = "live"), isNew = false),
                showMenu = false, onDrawer = {}, onStatus = {},
                actions = ChatActions(onToggleReasoning = { toggled += it }),
            )
        }
        compose.onNodeWithText("Thought for 12s").assertExists()
        compose.onNodeWithText("Compare the start times.").assertExists().performClick()
        assertEquals(listOf("a2"), toggled)
    }

    @Test
    fun `reasoning that is streaming says Thinking`() {
        val live = items.dropLast(1) + ChatItem.AssistantMessage("a2", "", streaming = true, reasoning = "Compare the start times.", reasoningLive = true)
        setThemed {
            ChatContent(
                state = ChatUiState(chat = ChatState(items = live, liveSessionId = "live"), isNew = false),
                showMenu = false, onDrawer = {}, onStatus = {}, actions = ChatActions(),
            )
        }
        compose.onNodeWithText("Thinking…").assertExists()
        compose.onNodeWithText("Compare the start times.").assertExists()
    }
}

/** The 0.14 chat without Hermes, and the background-process chip. */
class AwayUiTest : ComposeUiTest() {
    private val items = listOf(
        ChatItem.UserMessage("u1", "Why does it fail on Sundays?", rowId = 1),
        ChatItem.AssistantMessage("a1", "The snapshot holds the lock.", streaming = false, reasoning = null),
    )

    @Test
    fun `offline the bar says so, the composer still types and a waiting message can be cancelled`() {
        val cancelled = mutableListOf<String>()
        setThemed {
            ChatContent(
                state = ChatUiState(
                    chat = ChatState(items = items, status = app.tsukaima.domain.chat.AgentStatus.Offline),
                    isNew = false, savedAt = System.currentTimeMillis(), offlineSendMinutes = 10,
                    outbox = listOf(app.tsukaima.domain.chat.OutboxMessage("o1", "Move it to 04:30 then.", 1)),
                ),
                showMenu = false, onDrawer = {}, onStatus = {},
                actions = ChatActions(onCancelOffline = { cancelled += it }),
            )
        }
        compose.onNodeWithContentDescription("Offline, saved copy from", substring = true).assertHeightIsAtLeast(44.dp)
        compose.onNodeWithText("Move it to 04:30 then.").assertExists()
        compose.onNodeWithText("Sends when you are back online").assertExists()
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(listOf("o1"), cancelled)
        compose.onNodeWithText("Hermes is unreachable").assertDoesNotExist()
    }

    @Test
    fun `running background commands show as a chip that opens their list`() {
        var opened = 0
        setThemed {
            ChatContent(
                state = ChatUiState(
                    chat = ChatState(items = items, liveSessionId = "live"), isNew = false,
                    processes = listOf(
                        app.tsukaima.ui.chat.ProcessRow("p1", "npm run build", running = true),
                        app.tsukaima.ui.chat.ProcessRow("p2", "sleep 300", running = false, exitCode = -15),
                    ),
                ),
                showMenu = false, onDrawer = {}, onStatus = {},
                actions = ChatActions(onProcesses = { opened++ }),
            )
        }
        compose.onNodeWithContentDescription("1 running in the background").assertHeightIsAtLeast(44.dp).performClick()
        assertEquals(1, opened)
    }
}
