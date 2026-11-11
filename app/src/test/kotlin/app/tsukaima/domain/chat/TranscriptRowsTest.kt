package app.tsukaima.domain.chat

import app.tsukaima.data.gateway.Frame
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

class TranscriptRowsTest {
    private val zone = ZoneId.of("Europe/Warsaw")

    private fun at(day: Int, hour: Int, minute: Int = 0) = ZonedDateTime.of(2026, 11, day, hour, minute, 0, 0, zone).toInstant().toEpochMilli()
    private fun user(id: String, at: Long? = null, steer: Boolean = false, queued: Boolean = false) =
        ChatItem.UserMessage(id, "q", at = at, steer = steer, queued = queued)
    private fun reply(id: String, text: String = "answer", streaming: Boolean = false) =
        ChatItem.AssistantMessage(id, text, streaming = streaming, reasoning = if (text.isEmpty()) "thinking" else null)
    private fun tool(id: String, status: ToolStatus = ToolStatus.Done) =
        ChatItem.ToolCall(id, "terminal", "ls", null, status, null, null, null)

    private fun keys(items: List<ChatItem>, open: Set<String> = emptySet()) = TranscriptRows.build(items, open, zone).map { it.key }

    @Test
    fun anEarlierTurnFoldsItsStepsAndTheCurrentTurnDoesNot() {
        val items = listOf(
            user("u1"), reply("r1", ""), tool("t1"), reply("r2", ""), tool("t2", ToolStatus.Failed), reply("a1"),
            user("u2"), tool("t3"), tool("t4"), tool("t5"), reply("a2"),
        )
        val rows = TranscriptRows.build(items, emptySet(), zone)
        assertEquals(listOf("u1", "steps-r1", "a1", "u2", "t3", "t4", "t5", "a2"), rows.map { it.key })
        assertEquals(TranscriptRow.Steps("steps-r1", count = 4, tools = 2, failed = 1, open = false), rows[1])
    }

    @Test
    fun anOpenedGroupKeepsItsLineAndShowsItsSteps() {
        val items = listOf(user("u1"), tool("t1"), tool("t2"), tool("t3"), reply("a1"), user("u2"))
        assertEquals(listOf("u1", "steps-t1", "t1", "t2", "t3", "a1", "u2"), keys(items, setOf("steps-t1")))
    }

    @Test
    fun aShortRunStaysAsItIs() {
        val items = listOf(user("u1"), reply("r1", ""), tool("t1"), reply("a1"), user("u2"))
        assertEquals(listOf("u1", "r1", "t1", "a1", "u2"), keys(items))
    }

    @Test
    fun aQueuedOrSteerMessageDoesNotEndTheCurrentTurn() {
        // The live turn sits above a queued echo and around a steer: neither makes it an earlier turn.
        val items = listOf(user("u1"), tool("t1"), tool("t2"), user("s1", steer = true), tool("t3"), tool("t4"), tool("t5"), user("q1", queued = true))
        assertEquals(items.map { it.id }, keys(items))
    }

    @Test
    fun aRunWithSomethingStillRunningIsNotFolded() {
        val items = listOf(user("u1"), tool("t1"), tool("t2"), tool("t3", ToolStatus.Running), user("u2"))
        assertEquals(items.map { it.id }, keys(items))
    }

    @Test
    fun aTimeMarkOpensADayAndFollowsALongGap() {
        val items = listOf(
            user("u1", at(10, 21, 14)), reply("a1"),
            user("u2", at(10, 21, 30)), reply("a2"), // same hour: no mark
            user("u3", at(10, 23, 0)), // 90 minutes later
            user("u4", at(11, 0, 5)), // next day, an hour later
            user("u5"), // not stamped
        )
        assertEquals(listOf("time-u1", "u1", "a1", "u2", "a2", "time-u3", "u3", "time-u4", "u4", "u5"), keys(items))
    }

