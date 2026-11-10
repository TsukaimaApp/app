package app.tsukaima.domain.chat

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import app.tsukaima.data.api.HermesJson
import app.tsukaima.data.gateway.Frame
import app.tsukaima.data.gateway.JsonRpc
import app.tsukaima.data.gateway.TranscriptMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A real turn recorded from the test Hermes (0.22.2, a model that streams no reasoning) on 2026-11-10: three tool
 * calls (`terminal`, `read_file`, `terminal`) and a reply, with the `session.history` of the same session mid-turn and
 * after it, and the REST `/api/sessions/{id}/messages` rows. Fixtures: `src/test/resources/chat/recorded-tool-turn*`.
 *
 * The rule under test: once a row is on screen, neither its id nor its place among the other rows changes — while
 * the turn streams, and when the transcript is rebuilt from the stored history.
 */
class RecordedTurnTest {
    private fun resource(name: String) = checkNotNull(javaClass.getResource("/chat/$name")) { name }.readText()

    private val frames: List<Frame> = JsonRpc.decodeFrames(resource("recorded-tool-turn.jsonl"))
    private val snapshot: JsonObject = HermesJson.parseToJsonElement(resource("recorded-tool-turn-history.json")).jsonObject
    private val live = snapshot["live"]!!.jsonPrimitive.content
    private val userRow = snapshot["user_row_id"]!!.jsonPrimitive.long

    private fun messages(key: String): List<TranscriptMessage> =
        HermesJson.decodeFromJsonElement(ListSerializer(TranscriptMessage.serializer()), snapshot[key]!!)

    private fun start(): ChatState {
        val sent = ChatReducer.onSubmitted(ChatState(liveSessionId = live), "do the three things", "local-1", flair = { "…" })
        return ChatReducer.onSubmitAccepted(sent, "local-1", userRow)
    }

    private fun states(): List<ChatState> = frames.runningFold(start()) { s, f -> ChatReducer.reduce(s, f) { "…" } }

    private fun ids(s: ChatState) = s.items.map { it.id }

    @Test
    fun fixtureIsTheRecordedTurn() {
        assertEquals(39, frames.size)
        assertTrue(frames.all { it is Frame.Event })
        assertEquals(3, frames.count { (it as Frame.Event).type == "tool.complete" })
    }

    /** Between two frames a row may be added at the end, or the last row may go; nothing above ever moves or goes. */
    @Test
    fun rowsNeverMoveWhileTheTurnStreams() {
        states().zipWithNext().forEachIndexed { step, (before, after) ->
            val a = ids(before)
            val b = ids(after)
            val kept = a.dropLast(1)
            assertEquals("frame $step (${(frames[step] as Frame.Event).type}) moved or dropped a row above the last one", kept, b.take(kept.size))
            if (a.isNotEmpty() && a.last() in b) assertEquals("frame $step moved the last row", a.size - 1, b.indexOf(a.last()))
        }
    }

    @Test
    fun theTurnEndsAsUserThreeToolsAndTheReply() {
        val items = states().last().items
        assertEquals(
            listOf("UserMessage", "ToolCall", "ToolCall", "ToolCall", "AssistantMessage"),
            items.map { it::class.simpleName },
        )
        assertEquals(AgentStatus.Idle, states().last().status)
    }

    /** `read_file` answers `{content, …}` with no `output` key: the row shows the content, not "No output". */
    @Test
    fun everyToolOfTheTurnHasItsOutput() {
        val tools = states().last().items.filterIsInstance<ChatItem.ToolCall>()
        assertEquals(listOf("hello-from-tsukaima", "1|claude", "Linux"), tools.map { it.output })
        assertTrue(tools.none { it.outputPending })
    }

    /** A rebuild from the stored history keeps the id of every row that was on screen, in the same order. */
    @Test
    fun aRebuildFromHistoryKeepsEveryRowsId() {
        val before = states().last().items
        val rebuilt = ChatReducer.rebuild(ChatReducer.fromHistory(messages("history")), before)
        assertEquals(ids(states().last()), rebuilt.map { it.id })
        assertEquals(rebuilt.map { it.id }.distinct(), rebuilt.map { it.id })
    }

    /** History rows carry no tool output (Hermes omits it): the rebuilt rows wait for it instead of reading "No output". */
    @Test
    fun historyToolRowsWaitForTheirOutput() {
        val tools = ChatReducer.fromHistory(messages("history")).filterIsInstance<ChatItem.ToolCall>()
        assertEquals(3, tools.size)
        assertTrue(tools.all { it.outputPending && it.output == null })
    }

