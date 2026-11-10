package app.tsukaima.ui.chat

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import app.tsukaima.data.api.HermesJson
import app.tsukaima.data.gateway.SessionBranched
import app.tsukaima.data.gateway.TranscriptMessage
import app.tsukaima.domain.chat.ChatItem
import app.tsukaima.domain.chat.ChatReducer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `session.branch {count}` as the test Hermes (v0.23.1, 2026-11-10) answered it: `recorded-branch.json` holds the
 * parent's `session.history` (three turns, the first with a tool call), the results for `count` 2 and 3, and the
 * history of the first child after one more turn in it.
 */
class ChatBranchTest {
    private val recorded: JsonObject =
        HermesJson.parseToJsonElement(checkNotNull(javaClass.getResource("/chat/recorded-branch.json")).readText()).jsonObject
    private fun rows(key: String) = HermesJson.decodeFromJsonElement(ListSerializer(TranscriptMessage.serializer()), recorded[key]!!)
    private fun branched(key: String) = HermesJson.decodeFromJsonElement(SessionBranched.serializer(), recorded[key]!!)
    private val history = rows("history")
    private val items = ChatReducer.fromHistory(history)

    @Test
    fun aBranchFromAReplyEndsWithThatReply() {
        // user 334 · (reasoning-only 335) · tool · reply 337 "Output: alpha." · user 338 · reply 339 · …
        val cut = ChatBranch.count(history, 337)!!
        assertEquals(2, cut.count)
        assertNull(cut.draft)
        val child = branched("branch2")
        assertEquals(cut.count, child.messageCount)
        assertEquals("the server kept exactly up to that reply", cut.last, child.messages.last().text)
    }

    @Test
    fun aBranchFromTheUsersMessageEndsBeforeItAndKeepsItAsADraft() {
        val cut = ChatBranch.count(history, 338)!!
        assertEquals(2, cut.count)
        assertEquals("Reply with exactly: beta", cut.draft)
        assertEquals("Output: alpha.", cut.last)
        // count 3 would have kept the message itself as the child's last row, with no reply to it.
        assertEquals("user", branched("branch3").messages.last().role)
    }

    @Test
    fun toolRowsAndReasoningOnlyRowsAreNotCounted() {
        assertEquals(listOf("user", "assistant", "tool", "assistant"), history.take(4).map { it.role })
        assertEquals("the child holds the text of the turn, not its tool call", listOf("user", "assistant"), branched("branch2").messages.map { it.role })
        assertNull("a reasoning-only row has no text to branch from", ChatBranch.count(history, 335))
    }

    @Test
    fun nothingToKeepOrAnUnknownRowIsNoBranch() {
        assertNull("the first message: the branch would be empty", ChatBranch.count(history, 334))
        assertNull(ChatBranch.count(history, 99_999))
    }

    @Test
    fun theMenuOffersItOnPersistedMessagesOnly() {
        val first = items.first()
        val reply = items.first { it is ChatItem.AssistantMessage && (it as ChatItem.AssistantMessage).text == "Output: alpha." }
        val second = items.first { it is ChatItem.UserMessage && (it as ChatItem.UserMessage).text == "Reply with exactly: beta" }
        assertFalse(ChatBranch.offered(items, first))
        assertTrue(ChatBranch.offered(items, reply))
        assertTrue(ChatBranch.offered(items, second))
        assertFalse(ChatBranch.offered(items, items.first { it is ChatItem.ToolCall }))
        assertFalse(ChatBranch.offered(items, ChatItem.UserMessage("u-l1", "not stored yet")))
        assertFalse(ChatBranch.offered(items, (second as ChatItem.UserMessage).copy(steer = true)))
        assertFalse(ChatBranch.offered(items, ChatItem.AssistantMessage("a", "still coming", streaming = true, reasoning = null, rowId = 5)))
    }

    @Test
    fun theChildGoesOnByItselfAndTheParentIsUntouched() {
        val child = rows("child_history")
        assertEquals(listOf("Run `echo alpha` with the terminal tool and tell me the output in three words.", "Output: alpha."), child.take(2).map { it.text })
        assertTrue(child.size > 2)
        assertEquals(8, history.size)
    }
}
