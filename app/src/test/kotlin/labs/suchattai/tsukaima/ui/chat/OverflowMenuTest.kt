package labs.suchattai.tsukaima.ui.chat

import labs.suchattai.tsukaima.domain.chat.AgentStatus
import labs.suchattai.tsukaima.domain.chat.ChatState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OverflowMenuTest {
    private val actions = ChatActions()

    private fun labels(chat: ChatState, gateway: AgentStatus? = null) =
        overflowItems(ChatUiState(chat = chat, gatewayStatus = gateway), actions).map { it.label }

    @Test
    fun brandNewChatOffersOnlyWhatWorks() {
        // Nothing yet: no entries, so the button is disabled instead of opening an empty sheet.
        assertTrue(labels(ChatState()).isEmpty())
        // A live session but no stored id: the live id can be copied.
        assertEquals(listOf("Copy session ID"), labels(ChatState(liveSessionId = "d4fb0dea")))
        // Running: Stop first.
        assertEquals(
            listOf("Stop", "Copy session ID"),
            labels(ChatState(liveSessionId = "d4fb0dea", status = AgentStatus.Thinking("x"))),
        )
    }

    @Test
    fun storedSessionHasTheFullMenuWithStopWhileRunning() {
        val stored = ChatState(liveSessionId = "d4fb0dea", storedSessionId = "20261020_010916_48c967")
        assertEquals(listOf("Rename", "Archive", "Copy session ID", "Delete session"), labels(stored))
        assertEquals(
            listOf("Stop", "Rename", "Archive", "Copy session ID", "Delete session"),
            labels(stored.copy(status = AgentStatus.Replying)),
        )
        // Waiting for the user, or offline: no Stop.
        assertEquals("Rename", labels(stored.copy(status = AgentStatus.Approval)).first())
        assertEquals("Rename", labels(stored.copy(status = AgentStatus.Tool("x")), gateway = AgentStatus.Offline).first())
    }
}