    /** The REST message rows fill the outputs by `tool_call_id`; a row the store does not know reads "No output". */
    @Test
    fun storedMessagesFillHistoryToolOutputs() {
        val history = ChatReducer.fromHistory(messages("history")) + ChatItem.ToolCall(
            id = "call_unknown", name = "terminal", context = null, args = null, status = ToolStatus.Done,
            output = null, exitCode = null, durationS = null, outputPending = true,
        )
        val stored: List<Pair<String, JsonElement?>> = snapshot["rest"]!!.jsonArray.map { it.jsonObject }
            .filter { it["role"]!!.jsonPrimitive.content == "tool" }
            .map { it["tool_call_id"]!!.jsonPrimitive.content to it["content"] }
        val filled = ChatReducer.onToolOutputs(ChatState(items = history), stored, complete = true)
        val tools = filled.items.filterIsInstance<ChatItem.ToolCall>()
        assertEquals(listOf("hello-from-tsukaima", "1|claude", "Linux", null), tools.map { it.output })
        assertTrue(tools.none { it.outputPending })
        assertEquals(history.map { it.id }, filled.items.map { it.id })
    }

    /** A page that does not reach back to a row leaves it waiting (the next page may hold it). */
    @Test
    fun aPartialPageLeavesUnmatchedRowsWaiting() {
        val history = ChatReducer.fromHistory(messages("history"))
        val first = history.filterIsInstance<ChatItem.ToolCall>().first()
        val filled = ChatReducer.onToolOutputs(
            ChatState(items = history), listOf(first.id to HermesJson.parseToJsonElement("\"{\\\"output\\\": \\\"x\\\"}\"")), complete = false,
        )
        val tools = filled.items.filterIsInstance<ChatItem.ToolCall>()
        assertEquals(listOf(false, true, true), tools.map { it.outputPending })
        assertEquals("x", tools.first().output)
    }

    /** A reconnect in the middle of the turn rebuilds from a shorter history: the rows it holds keep their ids. */
    @Test
    fun aMidTurnRebuildKeepsTheIdsItHolds() {
        val afterFirstTool = states()[frames.indexOfFirst { (it as Frame.Event).type == "tool.complete" } + 1]
        val rebuilt = ChatReducer.rebuild(ChatReducer.fromHistory(messages("midturn")), afterFirstTool.items)
        assertEquals(ids(afterFirstTool), rebuilt.map { it.id })
        // The rest of the turn then lands below, and a later full rebuild still agrees.
        val rest = frames.drop(frames.indexOfFirst { (it as Frame.Event).type == "tool.complete" } + 1)
        val end = rest.fold(afterFirstTool.copy(items = rebuilt)) { s, f -> ChatReducer.reduce(s, f) { "…" } }
        assertEquals(ids(end), ChatReducer.rebuild(ChatReducer.fromHistory(messages("history")), end.items).map { it.id })
    }

    /** Server notices are not in the history: a rebuild keeps each one where it was, not at the end. */
    @Test
    fun aRebuildKeepsNoticesInPlace() {
        val end = states().last().items
        val firstTool = end.indexOfFirst { it is ChatItem.ToolCall }
        val withNotice = end.toMutableList().apply { add(firstTool + 1, ChatItem.Notice("n-9", "Model fallback: …", warning = true)) }
        val rebuilt = ChatReducer.rebuild(ChatReducer.fromHistory(messages("history")), withNotice)
        assertEquals(withNotice.map { it.id }, rebuilt.map { it.id })
    }

    /**
     * The recorded history rows before each tool call carry `reasoning_content` (blank here). Production has rows with
     * `reasoning_content` and no `reasoning` (417 on 2026-11-10): such a row is the thinking before the tool, and is kept.
     */
    @Test
    fun historyReadsReasoningContentWhenReasoningIsMissing() {
        val rows = messages("history").map { if (it.role == "assistant" && it.text.isEmpty()) it.copy(reasoningContent = JsonPrimitive("check the host")) else it }
        val items = ChatReducer.fromHistory(rows)
        assertEquals(
            listOf("UserMessage", "AssistantMessage", "ToolCall", "AssistantMessage", "ToolCall", "AssistantMessage", "ToolCall", "AssistantMessage"),
            items.map { it::class.simpleName },
        )
        assertEquals("check the host", (items[1] as ChatItem.AssistantMessage).reasoning)
        // `reasoning` wins when both are set; a blank or non-text value is no reasoning.
        val both = TranscriptMessage(role = "assistant", text = "x", reasoning = "a", reasoningContent = JsonPrimitive("b"), rowId = 1)
        assertEquals("a", (ChatReducer.fromHistory(listOf(both)).single() as ChatItem.AssistantMessage).reasoning)
        val odd = TranscriptMessage(role = "assistant", text = "x", reasoningContent = HermesJson.parseToJsonElement("[1]"), rowId = 1)
        assertNull((ChatReducer.fromHistory(listOf(odd)).single() as ChatItem.AssistantMessage).reasoning)
    }