    @Test
    fun aNewDayIsMarkedEvenMinutesAfterTheLastMessage() {
        val items = listOf(user("u1", at(10, 23, 50)), user("u2", at(11, 0, 10)))
        assertEquals(listOf("time-u1", "u1", "time-u2", "u2"), keys(items))
    }

    @Test
    fun labelsReadRelativeToToday() {
        val now = at(11, 12)
        fun label(t: Long, h24: Boolean = true) = TranscriptRows.label(t, now, zone, h24, Locale.UK)
        assertEquals("Today 09:02", label(at(11, 9, 2)))
        assertEquals("Yesterday 21:14", label(at(10, 21, 14)))
        assertEquals("Tue 3 Nov 18:05", label(at(3, 18, 5)))
        assertTrue(label(at(10, 21, 14), h24 = false).startsWith("Yesterday 9:14"))
        val lastYear = ZonedDateTime.of(2025, 11, 3, 18, 5, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals("3 Nov 2025 18:05", label(lastYear))
    }

    @Test
    fun durationsAndThoughtLabels() {
        assertEquals("0.4s", TranscriptRows.durationLabel(0.41))
        assertEquals("3.2s", TranscriptRows.durationLabel(3.2))
        assertEquals("42s", TranscriptRows.durationLabel(42.3))
        assertEquals("2m 5s", TranscriptRows.durationLabel(125.0))
        assertNull(TranscriptRows.durationLabel(null))
        assertEquals("Thought", TranscriptRows.thoughtLabel(null))
        assertEquals("Thought briefly", TranscriptRows.thoughtLabel(300))
        assertEquals("Thought for 12s", TranscriptRows.thoughtLabel(12_100))
        assertEquals("Thought for 2m 5s", TranscriptRows.thoughtLabel(125_000))
    }

    @Test
    fun theTailStartsAtAWordAndKeepsShortTextWhole() {
        assertEquals("short thought", TranscriptRows.tail("  short\nthought "))
        assertEquals("…cc dd", TranscriptRows.tail("aa bb cc dd", chars = 7))
    }

    @Test
    fun reasoningIsTimedFromItsFirstDeltaToItsLast() {
        fun delta(seq: Long) = Frame.Event("reasoning.delta", "live", seq, buildJsonObject { put("text", "hm ") })
        var s = ChatState(liveSessionId = "live")
        s = ChatReducer.reduce(s, delta(1), now = 1_000)
        s = ChatReducer.reduce(s, delta(2), now = 13_000)
        val message = s.items.single() as ChatItem.AssistantMessage
        assertEquals(1_000L, message.thoughtFrom)
        assertEquals(12_000L, message.thoughtMs)
        // Folded by default: the block shows a preview, the full text on a tap.
        assertEquals(false, ChatReducer.reasoningOpen(s, message))
        assertEquals(true, ChatReducer.reasoningOpen(ChatReducer.toggleReasoning(s, message.id), message))
    }

    @Test
    fun withoutAClockNothingIsTimed() {
        val s = ChatReducer.reduce(ChatState(liveSessionId = "live"), Frame.Event("reasoning.delta", "live", 1, buildJsonObject { put("text", "hm") }))
        val message = s.items.single() as ChatItem.AssistantMessage
        assertNull(message.thoughtFrom)
        assertNull(message.thoughtMs)
    }

    @Test
    fun aHistoryUserRowCarriesItsTime() {
        val items = ChatReducer.fromHistory(listOf(app.tsukaima.data.gateway.TranscriptMessage(role = "user", text = "hi", timestamp = 1_762_800_000.5, rowId = 4)))
        assertEquals(1_762_800_000_500L, (items.single() as ChatItem.UserMessage).at)
    }

    @Test
    fun stepsToggleOpenAndShut() {
        val opened = ChatReducer.toggleSteps(ChatState(), "steps-t1")
        assertEquals(setOf("steps-t1"), opened.openSteps)
        assertEquals(emptySet<String>(), ChatReducer.toggleSteps(opened, "steps-t1").openSteps)
    }
}
