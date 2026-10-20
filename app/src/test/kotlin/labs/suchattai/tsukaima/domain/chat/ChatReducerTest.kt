package labs.suchattai.tsukaima.domain.chat

import kotlinx.serialization.json.jsonObject
import labs.suchattai.tsukaima.data.api.HermesJson
import labs.suchattai.tsukaima.data.gateway.Frame
import labs.suchattai.tsukaima.data.gateway.HistoryResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatReducerTest {
    private val sid = "d4fb0dea"
    private var flairCalls = 0
    private val flair: () -> String = { "flair-${++flairCalls}" }

    private fun obj(s: String) = HermesJson.parseToJsonElement(s).jsonObject

    private fun ev(type: String, payload: String = "{}", seq: Long? = null, session: String = sid) =
        Frame.Event(type, session, seq, obj(payload))

    private fun ChatState.feed(vararg frames: Frame): ChatState =
        frames.fold(this) { s, f -> ChatReducer.reduce(s, f, flair) }

    private val live = ChatState(liveSessionId = sid)

    private val approvalRequest = Frame.ServerRequest(
        id = "srq-1",
        method = "approval",
        params = obj(
            """{"session_id":"$sid","request_id":"req-9","command":"rm -rf /tmp/x",
               "tool_name":"terminal","choices":["once","session","always","deny"]}""",
        ),
    )

    // README §2 history fixture: user, reasoning-only assistant, tool row (no output), final assistant.
    private val historyJson = """
        {"count":4,"messages":[
          {"role":"user","text":"what kernel is this box running?","timestamp":1760955300.5,"row_id":1},
          {"role":"assistant","text":"","reasoning":"I should run uname -a","timestamp":1760955301.0,"row_id":2},
          {"role":"tool","text":"","name":"terminal","context":"uname -a","args":{"command":"uname -a"},
           "tool_call_id":"terminal:0","row_id":3},
          {"role":"assistant","text":"The kernel version is **6.17.2-1-pve** (Proxmox).","row_id":4}
        ]}
    """.trimIndent()

    @Test
    fun historyMapsRoles() {
        val messages = HermesJson.decodeFromString(HistoryResult.serializer(), historyJson).messages
        val items = ChatReducer.fromHistory(messages)

        assertEquals(3, items.size)
        val user = items[0] as ChatItem.UserMessage
        assertEquals("what kernel is this box running?", user.text)
        assertEquals("h-1", user.id)

        val tool = items[1] as ChatItem.ToolCall
        assertEquals("terminal", tool.name)
        assertEquals("uname -a", tool.context)
        assertEquals(ToolStatus.Done, tool.status)
        assertNull(tool.output)
        assertEquals("h-3", tool.id)

        val reply = items[2] as ChatItem.AssistantMessage
        assertTrue(reply.text.startsWith("The kernel"))
        assertFalse(reply.streaming)
        assertEquals("h-4", reply.id)
    }

    @Test
    fun deltaStreamAccumulates() {
        val s = live.feed(
            ev("message.start", seq = 1),
            ev("message.delta", """{"text":"The kernel "}""", 2),
            ev("message.delta", """{"text":"version is "}""", 3),
            ev("message.delta", """{"text":"6.17"}""", 4),
        )
        val assistants = s.items.filterIsInstance<ChatItem.AssistantMessage>()
        assertEquals(1, assistants.size)
        assertEquals("The kernel version is 6.17", assistants[0].text)
        assertTrue(assistants[0].streaming)
        assertEquals("a-1", assistants[0].id)
        assertEquals(AgentStatus.Replying, s.status)
    }

    @Test
    fun completeFinalizesText() {
        val s = live.feed(
            ev("message.start", seq = 1),
            ev("reasoning.delta", """{"text":"Let me check "}""", 2),
            ev("reasoning.delta", """{"text":"uname."}""", 3),
            ev("message.delta", """{"text":"The kernel"}""", 4),
            ev("message.complete", """{"text":"The kernel version is **6.17.2-1-pve**.","usage":{}}""", 5),
        )
        val a = s.items.single() as ChatItem.AssistantMessage
        assertEquals("The kernel version is **6.17.2-1-pve**.", a.text)
        assertFalse(a.streaming)
        assertEquals("Let me check uname.", a.reasoning)
        assertEquals(AgentStatus.Idle, s.status)

        // Empty payload text keeps the streamed text.
        val kept = live.feed(
            ev("message.start", seq = 1),
            ev("message.delta", """{"text":"streamed"}""", 2),
            ev("message.complete", """{"text":""}""", 3),
        )
        assertEquals("streamed", (kept.items.single() as ChatItem.AssistantMessage).text)
    }

    @Test
    fun toolStartCompleteFlipsStatus() {
        val generating = live.feed(
            ev("message.start", seq = 1),
            ev("tool.generating", """{"name":"terminal"}""", 2),
        )
        assertEquals(AgentStatus.Tool("shell command"), generating.status)
        assertEquals("Running shell command", generating.status.label)

        val started = generating.feed(
            ev(
                "tool.start",
                """{"tool_id":"terminal:0","name":"terminal","context":"uname -a","args":{"command":"uname -a"}}""",
                3,
            ),
        )
        assertEquals(AgentStatus.Tool("shell command"), started.status)
        val running = started.items.filterIsInstance<ChatItem.ToolCall>().single()
        assertEquals("terminal:0", running.id)
        assertEquals(ToolStatus.Running, running.status)
        assertEquals("uname -a", running.context)

        val done = started.feed(
            ev(
                "tool.complete",
                """{"tool_id":"terminal:0","name":"terminal","args":{"command":"uname -a"},"duration_s":0.186,
                   "result":{"output":"Linux claude 6.17.2-1-pve","exit_code":0,"error":null}}""",
                4,
            ),
        )
        val tool = done.items.filterIsInstance<ChatItem.ToolCall>().single()
        assertEquals(ToolStatus.Done, tool.status)
        assertEquals(0, tool.exitCode)
        assertEquals("Linux claude 6.17.2-1-pve", tool.output)
        assertEquals(0.186, tool.durationS!!, 1e-9)
    }

    @Test
    fun toolFailureMarksFailed() {
        val s = live.feed(
            ev("message.start", seq = 1),
            ev("tool.start", """{"tool_id":"terminal:0","name":"terminal","context":"false"}""", 2),
            ev(
                "tool.complete",
                """{"tool_id":"terminal:0","name":"terminal","duration_s":0.01,
                   "result":{"output":"","exit_code":1,"error":null}}""",
                3,
            ),
        )
        val tool = s.items.filterIsInstance<ChatItem.ToolCall>().single()
        assertEquals(ToolStatus.Failed, tool.status)
        assertEquals(1, tool.exitCode)
    }

    @Test
    fun approvalAppendsCardAndStatus() {
        val s = live.feed(ev("message.start", seq = 1), approvalRequest)
        val card = s.items.filterIsInstance<ChatItem.Approval>().single()
        assertEquals("srq-1", card.id)
        assertEquals("req-9", card.requestId)
        assertEquals("rm -rf /tmp/x", card.command)
        assertEquals("terminal", card.toolName)
        assertFalse(card.answered)
        assertEquals(AgentStatus.Approval, s.status)
    }

    @Test
    fun approvalAnsweredOnce() {
        val pending = live.feed(ev("message.start", seq = 1), approvalRequest)
        val once = ChatReducer.onApprovalAnswered(pending, "srq-1", flair)
        val twice = ChatReducer.onApprovalAnswered(once, "srq-1", flair)

        val cards = twice.items.filterIsInstance<ChatItem.Approval>()
        assertEquals(1, cards.size)
        assertTrue(cards[0].answered)
        assertTrue(twice.status is AgentStatus.Thinking)
        assertEquals(once, twice)
    }

    @Test
    fun requestCancelRemovesApproval() {
        val pending = live.feed(ev("message.start", seq = 1), approvalRequest)
        val s = pending.feed(
            ev("request.cancel", """{"id":"srq-1","method":"approval","reason":"timeout"}""", 2),
        )
        assertTrue(s.items.none { it is ChatItem.Approval })
        assertTrue(s.status !is AgentStatus.Approval)
    }

    @Test
    fun closeDuringStreamKeepsPartialText() {
        val streaming = live.feed(
            ev("message.start", seq = 1),
            ev("message.delta", """{"text":"The kernel ver"}""", 2),
        )
        val lost = ChatReducer.onConnectionLost(streaming)
        val a = lost.items.single() as ChatItem.AssistantMessage
        assertEquals("The kernel ver", a.text)
        assertFalse(a.streaming)
        assertEquals(AgentStatus.Offline, lost.status)

        val restored = ChatReducer.onConnectionRestored(lost)
        assertEquals(AgentStatus.Idle, restored.status)
        assertEquals(lost.items, restored.items)
    }

    @Test
    fun turnErrorAppendsMessage() {
        val s = live.feed(
            ev("message.start", seq = 1),
            ev("turn.error", """{"message":"provider timed out"}""", 2),
        )
        assertEquals(AgentStatus.Idle, s.status)
        val last = s.items.last() as ChatItem.AssistantMessage
        assertEquals("Something went wrong: provider timed out", last.text)
        assertFalse(last.streaming)
        assertTrue(s.items.filterIsInstance<ChatItem.AssistantMessage>().none { it.streaming })
    }

    @Test
    fun errorEventAbortsTurn() {
        val s = live.feed(
            ev("message.start", seq = 1),
            ev("tool.start", """{"tool_id":"t1","name":"terminal"}""", 2),
            ev("error", """{"message":"model provider rejected the request"}""", 3),
        )
        assertEquals(AgentStatus.Idle, s.status)
        assertEquals(ToolStatus.Failed, s.items.filterIsInstance<ChatItem.ToolCall>().single().status)
        val last = s.items.last() as ChatItem.AssistantMessage
        assertEquals("Something went wrong: model provider rejected the request", last.text)
        assertTrue(s.items.filterIsInstance<ChatItem.AssistantMessage>().none { it.streaming })
    }

    @Test
    fun errorCompletionEndsTurnAsError() {
        val withError = live.feed(
            ev("message.start", seq = 1),
            ev("message.complete", """{"text":"","status":"error","error":"context window exceeded"}""", 2),
        )
        assertEquals(AgentStatus.Idle, withError.status)
        assertEquals(
            "Something went wrong: context window exceeded",
            (withError.items.last() as ChatItem.AssistantMessage).text,
        )
        val bare = live.feed(ev("message.complete", """{"text":"","status":"error"}""", 1))
        assertEquals("Something went wrong", (bare.items.last() as ChatItem.AssistantMessage).text)
        // An error completion that carries text keeps it (the server already rendered the failure).
        val texted = live.feed(ev("message.complete", """{"text":"Error: boom","status":"error","error":"boom"}""", 1))
        assertEquals("Error: boom", (texted.items.last() as ChatItem.AssistantMessage).text)
        assertEquals(AgentStatus.Idle, texted.status)
    }

    @Test
    fun seqOnlyAdvances() {
        val s1 = live.feed(ev("session.info", seq = 5))
        assertEquals(5L, s1.lastSeq)
        val s2 = s1.feed(ev("session.info", seq = 3))
        assertEquals(5L, s2.lastSeq)
        val s3 = s2.feed(ev("session.info", seq = null))
        assertEquals(5L, s3.lastSeq)
        val s4 = s3.feed(ev("session.info", seq = 9))
        assertEquals(9L, s4.lastSeq)
        // Global events and other sessions never touch lastSeq.
        assertEquals(9L, s4.feed(ev("sessions.changed", seq = 50, session = "")).lastSeq)
        assertEquals(9L, s4.feed(ev("session.info", seq = 60, session = "other")).lastSeq)
        // Without a live session, nothing advances the watermark (session.title still applies).
        val unbound = ChatState().feed(ev("session.title", """{"title":"T"}""", 4))
        assertEquals(0L, unbound.lastSeq)
        assertEquals("T", unbound.title)
    }

    @Test
    fun fullTurnSequenceOrdersUserToolAssistant() {
        val final = "The kernel version is **6.17.2-1-pve** on this Proxmox box."
        val s = ChatReducer.onSubmitted(live, "what kernel?", "local-1", flair).feed(
            ev("session.info", """{"model":"m","running":true}""", 1),
            ev("message.start", seq = 2),
            ev("session.title", """{"session_id":"20261020_101500_ab12cd","title":"Kernel"}""", 3),
            ev("thinking.delta", """{"text":""}""", 4),
            ev("reasoning.delta", """{"text":"I should run uname."}""", 5),
            ev("tool.generating", """{"name":"terminal"}""", 6),
            ev("tool.start", """{"tool_id":"terminal:0","name":"terminal","context":"uname -a","args":{"command":"uname -a"}}""", 7),
            ev(
                "tool.complete",
                """{"tool_id":"terminal:0","name":"terminal","args":{"command":"uname -a"},"duration_s":0.186,
                   "result":{"output":"Linux claude 6.17.2-1-pve","exit_code":0,"error":null}}""",
                8,
            ),
            ev("session.usage", """{"usage":{}}""", 9),
            ev("message.delta", """{"text":"The kernel version "}""", 10),
            ev("message.delta", """{"text":"is **6.17.2-1-pve**"}""", 11),
            ev("reasoning.available", """{"text":"I should run uname."}""", 12),
            ev("message.complete", """{"text":"$final","usage":{}}""", 13),
            ev("session.info", """{"running":false}""", 14),
            ev("sessions.changed", session = ""),
        )
        assertEquals(3, s.items.size)
        assertTrue(s.items[0] is ChatItem.UserMessage)
        assertEquals(ToolStatus.Done, (s.items[1] as ChatItem.ToolCall).status)
        val a = s.items[2] as ChatItem.AssistantMessage
        assertFalse(a.streaming)
        assertEquals(final, a.text)
        assertEquals("I should run uname.", a.reasoning)
        assertEquals(AgentStatus.Idle, s.status)
    }

    @Test
    fun messageStartFinalizesOrphanStream() {
        val s = live.feed(
            ev("message.start", seq = 1),
            ev("message.delta", """{"text":"partial"}""", 2),
            ev("message.start", seq = 3),
        )
        val assistants = s.items.filterIsInstance<ChatItem.AssistantMessage>()
        assertEquals(2, assistants.size)
        assertEquals("partial", assistants[0].text)
        assertFalse(assistants[0].streaming)
        assertTrue(assistants[1].streaming)
    }

    @Test
    fun approvalCancelledRemovesCardsAndRestoresStatus() {
        val pending = live.feed(ev("message.start", seq = 1), approvalRequest)
        assertEquals(AgentStatus.Approval, pending.status)
        val s = pending.feed(
            ev(
                "approval.cancelled",
                """{"session_id":"$sid","reason":"interrupt","cancelled_count":1,"request_ids":["req-9"]}""",
                2,
            ),
        )
        assertTrue(s.items.none { it is ChatItem.Approval })
        assertTrue(s.status is AgentStatus.Thinking)
    }

    @Test
    fun turnErrorFailsRunningToolsAndClearsApprovals() {
        val s = live.feed(
            ev("message.start", seq = 1),
            ev("tool.start", """{"tool_id":"terminal:0","name":"terminal","context":"sleep 99"}""", 2),
            approvalRequest,
            ev("turn.error", """{"message":"interrupted"}""", 3),
        )
        assertEquals(ToolStatus.Failed, s.items.filterIsInstance<ChatItem.ToolCall>().single().status)
        assertTrue(s.items.none { it is ChatItem.Approval })
        assertEquals(AgentStatus.Idle, s.status)
        assertEquals("Something went wrong: interrupted", (s.items.last() as ChatItem.AssistantMessage).text)
    }

    @Test
    fun replayedEventsAreIgnored() {
        val s = live.feed(
            ev("message.start", seq = 2),
            ev("message.delta", """{"text":"a"}""", 3),
            ev("message.start", seq = 2),
            ev("message.delta", """{"text":"a"}""", 3),
            ev("message.delta", """{"text":"b"}""", 4),
        )
        val assistants = s.items.filterIsInstance<ChatItem.AssistantMessage>()
        assertEquals(1, assistants.size)
        assertEquals("ab", assistants[0].text)
        assertEquals(4L, s.lastSeq)
    }

    // --- extra coverage for the delegated decisions ---

    @Test
    fun submittedAppendsUserAndThinks() {
        val s = ChatReducer.onSubmitted(live, "what kernel?", "local-1", flair)
        assertEquals(ChatItem.UserMessage("local-1", "what kernel?"), s.items.single())
        assertEquals(AgentStatus.Thinking("flair-1"), s.status)
    }

    @Test
    fun otherSessionEventsIgnored() {
        val s = live.feed(
            ev("message.start", seq = 7, session = "someone-else"),
            ev("message.delta", """{"text":"x"}""", 8, session = "someone-else"),
        )
        assertEquals(live, s)
        // Global events (empty session id) pass through but never move the watermark.
        val g = live.feed(ev("sessions.changed", seq = 2, session = ""))
        assertEquals(0L, g.lastSeq)
    }

    @Test
    fun thinkingRotatesFlairAndSessionTitleSets() {
        val s = live.feed(
            ev("message.start", seq = 1),
            ev("session.title", """{"session_id":"20261020_101500_ab12cd","title":"Kernel check"}""", 2),
            ev("thinking.delta", """{"text":""}""", 3),
        )
        val afterStart = live.feed(ev("message.start", seq = 1)).status as AgentStatus.Thinking
        val afterThinking = s.status as AgentStatus.Thinking
        assertTrue(afterStart.flair != afterThinking.flair)
        assertEquals("Kernel check", s.title)
    }

    @Test
    fun humanNames() {
        assertEquals("shell command", ChatReducer.humanName("terminal"))
        assertEquals("code", ChatReducer.humanName("execute_code"))
        assertEquals("web search", ChatReducer.humanName("web_search"))
        assertEquals("web page", ChatReducer.humanName("web_extract"))
        assertEquals("subagent", ChatReducer.humanName("delegate_task"))
        assertEquals("read file", ChatReducer.humanName("read_file"))
    }

    @Test
    fun sessionBoundResetsSeqAndCounter() {
        val before = ChatState(liveSessionId = "old1", storedSessionId = "20261020_010916_48c967", lastSeq = 42, localCounter = 7)
        val bound = ChatReducer.onSessionBound(before, liveId = "d4fb0dea", storedId = "20261020_010916_48c967")
        assertEquals("d4fb0dea", bound.liveSessionId)
        assertEquals("20261020_010916_48c967", bound.storedSessionId)
        assertEquals(0L, bound.lastSeq)
        assertEquals(0L, bound.localCounter)
        // The new live session's seq restarts at 1, and must not be swallowed by the old watermark.
        val next = bound.feed(ev("message.start", seq = 1, session = "d4fb0dea"))
        assertEquals(1L, next.lastSeq)
        assertEquals("a-1", next.items.single().id)
    }

    @Test
    fun sessionBoundKeepsStoredIdWhenNull() {
        val bound = ChatReducer.onSessionBound(ChatState(storedSessionId = "20261020_010916_48c967"), "d4fb0dea", null)
        assertEquals("20261020_010916_48c967", bound.storedSessionId)
    }

    @Test
    fun sessionBoundToSameLiveIdKeepsWatermark() {
        // The server re-attached the same live session (reconnect within its grace window): its seq did not
        // restart, so the watermark and counter stay and a replay is a true delta.
        val before = ChatState(liveSessionId = sid, lastSeq = 6, localCounter = 2)
        val bound = ChatReducer.onSessionBound(before, sid, "20261020_010916_48c967")
        assertEquals(6L, bound.lastSeq)
        assertEquals(2L, bound.localCounter)
        assertEquals("20261020_010916_48c967", bound.storedSessionId)
    }
}
