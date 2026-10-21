package labs.suchattai.tsukaima.domain.chat

import kotlinx.serialization.json.jsonObject
import labs.suchattai.tsukaima.data.api.HermesJson
import labs.suchattai.tsukaima.data.gateway.Frame
import labs.suchattai.tsukaima.data.gateway.HistoryResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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

    private fun clarifyRequest(id: String = "srq-q", session: String = sid) = Frame.ServerRequest(
        id = id,
        method = "clarify",
        params = obj(
            """{"session_id":"$session","questions":[
                 {"qid":"q1","question":"Which colour?","choices":["red","green","blue"],"multi_select":false},
                 {"qid":"q2","question":"Toppings?","choices":["a","b"],"multi_select":true},
                 {"qid":"q3","question":"Anything else?","choices":null}
               ]}""",
        ),
    )

    @Test
    fun clarifyAppendsCardAndStatus() {
        val s = live.feed(ev("message.start", seq = 1), clarifyRequest())
        val card = s.items.filterIsInstance<ChatItem.Clarify>().single()
        assertEquals("srq-q", card.id)
        assertFalse(card.answered)
        assertEquals(
            listOf(
                ClarifyQuestion("q1", "Which colour?", listOf("red", "green", "blue"), multiSelect = false),
                ClarifyQuestion("q2", "Toppings?", listOf("a", "b"), multiSelect = true),
                ClarifyQuestion("q3", "Anything else?", null, multiSelect = false),
            ),
            card.questions,
        )
        assertEquals(AgentStatus.Question, s.status)
        assertEquals("Waiting for your answer", AgentStatus.Question.label)
        assertEquals(StatusTone.Amber, AgentStatus.Question.tone)
        assertFalse(AgentStatus.Question.pulsing)
        // Another live session's question, and a replay of the same id, are ignored.
        assertEquals(s, s.feed(clarifyRequest("srq-other", session = "ffffffff"), clarifyRequest()))
    }

    @Test
    fun clarifyCancelRemovesCard() {
        val pending = live.feed(ev("message.start", seq = 1), clarifyRequest())
        val s = pending.feed(ev("request.cancel", """{"id":"srq-q","method":"clarify","reason":"timeout"}""", 2))
        assertTrue(s.items.none { it is ChatItem.Clarify })
        assertTrue(s.status is AgentStatus.Thinking)
    }

    @Test
    fun clarifyAnsweredOnce() {
        val pending = live.feed(ev("message.start", seq = 1), clarifyRequest())
        val answers = mapOf("q1" to "red", "q2" to null, "q3" to null)
        val once = ChatReducer.onClarifyAnswered(pending, "srq-q", answers, flair)
        val twice = ChatReducer.onClarifyAnswered(once, "srq-q", mapOf("q1" to "blue"), flair)

        val card = twice.items.filterIsInstance<ChatItem.Clarify>().single()
        assertTrue(card.answered)
        assertEquals(answers, card.answers)
        assertTrue(twice.status is AgentStatus.Thinking)
        assertEquals(once, twice)
    }

    @Test
    fun turnErrorDropsUnansweredClarify() {
        val s = live.feed(ev("message.start", seq = 1), clarifyRequest(), ev("error", """{"message":"boom"}""", 2))
        assertTrue(s.items.none { it is ChatItem.Clarify })
        assertEquals(AgentStatus.Idle, s.status)
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
        assertEquals(NO_SEQ, unbound.lastSeq)
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
        assertEquals(NO_SEQ, g.lastSeq)
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
        assertEquals(NO_SEQ, bound.lastSeq)
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

    // --- v0.4.0 ---

    @Test
    fun seqZeroOnAFreshSessionIsApplied() {
        // The watermark starts at NO_SEQ, not 0: an event numbered 0 is new, not a replay.
        val s = live.feed(ev("message.start", seq = 0), ev("message.delta", """{"text":"hi"}""", 1))
        assertEquals(1L, s.lastSeq)
        assertEquals("hi", (s.items.single() as ChatItem.AssistantMessage).text)
        assertEquals("a-0", s.items.single().id)
        // ...and its replay is then dropped.
        assertEquals(s, s.feed(ev("message.delta", """{"text":"hi"}""", 0)))
    }

    @Test
    fun interimNotStreamedBecomesItsOwnReplyBeforeTheNextTool() {
        val s = live.feed(
            ev("message.start", seq = 1),
            ev("message.interim", """{"text":"Let me check the disk first.","already_streamed":false}""", 2),
            ev("tool.start", """{"tool_id":"t1","name":"terminal","context":"df -h"}""", 3),
            ev("message.delta", """{"text":"Plenty of space."}""", 4),
            ev("message.complete", """{"text":"Plenty of space."}""", 5),
        )
        val kinds = s.items.map { it::class.simpleName }
        assertEquals(listOf("AssistantMessage", "ToolCall", "AssistantMessage"), kinds)
        val interim = s.items[0] as ChatItem.AssistantMessage
        assertEquals("Let me check the disk first.", interim.text)
        assertFalse(interim.streaming)
        assertEquals("Plenty of space.", (s.items[2] as ChatItem.AssistantMessage).text)
    }

    @Test
    fun interimAfterStreamedTextIsAppendedClosedAndAlreadyStreamedIgnored() {
        val streamed = live.feed(ev("message.start", seq = 1), ev("message.delta", """{"text":"Checking."}""", 2))
        // already_streamed: the text is in the bubble already.
        assertEquals(streamed.copy(lastSeq = 3), streamed.feed(ev("message.interim", """{"text":"Checking.","already_streamed":true}""", 3)))
        val s = streamed.feed(ev("message.interim", """{"text":"Now the logs.","already_streamed":false}""", 3))
        assertEquals(listOf("Checking.", "Now the logs."), s.items.map { (it as ChatItem.AssistantMessage).text })
        assertTrue(s.items.none { (it as ChatItem.AssistantMessage).streaming })
        assertEquals("i-3", s.items.last().id)
        // A blank interim changes nothing but the watermark.
        assertEquals(streamed.copy(lastSeq = 3), streamed.feed(ev("message.interim", """{"text":" ","already_streamed":false}""", 3)))
    }

    @Test
    fun streamedReasoningShowsOpenUntilTheUserClosesIt() {
        val s = live.feed(ev("message.start", seq = 1), ev("reasoning.delta", """{"text":"think"}""", 2))
        val a = s.items.single() as ChatItem.AssistantMessage
        assertTrue(a.reasoningLive)
        assertTrue("live reasoning is readable without a tap", ChatReducer.reasoningOpen(s, a))

        val closed = ChatReducer.toggleReasoning(s, a.id)
        assertFalse(ChatReducer.reasoningOpen(closed, a))
        // Streaming on, text arriving and the turn ending never change the user's choice.
        val done = closed.feed(ev("message.delta", """{"text":"Hi"}""", 3), ev("message.complete", """{"text":"Hi","usage":{}}""", 4))
        val final = done.items.single() as ChatItem.AssistantMessage
        assertFalse(ChatReducer.reasoningOpen(done, final))
        assertTrue(ChatReducer.reasoningOpen(ChatReducer.toggleReasoning(done, final.id), final))

        // History loads closed; a tap opens it.
        val fromHistory = ChatItem.AssistantMessage("h-1", "Hi", streaming = false, reasoning = "think")
        assertFalse(ChatReducer.reasoningOpen(ChatState(items = listOf(fromHistory)), fromHistory))
    }

    @Test
    fun reasoningCarriedToANewBubbleKeepsItsOpenChoice() {
        // Reasoning-only bubble, user closes it, then a request blocks it so the next delta opens a new bubble.
        val s = live.feed(ev("message.start", seq = 1), ev("reasoning.delta", """{"text":"think"}""", 2))
        val first = s.items.single() as ChatItem.AssistantMessage
        val closed = ChatReducer.toggleReasoning(s, first.id)
        val moved = closed.feed(prompt("srq-1", "clarify", """"questions":[{"qid":"q","question":"Which?"}]"""), ev("reasoning.delta", """{"text":" more"}""", 3))
        val carried = moved.items.filterIsInstance<ChatItem.AssistantMessage>().last()
        assertNotEquals(first.id, carried.id)
        assertEquals("think more", carried.reasoning)
        assertTrue(carried.reasoningLive)
        assertFalse("the choice followed the reasoning", ChatReducer.reasoningOpen(moved, carried))
        assertFalse(first.id in moved.reasoningOpen)
    }

    @Test
    fun interimFillsAReasoningOnlyBubble() {
        val s = live.feed(
            ev("message.start", seq = 1),
            ev("reasoning.delta", """{"text":"think"}""", 2),
            ev("message.interim", """{"text":"First, the config.","already_streamed":false}""", 3),
        )
        val only = s.items.single() as ChatItem.AssistantMessage
        assertEquals("First, the config.", only.text)
        assertEquals("think", only.reasoning)
        assertFalse(only.streaming)
    }

    private fun prompt(id: String, method: String, params: String) =
        Frame.ServerRequest(id, method, obj("""{"session_id":"$sid",$params}"""))

    @Test
    fun maskedPromptsAddCardsWithWaitingStatus() {
        val sudo = live.feed(prompt("srq-u", "sudo", """"command":"sudo apt update""""))
        val card = sudo.items.single() as ChatItem.SecretPrompt
        assertEquals(SecretKind.Sudo, card.kind)
        assertEquals("sudo apt update", card.subject)
        assertEquals(AgentStatus.Secret(SecretKind.Sudo), sudo.status)
        assertEquals("Waiting for a password", sudo.status.label)
        assertEquals(StatusTone.Amber, sudo.status.tone)

        val secret = live.feed(prompt("srq-s", "secret", """"env_var":"API_KEY","prompt":"Weather API key""""))
        val s = secret.items.single() as ChatItem.SecretPrompt
        assertEquals(SecretKind.Secret, s.kind)
        assertEquals("API_KEY", s.subject)
        assertEquals("Weather API key", s.prompt)
        assertEquals("Waiting for a secret", secret.status.label)

        val vault = live.feed(prompt("srq-v", "vault.unlock_prompt", """"backend":"bw","display_name":"Bitwarden""""))
        assertEquals("Bitwarden", (vault.items.single() as ChatItem.SecretPrompt).subject)
        assertEquals(AgentStatus.Secret(SecretKind.VaultUnlock), vault.status)
        assertEquals("Waiting for a password", vault.status.label)
        // Other sessions' prompts and duplicates are ignored.
        assertEquals(live, live.feed(Frame.ServerRequest("srq-x", "sudo", obj("""{"session_id":"other"}"""))))
        assertEquals(sudo, sudo.feed(prompt("srq-u", "sudo", """"command":"sudo apt update"""")))
    }

    @Test
    fun vaultCodeAndSaveLoginBecomeCards() {
        val code = live.feed(prompt("srq-c", "vault.code", """"site":"github.com","hint":"6-digit code from your authenticator""""))
        val c = code.items.single() as ChatItem.SecretPrompt
        assertEquals(SecretKind.VaultCode, c.kind)
        assertEquals("github.com", c.subject)
        assertEquals("6-digit code from your authenticator", c.prompt)
        assertEquals(AgentStatus.Secret(SecretKind.VaultCode), code.status)
        assertEquals("Waiting for a code", code.status.label)
        assertEquals(StatusTone.Amber, code.status.tone)
        // Both params are optional for a code.
        val bare = live.feed(prompt("srq-c2", "vault.code", """"site":null"""))
        assertEquals("", (bare.items.single() as ChatItem.SecretPrompt).subject)
        assertEquals(null, (bare.items.single() as ChatItem.SecretPrompt).prompt)

        val login = live.feed(prompt("srq-l", "vault.save_login", """"origin":"https://github.com","site":"github.com""""))
        val l = login.items.single() as ChatItem.SecretPrompt
        assertEquals(SecretKind.VaultSaveLogin, l.kind)
        assertEquals("github.com", l.subject)
        assertEquals("https://github.com", l.origin) // both kept: the card shows the scheme
        assertEquals(null, l.prompt)
        // Kinds without an origin leave it null.
        assertEquals(null, c.origin)
        assertEquals(null, (live.feed(prompt("srq-u", "sudo", """"command":"sudo -v","origin":"https://x"""")).items.single() as ChatItem.SecretPrompt).origin)
        assertEquals("Waiting for a login", login.status.label)
        // No site: the origin names it.
        val originOnly = live.feed(prompt("srq-l2", "vault.save_login", """"origin":"https://x.test","site":"""""))
        assertEquals("https://x.test", (originOnly.items.single() as ChatItem.SecretPrompt).subject)
    }

    @Test
    fun vaultPromptsAnsweredResumeThinking() {
        val waiting = live.copy(status = AgentStatus.Thinking("x"))
            .feed(prompt("srq-c", "vault.code", """"site":"x""""), prompt("srq-l", "vault.save_login", """"origin":"https://x","site":"x""""))
        // The latest open masked prompt names the status.
        assertEquals(AgentStatus.Secret(SecretKind.VaultSaveLogin), waiting.status)
        val loginSaved = ChatReducer.onSecretAnswered(waiting, "srq-l", declined = false, flair)
        assertEquals(AgentStatus.Secret(SecretKind.VaultCode), loginSaved.status)
        val codeDeclined = ChatReducer.onSecretAnswered(loginSaved, "srq-c", declined = true, flair)
        assertTrue(codeDeclined.status is AgentStatus.Thinking)
        val cards = codeDeclined.items.filterIsInstance<ChatItem.SecretPrompt>()
        assertTrue(cards.all { it.answered })
        assertEquals(listOf(true, false), cards.map { it.declined }) // code declined, login saved
    }

    @Test
    fun maskedPromptAnsweredRecordsOnlyDeclinedAndResumesThinking() {
        val waiting = live.copy(status = AgentStatus.Thinking("x")).feed(prompt("srq-u", "sudo", """"command":"sudo -v""""))
        val sent = ChatReducer.onSecretAnswered(waiting, "srq-u", declined = false, flair)
        val card = sent.items.single() as ChatItem.SecretPrompt
        assertTrue(card.answered)
        assertFalse(card.declined)
        assertTrue(sent.status is AgentStatus.Thinking)
        assertEquals(sent, ChatReducer.onSecretAnswered(sent, "srq-u", declined = true, flair)) // idempotent
        val declined = ChatReducer.onSecretAnswered(waiting, "srq-u", declined = true, flair)
        assertTrue((declined.items.single() as ChatItem.SecretPrompt).declined)
    }

    @Test
    fun requestCancelRemovesMaskedPromptAndTurnErrorDropsIt() {
        val waiting = live.feed(prompt("srq-s", "secret", """"env_var":"K","prompt":"p""""))
        val cancelled = waiting.feed(ev("request.cancel", """{"id":"srq-s","method":"secret","reason":"timeout"}""", 4))
        assertTrue(cancelled.items.isEmpty())
        assertTrue(cancelled.status is AgentStatus.Thinking)
        val failed = waiting.feed(ev("error", """{"message":"boom"}""", 4))
        assertTrue(failed.items.none { it is ChatItem.SecretPrompt })
    }

    @Test
    fun approvalWaitsBeforeAMaskedPrompt() {
        val both = live.feed(approvalRequest, prompt("srq-u", "sudo", """"command":"sudo -v""""))
        assertEquals(AgentStatus.Approval, both.status)
        val afterApproval = ChatReducer.onApprovalAnswered(both, "srq-1", flair)
        assertEquals(AgentStatus.Secret(SecretKind.Sudo), afterApproval.status)
    }

    @Test
    fun approvalScopesFromChoicesOrFlags() {
        val full = live.feed(approvalRequest).items.single() as ChatItem.Approval
        assertTrue(full.allowSession)
        assertTrue(full.allowAlways)
        val onceOnly = live.feed(
            Frame.ServerRequest("srq-2", "approval", obj("""{"session_id":"$sid","request_id":"r","choices":["once","deny"]}""")),
        ).items.single() as ChatItem.Approval
        assertFalse(onceOnly.allowSession)
        assertFalse(onceOnly.allowAlways)
        // No `choices`: derived like the server (allow_permanent=false hides always; smart_denied hides both).
        val noPermanent = live.feed(
            Frame.ServerRequest("srq-3", "approval", obj("""{"session_id":"$sid","request_id":"r","allow_permanent":false}""")),
        ).items.single() as ChatItem.Approval
        assertTrue(noPermanent.allowSession)
        assertFalse(noPermanent.allowAlways)
        val smart = live.feed(
            Frame.ServerRequest("srq-4", "approval", obj("""{"session_id":"$sid","request_id":"r","smart_denied":true}""")),
        ).items.single() as ChatItem.Approval
        assertFalse(smart.allowSession)
        assertFalse(smart.allowAlways)
    }

    // ---- v0.5.0: usage, reasoning effort, output risk, subagents, resume progress ----------------------------

    /** Live-probed `usage` object (`message.complete` / `session.usage` / `session.info`), trimmed. */
    private val usageJson =
        """{"model":"m","input":15882,"output":4,"total":31502,"calls":2,"context_used":15754,"context_max":1000000,"context_percent":2}"""

    @Test
    fun sessionUsageEventSetsTheCounters() {
        val s = live.feed(ev("session.usage", """{"usage":$usageJson}""", seq = 3))
        assertEquals(SessionUsage(contextPercent = 2, contextUsed = 15754, contextMax = 1000000, totalTokens = 31502), s.usage)
        assertEquals("15.8k / 1M ctx · 2%", s.usage?.meter)
    }

    @Test
    fun messageCompleteCarriesTheFinalUsage() {
        val s = live.feed(
            ev("message.start", seq = 1),
            ev("message.delta", """{"text":"two"}""", seq = 2),
            ev("message.complete", """{"text":"two","usage":$usageJson}""", seq = 3),
        )
        assertEquals("two", (s.items.single() as ChatItem.AssistantMessage).text)
        assertEquals(31502L, s.usage?.totalTokens)
        assertEquals(AgentStatus.Idle, s.status)
    }

    @Test
    fun emptyUsageKeepsWhatIsKnown() {
        val known = live.feed(ev("session.usage", """{"usage":$usageJson}""", seq = 1))
        val after = known.feed(ev("session.usage", """{"usage":{}}""", seq = 2), ev("message.complete", """{"text":""}""", seq = 3))
        assertEquals(known.usage, after.usage)
    }

    @Test
    fun sessionInfoSetsReasoningEffortAndUsage() {
        val s = live.feed(ev("session.info", """{"model":"m","reasoning_effort":"low","usage":$usageJson}""", seq = 1))
        assertEquals("low", s.reasoningEffort)
        assertEquals(2, s.usage?.contextPercent)
        // A lazy session's info names neither: what is known stays.
        val lazy = s.feed(ev("session.info", """{"model":"m","lazy":true}""", seq = 2))
        assertEquals("low", lazy.reasoningEffort)
        assertEquals(s.usage, lazy.usage)
        // "" is the provider default, kept as such (not "unknown").
        assertEquals("", lazy.feed(ev("session.info", """{"reasoning_effort":""}""", seq = 3)).reasoningEffort)
    }

    @Test
    fun meterFormatsEitherHalf() {
        assertEquals("42% ctx", SessionUsage(contextPercent = 42).meter)
        assertEquals("950 ctx", SessionUsage(contextUsed = 950).meter)
        assertNull("the billing total alone is not a meter", SessionUsage(totalTokens = 1_234_567).meter)
        assertNull(SessionUsage().meter)
    }

    @Test
    fun outputRiskFlagsTheToolRow() {
        val s = live.feed(
            ev("tool.start", """{"tool_id":"t1","name":"web_extract","context":"example.com"}""", seq = 1),
            ev("tool.complete", """{"tool_id":"t1","name":"web_extract","result":{"output":"…"}}""", seq = 2),
            ev("tool.output_risk", """{"tool_id":"t1","name":"web_extract","risk":"high","findings":["prompt_injection"],"redacted":true}""", seq = 3),
        )
        val tool = s.items.single() as ChatItem.ToolCall
        assertEquals(ToolRisk("high", listOf("prompt_injection"), redacted = true), tool.risk)
        assertEquals(ToolStatus.Done, tool.status)
        // An unknown tool id changes nothing.
        assertEquals(s.items, s.feed(ev("tool.output_risk", """{"tool_id":"nope","risk":"low","findings":[]}""", seq = 4)).items)
    }

    /** Live-probed `subagent.*` payloads (trimmed): spawn, thinking, complete for one background child. */
    private fun sub(type: String, extra: String = "", seq: Long) = ev(
        type,
        """{"goal":"Reply with pong","task_count":1,"task_index":0,"subagent_id":"sa-0-8cb3ad99","delegation_id":"deleg_1"$extra}""",
        seq = seq,
    )

    @Test
    fun subagentEventsKeepOneRowWithItsStatus() {
        val started = live.feed(sub("subagent.start", seq = 1), sub("subagent.thinking", ""","text":"deliberating…"""", seq = 2))
        val row = started.items.single() as ChatItem.Subagent
        assertEquals("sub-sa-0-8cb3ad99", row.id)
        assertEquals("Reply with pong", row.goal)
        assertEquals(SubagentStatus.Running, row.status)
        assertEquals("deliberating…", row.detail)
        assertNull(row.position)
        assertEquals("a subagent does not change the agent's status", AgentStatus.Idle, started.status)

        val done = started.feed(sub("subagent.complete", ""","status":"completed","summary":"pong"""", seq = 3))
        val finished = done.items.single() as ChatItem.Subagent
        assertEquals(SubagentStatus.Done, finished.status)
        assertEquals("pong", finished.summary)
        assertEquals("deliberating…", finished.detail)

        val failed = started.feed(sub("subagent.complete", ""","status":"timeout"""", seq = 3))
        assertEquals(SubagentStatus.Failed, (failed.items.single() as ChatItem.Subagent).status)
    }

    @Test
    fun subagentsOfOneDelegationAreSeparateRows() {
        val s = live.feed(
            ev("subagent.start", """{"goal":"a","task_count":2,"task_index":0,"delegation_id":"d"}""", seq = 1),
            ev("subagent.start", """{"goal":"b","task_count":2,"task_index":1,"delegation_id":"d"}""", seq = 2),
            ev("subagent.text", """{"goal":"b","task_count":2,"task_index":1,"delegation_id":"d","text":"x"}""", seq = 3),
        )
        val rows = s.items.map { it as ChatItem.Subagent }
        assertEquals(listOf("sub-d-0", "sub-d-1"), rows.map { it.id })
        assertEquals(listOf("1/2", "2/2"), rows.map { it.position })
        assertNull("subagent.text is only for a watch window", rows[1].detail)
    }

    @Test
    fun resumeProgressBecomesSkeletonText() {
        val unbound = ChatState()
        assertEquals("Loading history…", unbound.feed(ev("session.resume_progress", """{"phase":"history","status":"loading"}""")).resumeProgress)
        assertEquals(
            "Loaded 42 messages",
            unbound.feed(ev("session.resume_progress", """{"phase":"history","status":"complete","message_count":42}""")).resumeProgress,
        )
        assertEquals(
            "disk full",
            unbound.feed(ev("session.resume_progress", """{"phase":"history","status":"failed","message":"disk full"}""")).resumeProgress,
        )
    }

    // ---- edit and regenerate ----------------------------------------------------------------------

    @Test
    fun historyKeepsRowIdsOnMessages() {
        val messages = HermesJson.decodeFromString(HistoryResult.serializer(), historyJson).messages
        val items = ChatReducer.fromHistory(messages)
        assertEquals(1L, (items[0] as ChatItem.UserMessage).rowId)
        assertEquals(4L, (items[2] as ChatItem.AssistantMessage).rowId)
    }

    @Test
    fun submitAcceptedStoresTheRowOnTheEcho() {
        val s = ChatReducer.onSubmitted(live, "hi", "u-l1", flair)
        val accepted = ChatReducer.onSubmitAccepted(s, "u-l1", 42L)
        assertEquals(42L, (accepted.items.single() as ChatItem.UserMessage).rowId)
        assertEquals(accepted, ChatReducer.onSubmitAccepted(accepted, "missing", 7L))
        assertEquals(accepted, ChatReducer.onSubmitAccepted(accepted, "u-l1", null))
    }

    @Test
    fun truncationDropsTheEditedRowAndEverythingAfterIt() {
        val items = listOf(
            ChatItem.UserMessage("h-1", "first", rowId = 1),
            ChatItem.AssistantMessage("h-2", "one", streaming = false, reasoning = null, rowId = 2),
            ChatItem.UserMessage("h-3", "second", rowId = 3),
            ChatItem.ToolCall("t-1", "terminal", null, null, ToolStatus.Done, null, null, null),
            ChatItem.AssistantMessage("h-5", "two", streaming = false, reasoning = null, rowId = 5),
            ChatItem.UserMessage("u-l1", "local echo, not yet persisted"),
            ChatItem.AssistantMessage("a-9", "live reply", streaming = false, reasoning = null),
        )
        val cut = ChatReducer.onTruncated(live.copy(items = items), 3L)
        assertEquals(listOf("h-1", "h-2"), cut.items.map { it.id })
    }

    @Test
    fun truncationCutsAtTheFirstRowAtOrAfterTheTarget() {
        // Row 3 is gone from the client (e.g. a blank reply) — the cut lands on the next known row.
        val items = listOf(
            ChatItem.UserMessage("h-1", "first", rowId = 1),
            ChatItem.UserMessage("h-4", "later", rowId = 4),
            ChatItem.UserMessage("u-l2", "echo"),
        )
        assertEquals(listOf("h-1"), ChatReducer.onTruncated(live.copy(items = items), 3L).items.map { it.id })
        // No row at or after the target: nothing to drop.
        val none = live.copy(items = items.take(1))
        assertEquals(none, ChatReducer.onTruncated(none, 9L))
    }

    @Test
    fun survivorRowIdsRebindTheKeptUserMessages() {
        val items = listOf(
            ChatItem.UserMessage("h-1", "first", rowId = 1),
            ChatItem.AssistantMessage("h-2", "one", streaming = false, reasoning = null, rowId = 2),
            ChatItem.UserMessage("h-3", "second", rowId = 3),
            ChatItem.UserMessage("u-l1", "new"),
        )
        val s = ChatReducer.onSubmitAccepted(live.copy(items = items), "u-l1", 11L, survivorUserRowIds = listOf(1L, null))
        assertEquals(listOf(1L, 2L, null, 11L), s.items.map {
            when (it) {
                is ChatItem.UserMessage -> it.rowId
                is ChatItem.AssistantMessage -> it.rowId
                else -> -1L
            }
        })
    }

    @Test
    fun completedTurnTakesItsPersistedRowIds() {
        val s = ChatReducer.onSubmitted(live, "hi", "u-l1", flair).feed(
            ev("message.start", seq = 1),
            ev("message.delta", """{"text":"Hello"}""", seq = 2),
            ev("message.complete", """{"text":"Hello","persisted_turn":{"row_ids":[7,8],"complete":true,"user_row_id":7,"final_assistant_row_id":8}}""", seq = 3),
        )
        assertEquals(7L, (s.items[0] as ChatItem.UserMessage).rowId)
        assertEquals(8L, (s.items[1] as ChatItem.AssistantMessage).rowId)
    }

    @Test
    fun aTurnWithoutFinalTextNeverStampsAnOlderReply() {
        val history = live.copy(
            items = listOf(
                ChatItem.UserMessage("h-1", "first", rowId = 1),
                ChatItem.AssistantMessage("h-2", "one", streaming = false, reasoning = null, rowId = 2),
            ),
        )
        val s = ChatReducer.onSubmitted(history, "again", "u-l1", flair).feed(
            ev("message.complete", """{"text":"","persisted_turn":{"row_ids":[7,8],"complete":false,"user_row_id":7,"final_assistant_row_id":8}}""", seq = 1),
        )
        assertEquals(2L, (s.items[1] as ChatItem.AssistantMessage).rowId)
        assertEquals(7L, (s.items[2] as ChatItem.UserMessage).rowId)
        // A user message that already has its row keeps it.
        val again = s.feed(ev("message.complete", """{"text":"","persisted_turn":{"row_ids":[9],"complete":false,"user_row_id":9}}""", seq = 2))
        assertEquals(7L, (again.items[2] as ChatItem.UserMessage).rowId)
    }

    @Test
    fun theEchoKeepsWhatWasSubmittedWhenItDiffers() {
        val plain = ChatReducer.onSubmitted(live, "hi", "u-l1", flair, promptText = "hi")
        assertNull((plain.items.single() as ChatItem.UserMessage).promptText)
        val withRef = ChatReducer.onSubmitted(live, "hi", "u-l1", flair, listOf("notes.txt"), promptText = "@file:notes.txt\n\nhi")
        assertEquals("@file:notes.txt\n\nhi", (withRef.items.single() as ChatItem.UserMessage).promptText)
    }

    // ---- side questions and background tasks ----------------------------------------------------

    @Test
    fun sideQuestionStartsPendingAndCompletesByTaskId() {
        val started = ChatReducer.onSideStarted(live, "t-1", SideKind.Question, "what time is it?")
        val pending = started.items.single() as ChatItem.SideNote
        assertEquals(ChatItem.SideNote("side-t-1", SideKind.Question, "what time is it?", "", pending = true), pending)
        assertEquals(AgentStatus.Idle, started.status)

        val done = started.feed(ev("btw.complete", """{"task_id":"t-1","text":"Noon.","question":"what time is it?"}""", seq = 4))
        assertEquals(ChatItem.SideNote("side-t-1", SideKind.Question, "what time is it?", "Noon.", pending = false), done.items.single())
        assertEquals(AgentStatus.Idle, done.status)

        val bg = ChatReducer.onSideStarted(live, "t-2", SideKind.Background, "index the repo")
            .feed(ev("background.complete", """{"task_id":"t-2","text":"Indexed 42 files."}"""))
        val note = bg.items.single() as ChatItem.SideNote
        assertEquals(SideKind.Background, note.kind)
        assertEquals("index the repo", note.question) // kept: background.complete carries no question
        assertEquals("Indexed 42 files.", note.text)
        assertFalse(note.pending)
    }

    @Test
    fun sideNoteFromAnotherClient() {
        // A completion for a task this app never started (another client, or the RPC result lost): appended anyway.
        val s = live.feed(
            ev("btw.complete", """{"task_id":"t-9","text":"Yes."}"""),
            ev("background.complete", """{"task_id":"t-8","text":"Done.","question":null}"""),
        )
        assertEquals(
            listOf(
                ChatItem.SideNote("side-t-9", SideKind.Question, null, "Yes.", pending = false),
                ChatItem.SideNote("side-t-8", SideKind.Background, null, "Done.", pending = false),
            ),
            s.items,
        )
        // A completion that beat the RPC result is not followed by a second, pending note.
        assertEquals(s.items, ChatReducer.onSideStarted(s, "t-9", SideKind.Question, "really?").items)
        // No task id: nothing to show.
        assertEquals(live, live.feed(ev("btw.complete", """{"text":"orphan"}""")))
    }

    @Test
    fun sideCompletionWithEmptyTextFallsBackToNoAnswer() {
        val started = ChatReducer.onSideStarted(live, "t-1", SideKind.Question, "anything?")
        val empty = started.feed(ev("btw.complete", """{"task_id":"t-1","text":""}"""))
        assertEquals(ChatItem.SideNote("side-t-1", SideKind.Question, "anything?", "No answer", pending = false), empty.items.single())
        val missing = live.feed(ev("background.complete", """{"task_id":"t-2"}"""))
        assertEquals("No answer", (missing.items.single() as ChatItem.SideNote).text)
    }

    @Test
    fun sideNoteAfterAStreamingReplyDoesNotCloseIt() {
        val streaming = live.feed(ev("message.start", seq = 1), ev("message.delta", """{"text":"Hel"}""", seq = 2))
        val withNote = ChatReducer.onSideStarted(streaming, "t-1", SideKind.Question, "side?")
        assertEquals(streaming.status, withNote.status)
        val s = withNote.feed(
            ev("message.delta", """{"text":"lo"}""", seq = 3),
            ev("btw.complete", """{"task_id":"t-1","text":"Side answer."}""", seq = 4),
            ev("message.delta", """{"text":"!"}""", seq = 5),
            ev("message.complete", """{"text":"Hello!"}""", seq = 6),
        )
        assertEquals(2, s.items.size)
        val reply = s.items[0] as ChatItem.AssistantMessage
        assertEquals("Hello!", reply.text)
        assertFalse(reply.streaming)
        assertEquals("Side answer.", (s.items[1] as ChatItem.SideNote).text)
        assertEquals(AgentStatus.Idle, s.status)
    }

    // ---- v0.9.0: connection requests ---------------------------------------------------------------------------

    private val connectionRequestJson = """
        {"op_id":"a442c3e92e99","seq":1,"deadline_at":1761010838.5,"timeout_seconds":300,"tool_call_id":"call_1",
         "targets":[{"name":"linear","kind":"connector","action":"connect","state":"pending","resolved":false},
                    {"name":"github","kind":"connector","action":"connect","state":"pending","resolved":false}]}
    """.trimIndent()

    private fun connectionUpdate(seq: Int, settled: Boolean, linear: String, github: String, settledBy: String? = null) = """
        {"op_id":"a442c3e92e99","seq":$seq,"deadline_at":1761010838.5,"settled":$settled,
         ${settledBy?.let { "\"settled_by\":\"$it\"," }.orEmpty()}
         "owner":{"type":"session","session_id":"$sid"},
         "targets":[{"name":"linear","kind":"connector","action":"connect","state":"$linear"},
                    {"name":"github","kind":"connector","action":"connect","state":"$github"}]}
    """.trimIndent()

    @Test
    fun connectionRequestBecomesACard() {
        val s = live.feed(
            ev("tool.start", """{"tool_id":"call_1","name":"manage_connections"}""", seq = 1),
            ev("connection.request", connectionRequestJson, seq = 2),
        )

        val card = s.items.last() as ChatItem.ConnectionRequest
        assertEquals("conn-a442c3e92e99", card.id)
        assertEquals("a442c3e92e99", card.opId)
        assertNull(card.owner)
        assertEquals(1761010838.5, card.deadlineAt, 0.0)
        assertEquals(listOf("linear", "github"), card.targets.map { it.name })
        assertEquals(listOf("pending", "pending"), card.targets.map { it.state })
        assertFalse(card.settled)
        assertNull(card.settledBy)
        assertEquals(AgentStatus.Connection, s.status)
        assertEquals("Waiting for a connection", s.status.label)
        assertEquals(StatusTone.Amber, s.status.tone)
        // The same request again (a replay) keeps one card.
        assertEquals(1, s.feed(ev("connection.request", connectionRequestJson, session = "")).items.count { it is ChatItem.ConnectionRequest })
        // A request without an op id is no card.
        assertEquals(live, live.feed(ev("connection.request", """{"seq":1,"deadline_at":1,"targets":[]}""")))
    }

    @Test
    fun connectionUpdateReplacesTargetsAndSettles() {
        val opened = live.feed(ev("connection.request", connectionRequestJson, seq = 1))

        val moved = opened.feed(ev("connection.update", connectionUpdate(2, settled = false, linear = "initiated", github = "pending"), seq = 2))
        val card = moved.items.single() as ChatItem.ConnectionRequest
        assertEquals(listOf("initiated", "pending"), card.targets.map { it.state })
        assertEquals("""{"type":"session","session_id":"$sid"}""", card.owner.toString())
        assertFalse(card.settled)
        assertEquals(AgentStatus.Connection, moved.status)

        // An older frame of the operation (by its own seq) moves nothing.
        val stale = moved.feed(ev("connection.update", connectionUpdate(1, settled = false, linear = "pending", github = "pending"), seq = 3))
        assertEquals(card, stale.items.single())

        // An update for an operation with no card (another session's broadcast) is ignored.
        val other = moved.feed(ev("connection.update", connectionUpdate(5, true, "skipped", "skipped").replace("a442c3e92e99", "ffff"), session = ""))
        assertEquals(moved.items, other.items)

        val settled = moved.feed(
            ev("connection.update", connectionUpdate(4, settled = true, linear = "connected", github = "skipped", settledBy = "all_resolved"), seq = 4),
        )
        val done = settled.items.single() as ChatItem.ConnectionRequest
        assertTrue(done.settled)
        assertEquals("all_resolved", done.settledBy)
        assertEquals(listOf("connected", "skipped"), done.targets.map { it.state })
        assertEquals(AgentStatus.Thinking("flair-1"), settled.status)
    }

    @Test
    fun connectionRequestSurvivesResume() {
        val opened = live.feed(ev("connection.request", connectionRequestJson, seq = 7))
        // A resume rebuilds the transcript from the stored history, which never holds the card ...
        val rebuilt = ChatReducer.onSessionBound(opened, "beef0001", null).copy(items = emptyList(), status = AgentStatus.Thinking("x"))
        // ... and its `pending_connection` snapshot brings it back, answerable, with the server's deadline.
        val restored = ChatReducer.onConnectionRequest(rebuilt, obj(connectionRequestJson))
        val card = restored.items.single() as ChatItem.ConnectionRequest
        assertEquals("a442c3e92e99", card.opId)
        assertFalse(card.settled)
        assertEquals(AgentStatus.Connection, restored.status)
        // The replayed event of the new live session finds the card already there.
        val replayed = restored.copy(liveSessionId = "beef0001").feed(ev("connection.request", connectionRequestJson, seq = 3, session = "beef0001"))
        assertEquals(1, replayed.items.size)
        // A snapshot of a card already shown (same live session) keeps it as it is.
        assertEquals(opened, ChatReducer.onConnectionRequest(opened, obj(connectionRequestJson)))
    }

    @Test
    fun turnErrorSettlesAnOpenConnectionCard() {
        val s = live.feed(
            ev("connection.request", connectionRequestJson, seq = 1),
            ev("error", """{"message":"boom"}""", seq = 2),
        )
        assertTrue((s.items.first() as ChatItem.ConnectionRequest).settled)
        assertEquals(AgentStatus.Idle, s.status)
    }
}