    /**
     * The thinking streamed before a tool call has no row id on the live path. A rebuild still keeps its id: it is the
     * message directly above the same tool call in both transcripts.
     */
    @Test
    fun aRebuildKeepsTheIdOfTheReasoningAboveATool() {
        fun ev(type: String, payload: String, seq: Long) =
            Frame.Event(type, live, seq, HermesJson.parseToJsonElement(payload).jsonObject)
        val end = listOf(
            ev("message.start", "{}", 1),
            ev("reasoning.delta", """{"text":"look first"}""", 2),
            ev("tool.start", """{"tool_id":"call_a","name":"terminal","context":"ls"}""", 3),
            ev("tool.complete", """{"tool_id":"call_a","name":"terminal","result":{"output":"x","exit_code":0}}""", 4),
            ev("reasoning.delta", """{"text":"now answer"}""", 5),
            ev("message.delta", """{"text":"done"}""", 6),
            ev("message.complete", """{"text":"done","persisted_turn":{"user_row_id":$userRow,"final_assistant_row_id":9}}""", 7),
        ).fold(start()) { s, f -> ChatReducer.reduce(s, f) { "…" } }
        assertEquals(listOf("local-1", "a-1", "call_a", "a-5"), ids(end))
        val history = ChatReducer.fromHistory(
            listOf(
                TranscriptMessage(role = "user", text = "do the three things", rowId = userRow),
                TranscriptMessage(role = "assistant", text = "", reasoning = "look first", rowId = 7),
                TranscriptMessage(role = "tool", name = "terminal", toolCallId = "call_a"),
                TranscriptMessage(role = "assistant", text = "done", reasoning = "now answer", rowId = 9),
            ),
        )
        assertEquals(ids(end), ChatReducer.rebuild(history, end.items).map { it.id })
        // A second rebuild (the rows now have their row ids) agrees.
        val once = ChatReducer.rebuild(history, end.items)
        assertEquals(ids(end), ChatReducer.rebuild(history, once).map { it.id })
    }

    /** A history without tool-call ids or row ids still gets distinct ids that cannot collide with a row id. */
    @Test
    fun historyIdsAreUniqueWithoutWireIds() {
        val rows = listOf(
            TranscriptMessage(role = "user", text = "a", rowId = 1),
            TranscriptMessage(role = "tool", name = "terminal"),
            TranscriptMessage(role = "tool", name = "terminal", toolCallId = "terminal:0"),
            TranscriptMessage(role = "user", text = "b", rowId = 2),
            TranscriptMessage(role = "tool", name = "terminal", toolCallId = "terminal:0"),
        )
        assertEquals(listOf("h-1", "h-i1", "terminal:0", "h-2", "terminal:0#2"), ChatReducer.fromHistory(rows).map { it.id })
    }

    /** A provider that reuses a tool-call id in a later turn gets a new row; the earlier turn's row is not touched. */
    @Test
    fun aReusedToolIdInALaterTurnIsANewRow() {
        fun ev(type: String, payload: String, seq: Long) =
            Frame.Event(type, live, seq, HermesJson.parseToJsonElement(payload).jsonObject)
        var s = ChatReducer.onSubmitted(ChatState(liveSessionId = live), "one", "u1", flair = { "…" })
        s = listOf(
            ev("tool.start", """{"tool_id":"terminal:0","name":"terminal","context":"ls"}""", 1),
            ev("tool.complete", """{"tool_id":"terminal:0","name":"terminal","result":{"output":"first","exit_code":0}}""", 2),
            ev("message.complete", """{"text":"done"}""", 3),
        ).fold(s) { acc, f -> ChatReducer.reduce(acc, f) { "…" } }
        s = ChatReducer.onSubmitted(s, "two", "u2", flair = { "…" })
        s = ChatReducer.reduce(s, ev("tool.start", """{"tool_id":"terminal:0","name":"terminal","context":"pwd"}""", 4)) { "…" }
        val running = s.items.filterIsInstance<ChatItem.ToolCall>()
        assertEquals(listOf("terminal:0", "terminal:0#2"), running.map { it.id })
        assertEquals(listOf(ToolStatus.Done, ToolStatus.Running), running.map { it.status })
        s = ChatReducer.reduce(s, ev("tool.complete", """{"tool_id":"terminal:0","name":"terminal","result":{"output":"second","exit_code":0}}""", 5)) { "…" }
        assertEquals(listOf("first", "second"), s.items.filterIsInstance<ChatItem.ToolCall>().map { it.output })
        assertEquals(listOf("u1", "terminal:0", "a-3", "u2", "terminal:0#2"), ids(s))
    }

    /** An approval, like a tool, takes the place of the empty bubble `message.start` opened; nothing is left above it. */
    @Test
    fun aRequestCardReplacesTheEmptyBubble() {
        val started = ChatReducer.reduce(start(), Frame.Event("message.start", live, 1, JsonObject(emptyMap()))) { "…" }
        assertEquals(2, started.items.size)
        val asked = ChatReducer.reduce(
            started,
            Frame.ServerRequest("srq-1", "approval", HermesJson.parseToJsonElement("""{"session_id":"$live","request_id":"r","command":"ls","tool_name":"terminal"}""").jsonObject),
        ) { "…" }
        assertEquals(listOf("local-1", "srq-1"), ids(asked))
        assertFalse(asked.items.any { it is ChatItem.AssistantMessage })
        assertNull((asked.items.last() as ChatItem.Approval).takeIf { it.answered })
    }
}
