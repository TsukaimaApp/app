package app.tsukaima.domain.chat

import kotlinx.serialization.json.jsonObject
import app.tsukaima.data.api.HermesJson
import app.tsukaima.data.gateway.Frame
import app.tsukaima.ui.chat.components.ToolClock
import app.tsukaima.ui.chat.components.elapsedText
import app.tsukaima.ui.chat.components.outputText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `tool.progress` (Hermes 0.23): the payload shape is the 0.23.1 contract's (`ToolProgressPayload`); no frame was
 * recorded, because the test server has no MCP tool that reports progress.
 */
class ToolProgressTest {
    private fun ev(type: String, payload: String, seq: Long) =
        Frame.Event(type, "live", seq, HermesJson.parseToJsonElement(payload).jsonObject)

    private fun fold(vararg events: Frame.Event) = events.fold(ChatState(liveSessionId = "live")) { s, f -> ChatReducer.reduce(s, f) { "…" } }

    private val start = ev("tool.start", """{"tool_id":"t1","name":"mcp_crawl","args":{}}""", 1)
    private fun tool(s: ChatState) = s.items.single() as ChatItem.ToolCall

    @Test
    fun progressShowsOnTheRunningRow() {
        val s = fold(start, ev("tool.progress", """{"tool_id":"t1","name":"mcp_crawl","progress":2,"total":5,"message":"Fetching page 2"}""", 2))
        assertEquals(ToolProgress(40, "Fetching page 2"), tool(s).progress)
        assertEquals("40% · Fetching page 2", tool(s).progress?.label)
        assertEquals("Fetching page 2", outputText(tool(s)))
    }

    @Test
    fun theServersPercentWinsAndATotalIsNotNeeded() {
        assertEquals(ToolProgress(75, null), tool(fold(start, ev("tool.progress", """{"tool_id":"t1","name":"x","progress":3,"total":10,"percent":75}""", 2))).progress)
        val open = tool(fold(start, ev("tool.progress", """{"tool_id":"t1","name":"x","progress":7}""", 2)))
        assertEquals(ToolProgress(null, null), open.progress)
        assertNull(open.progress?.label)
        assertEquals("Running…", outputText(open))
    }

    @Test
    fun completionClearsTheProgressAndALateFrameChangesNothing() {
        val done = fold(
            start,
            ev("tool.progress", """{"tool_id":"t1","name":"x","progress":1,"total":2}""", 2),
            ev("tool.complete", """{"tool_id":"t1","name":"x","result":{"output":"ok"}}""", 3),
            ev("tool.progress", """{"tool_id":"t1","name":"x","progress":2,"total":2}""", 4),
        )
        assertNull(tool(done).progress)
        assertEquals("ok", outputText(tool(done)))
        assertEquals("no row for an unknown call", 1, fold(start, ev("tool.progress", """{"tool_id":"zz","name":"x","progress":1}""", 2)).items.size)
    }

    @Test
    fun elapsedTimeReadsAsAClock() {
        assertEquals("0:07", elapsedText(7))
        assertEquals("2:05", elapsedText(125))
        assertEquals("1:02:03", elapsedText(3723))
        assertEquals("0:00", elapsedText(-4))
    }

    @Test
    fun aRowKeepsItsStartWhenItIsShownAgain() {
        assertEquals(1_000L, ToolClock.startOf("clock-test-row", 1_000L))
        assertEquals(1_000L, ToolClock.startOf("clock-test-row", 9_000L))
    }
}
