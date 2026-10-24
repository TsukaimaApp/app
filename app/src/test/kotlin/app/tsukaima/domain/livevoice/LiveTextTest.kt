package app.tsukaima.domain.livevoice

import app.tsukaima.domain.chat.ChatItem
import app.tsukaima.domain.chat.ToolStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class LiveTextTest {
    @Test fun chunkShortTextIsOneChunk() =
        assertEquals(
            listOf("Hello there. How are you?"),
            LiveText.chunkForCommentary("  Hello  there.\n How are you? "),
        )

    @Test fun chunkSplitsOnSentenceBoundariesUnderLimit() {
        val chunks = LiveText.chunkForCommentary("One two. Three four. Five six.", limit = 20)
        assertEquals(listOf("One two. Three four.", "Five six."), chunks)
    }

    @Test fun chunkHardSplitsAnOverlongSentence() {
        val chunks = LiveText.chunkForCommentary("Hi. " + "a".repeat(25) + ". Bye.", limit = 10)
        assertEquals(listOf("Hi.", "a".repeat(10), "a".repeat(10), "a".repeat(5) + ".", "Bye."), chunks)
    }

    @Test fun chunkBlankIsEmpty() = assertEquals(emptyList<String>(), LiveText.chunkForCommentary(" \n\t "))

    private fun f(s: LiveSpeaker, t: String) = LiveFragment(s, t, 0, 0)

    @Test fun delegationPromptMergesTurnsAndTakesLastUserTurn() {
        val p = LiveText.delegationPrompt(
            listOf(
                f(LiveSpeaker.User, "what"),
                f(LiveSpeaker.User, " time"),
                f(LiveSpeaker.Assistant, "let me check"),
                f(LiveSpeaker.User, "in Tokyo"),
            ),
        )
        assertEquals("in Tokyo", p.prompt)
        assertEquals("User: what time\nVoice assistant: let me check\nUser: in Tokyo", p.context)
    }

    @Test fun delegationPromptWithoutUserTurnFallsBackToContextTail() {
        val text = "x".repeat(500)
        val p = LiveText.delegationPrompt(listOf(f(LiveSpeaker.Assistant, text)))
        val context = "Voice assistant: $text"
        assertEquals(context, p.context)
        assertEquals(context.takeLast(400), p.prompt)
        assertEquals(DelegationPrompt("", ""), LiveText.delegationPrompt(emptyList()))
    }

    private fun u(i: Int, t: String) = ChatItem.UserMessage(id = "u$i", text = t)
    private fun a(i: Int, t: String) =
        ChatItem.AssistantMessage(id = "a$i", text = t, streaming = false, reasoning = null)

    @Test fun liveHistoryKeepsNewest24Within6000AndMapsRoles() {
        val items = buildList {
            for (i in 1..20) {
                add(u(i, "question $i"))
                add(ChatItem.ToolCall("t$i", "ls", null, null, ToolStatus.Done, null, null, null))
                add(a(i, "answer   $i\n"))
                add(a(100 + i, "  "))
            }
        }
        val h = LiveText.liveHistory(items)
        assertEquals(24, h.size)
        assertEquals("answer 20", h.last().content.single().text)
        assertEquals("output_text", h.last().content.single().type)
        assertEquals("assistant", h.last().role)
        assertEquals("message", h.last().type)
        assertEquals("question 20", h[h.size - 2].content.single().text)
        assertEquals("input_text", h[h.size - 2].content.single().type)
        assertEquals("user", h[h.size - 2].role)
        assertEquals("question 9", h.first().content.single().text)

        val big = listOf(
            u(1, "a".repeat(2_000)),
            a(1, "b".repeat(2_000)),
            u(2, "c".repeat(2_000)),
            a(2, "d".repeat(2_000)),
        )
        val bh = LiveText.liveHistory(big)
        assertEquals(listOf(1_200, 1_200, 1_200, 1_200), bh.map { it.content.single().text.length }.takeLast(4))
    }

    @Test fun liveHistoryStopsAtCharBudget() {
        val items = (1..10).map { u(it, "x".repeat(1_000) + it) }
        assertEquals(5, LiveText.liveHistory(items, maxChars = 5_100).size)
    }
}
