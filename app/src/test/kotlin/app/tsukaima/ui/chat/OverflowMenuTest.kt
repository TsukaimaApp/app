package app.tsukaima.ui.chat

import app.tsukaima.domain.chat.AgentStatus
import app.tsukaima.domain.chat.ChatItem
import app.tsukaima.domain.chat.ChatState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OverflowMenuTest {
    private val actions = ChatActions()

    private fun labels(chat: ChatState, gateway: AgentStatus? = null, archived: Boolean = false, compacting: Boolean = false) =
        overflowItems(ChatUiState(chat = chat, gatewayStatus = gateway, archived = archived, compacting = compacting), actions).map { it.label }

    private val turn = listOf(ChatItem.UserMessage("u1", "hi"), ChatItem.AssistantMessage("a1", "hello", streaming = false, reasoning = null))

    @Test
    fun brandNewChatOffersOnlyWhatWorks() {
        // Nothing yet: no entries, so the button is disabled instead of opening an empty sheet.
        assertTrue(labels(ChatState()).isEmpty())
        // A live session but no stored id: reasoning, and the live id can be copied.
        assertEquals(listOf("Reasoning", "Copy session ID"), labels(ChatState(liveSessionId = "d4fb0dea")))
        // Running: Stop first.
        assertEquals(
            listOf("Stop", "Reasoning", "Copy session ID"),
            labels(ChatState(liveSessionId = "d4fb0dea", status = AgentStatus.Thinking("x"))),
        )
    }

    @Test
    fun storedSessionHasTheFullMenuWithStopWhileRunning() {
        val stored = ChatState(liveSessionId = "d4fb0dea", storedSessionId = "20261020_010916_48c967")
        assertEquals(listOf("Reasoning", "Rename", "Archive", "Copy session ID", "Delete session"), labels(stored))
        assertEquals(
            listOf("Stop", "Reasoning", "Rename", "Archive", "Copy session ID", "Delete session"),
            labels(stored.copy(status = AgentStatus.Replying)),
        )
        // Waiting for the user: no Stop. Offline: neither Stop nor the live-session entries.
        assertEquals("Reasoning", labels(stored.copy(status = AgentStatus.Approval)).first())
        assertEquals("Rename", labels(stored.copy(status = AgentStatus.Tool("x")), gateway = AgentStatus.Offline).first())
    }

    @Test
    fun compactBetweenTurnsAndUnarchiveForAnArchivedChat() {
        val stored = ChatState(liveSessionId = "d4fb0dea", storedSessionId = "20261020_010916_48c967", items = turn)
        assertEquals(
            listOf("Reasoning", "Compact context", "Rename", "Archive", "Copy session ID", "Delete session"),
            labels(stored),
        )
        assertTrue("not mid-turn", "Compact context" !in labels(stored.copy(status = AgentStatus.Replying)))
        assertTrue("not twice", "Compact context" !in labels(stored, compacting = true))
        // Archived: read-only, so no live-session entries, and Unarchive instead of Archive.
        assertEquals(listOf("Rename", "Unarchive", "Copy session ID", "Delete session"), labels(stored, archived = true))
        assertEquals("Reasoning · Low", labels(stored.copy(reasoningEffort = "low")).first())
    }

    @Test
    fun sideEntriesNeedTextAndAWritableLiveSession() {
        val chat = ChatState(liveSessionId = "d4fb0dea")
        fun labels(input: String, chat: ChatState, archived: Boolean = false, compacting: Boolean = false) =
            overflowItems(ChatUiState(chat = chat, input = input, archived = archived, compacting = compacting), actions).map { it.label }
        val sides = listOf("Send as side question", "Send as background task")

        assertEquals(sides + listOf("Reasoning", "Copy session ID"), labels("what time is it?", chat))
        // Mid-turn too: side agents run detached from the turn.
        assertEquals(listOf("Stop") + sides, labels("x", chat.copy(status = AgentStatus.Replying)).take(3))
        assertTrue(labels(" ", chat).none { it in sides })
        assertTrue(labels("x", ChatState()).none { it in sides })
        assertTrue(labels("x", chat, archived = true).none { it in sides })
        assertTrue(labels("x", chat, compacting = true).none { it in sides })
        assertTrue(labels("x", chat.copy(status = AgentStatus.Offline)).none { it in sides })
    }
}
