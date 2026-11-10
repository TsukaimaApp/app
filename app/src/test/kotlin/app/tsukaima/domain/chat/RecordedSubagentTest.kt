package app.tsukaima.domain.chat

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import app.tsukaima.data.api.HermesJson
import app.tsukaima.data.gateway.Frame
import app.tsukaima.data.gateway.JsonRpc
import app.tsukaima.data.gateway.SubagentTail
import app.tsukaima.ui.chat.ChatSubagents
import app.tsukaima.ui.chat.SubagentWatch
import app.tsukaima.ui.chat.components.subagentBody
import app.tsukaima.ui.chat.components.subagentState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A background delegation that was messaged and then stopped from the client, recorded from the test Hermes (v0.23.1,
 * 2026-11-10): `recorded-subagent.jsonl` holds the events, `recorded-subagent-calls.json` the answers to
 * `subagent.tail`, `subagent.steer` and `subagent.interrupt` while the child ran and after it ended.
 */
class RecordedSubagentTest {
    private fun resource(name: String) = checkNotNull(javaClass.getResource("/chat/$name")) { name }.readText()

    private val frames = JsonRpc.decodeFrames(resource("recorded-subagent.jsonl")).filterIsInstance<Frame.Event>()
    private val calls: JsonObject = HermesJson.parseToJsonElement(resource("recorded-subagent-calls.json")).jsonObject
    private val end = frames.fold(ChatState(liveSessionId = frames.first().sessionId)) { s, f -> ChatReducer.reduce(s, f) { "…" } }
    private val row = end.items.filterIsInstance<ChatItem.Subagent>().single()

    @Test
    fun theRowKnowsTheChildItCanActOn() {
        assertEquals("sa-0-69f0505f", row.subagentId)
        assertEquals("sub-sa-0-69f0505f", row.id)
        assertEquals("deepseek-v4.1-flash-1017", row.model)
        assertEquals(1, row.toolCount)
    }

    @Test
    fun aStoppedChildDidNotFinish() {
        assertEquals(SubagentStatus.Failed, row.status)
        assertEquals("Did not finish · deepseek-v4.1-flash-1017 · 1 tool", subagentState(row))
        assertTrue(row.summary!!.startsWith("This turn did not complete."))
    }

    @Test
    fun theChildOutlivesTheTurnThatDispatchedIt() {
        val complete = frames.indexOfFirst { it.type == "message.complete" }
        val after = frames.drop(complete + 1).map { it.type }
        assertTrue("its events keep coming after the turn ended", "subagent.tool" in after && "subagent.complete" in after)
        assertEquals(AgentStatus.Idle, end.status)
    }

    @Test
    fun theTailIsShownWithoutItsFileHeader() {
        val tail = HermesJson.decodeFromJsonElement(SubagentTail.serializer(), calls["tail"]!!)
        assertTrue(tail.available)
        val body = ChatSubagents.tailBody(tail.text)
        assertFalse("tail -f me" in body)
        assertTrue(body.lines().first().contains("user     | kickoff:"))
        assertTrue(body.lines().last().contains("tool     | -> terminal("))
        assertEquals(body, subagentBody(row, SubagentWatch(row.id, body, available = true)))
    }

    @Test
    fun aCutTailHasNoHeaderToRemove() =
        assertEquals("14:10:09 tool     | -> terminal(x)", ChatSubagents.tailBody("14:10:09 tool     | -> terminal(x)\n"))

    @Test
    fun afterTheEndTheServerHasNoTailAndRefusesBothActions() {
        assertFalse(HermesJson.decodeFromJsonElement(SubagentTail.serializer(), calls["tail_after_end"]!!).available)
        assertEquals("queued", calls["steer"]!!.jsonObject["status"]!!.jsonPrimitive.content)
        assertEquals("rejected", calls["steer_after_end"]!!.jsonObject["status"]!!.jsonPrimitive.content)
        assertTrue(calls["interrupt"]!!.jsonObject["found"]!!.jsonPrimitive.boolean)
        assertFalse(calls["interrupt_after_end"]!!.jsonObject["found"]!!.jsonPrimitive.boolean)
        assertTrue(subagentBody(row.copy(detail = null), SubagentWatch(row.id, "", available = false)).startsWith("The live transcript is gone"))
        assertEquals("Loading…", subagentBody(row, SubagentWatch(row.id)))
    }
}
