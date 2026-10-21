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
    fun visionAnalyzeCarriesItsImageUrl() {
        val started = live.feed(
            ev(
                "tool.start",
                """{"tool_id":"v1","name":"vision_analyze","context":"what is on screen",
                   "args":{"image_url":"~/.hermes/screenshots/s1.png","question":"what is on screen"}}""",
                1,
            ),
        )
        assertEquals("~/.hermes/screenshots/s1.png", (started.items.single() as ChatItem.ToolCall).imageUrl)

        // Completion keeps it (its own args may be absent).
        val done = started.feed(ev("tool.complete", """{"tool_id":"v1","name":"vision_analyze","result":{"output":"a cat"}}""", 2))
        val tool = done.items.single() as ChatItem.ToolCall
        assertEquals("~/.hermes/screenshots/s1.png", tool.imageUrl)
        assertEquals(ToolStatus.Done, tool.status)
        assertEquals("a cat", tool.output)

        // A completion with no start fills it from its own args; the raw string is kept as sent.
        val late = live.feed(
            ev(
                "tool.complete",
                """{"tool_id":"v2","name":"vision_analyze","args":{"image_url":"file:///root/.hermes/images/a.png"},"result":{"output":"x"}}""",
                3,
            ),
        )
        assertEquals("file:///root/.hermes/images/a.png", (late.items.single() as ChatItem.ToolCall).imageUrl)

        // Only vision_analyze: another tool's image_url arg, or a missing or non-string one, carries nothing.
        val other = live.feed(
            ev("tool.start", """{"tool_id":"b1","name":"browser_vision","args":{"image_url":"/tmp/x.png"}}""", 4),
            ev("tool.start", """{"tool_id":"v3","name":"vision_analyze","args":{"question":"?"}}""", 5),
            ev("tool.start", """{"tool_id":"v4","name":"vision_analyze","args":{"image_url":42}}""", 6),
        )
        assertEquals(listOf<String?>(null, null, null), other.items.filterIsInstance<ChatItem.ToolCall>().map { it.imageUrl })
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
    fun queuedEchoFlipsOnNextTurn() {
        // A turn is streaming; the user sends mid-turn and the server queues it.
        val running = ChatReducer.onSubmitAccepted(ChatReducer.onSubmitted(live, "first", "u-l1", flair), "u-l1", 1L)
            .feed(ev("message.start", seq = 1), ev("message.delta", """{"text":"Once"}""", seq = 2))
        val queued = ChatReducer.onSubmitAccepted(
            ChatReducer.onSubmitted(running, "then this", "u-l2", flair, queued = true), "u-l2", null, status = "queued",
        )
        fun echo(s: ChatState) = s.items.first { it.id == "u-l2" } as ChatItem.UserMessage
        assertTrue(echo(queued).queued)
        assertEquals("a queued send leaves the live status alone", running.status, queued.status)

        // The live turn goes on: its tool row lands above the queued echo, which stays at the bottom.
        val tool = queued.feed(ev("tool.start", """{"tool_id":"t1","name":"terminal"}""", seq = 3))
        assertEquals("u-l2", tool.items.last().id)
        assertTrue(echo(tool).queued)

        // The live turn ends: its row goes to its own message, not to the queued echo.
        val ended = tool.feed(ev("message.complete", """{"text":"done","persisted_turn":{"user_row_id":1}}""", seq = 4))
        assertTrue(echo(ended).queued)
        assertNull(echo(ended).rowId)

        // The queued turn starts: the echo is sent; its reply lands below it and binds its row.
        val started = ended.feed(ev("message.start", seq = 5))
        assertFalse(echo(started).queued)
        assertEquals("u-l2", started.items[started.items.size - 2].id)
        val done = started.feed(
            ev("message.complete", """{"text":"ok","persisted_turn":{"user_row_id":7,"final_assistant_row_id":8}}""", seq = 6),
        )
        assertEquals(7L, echo(done).rowId)
        assertEquals(1L, (done.items.first { it.id == "u-l1" } as ChatItem.UserMessage).rowId)
    }

    @Test
    fun queuedEchoesFlipOneTurnAtATime() {
        val running = live.feed(ev("message.start", seq = 1))
        val two = listOf("u-l1", "u-l2").fold(running) { s, id ->
            ChatReducer.onSubmitAccepted(ChatReducer.onSubmitted(s, id, id, flair, queued = true), id, null, status = "queued")
        }
        val next = two.feed(ev("message.complete", """{"text":"a"}""", seq = 2), ev("message.delta", """{"text":"b"}""", seq = 3))
        val queued = next.items.filterIsInstance<ChatItem.UserMessage>().associate { it.id to it.queued }
        assertEquals(mapOf("u-l1" to false, "u-l2" to true), queued)
        assertEquals("the waiting echo stays below the reply that streams", "u-l2", next.items.last().id)
        val third = next.feed(ev("message.complete", """{"text":"b"}""", seq = 4), ev("message.start", seq = 5))
        assertTrue(third.items.filterIsInstance<ChatItem.UserMessage>().none { it.queued })
    }

    @Test
    fun aQueuedSendThatStartedAtOnceIsNotQueued() {
        val s = ChatReducer.onSubmitted(live.feed(ev("message.start", seq = 1)), "hi", "u-l1", flair, queued = true)
        val accepted = ChatReducer.onSubmitAccepted(s, "u-l1", 3L, status = "streaming")
        assertFalse((accepted.items.first { it.id == "u-l1" } as ChatItem.UserMessage).queued)
    }

    @Test
    fun anInterruptedTurnDropsTheQueue() {
        // Stop purges the server's queue: the message stays in the transcript but never runs.
        val running = live.feed(ev("message.start", seq = 1))
        val queued = ChatReducer.onSubmitAccepted(
            ChatReducer.onSubmitted(running, "later", "u-l1", flair, queued = true), "u-l1", null, status = "queued",
        )
        val stopped = queued.feed(ev("message.complete", """{"text":"","status":"interrupted"}""", seq = 2))
        val echo = stopped.items.first { it.id == "u-l1" } as ChatItem.UserMessage
        assertFalse(echo.queued)
        assertTrue("it never ran: Not sent", echo.failed)
        assertFalse(stopped.queuedTurnNext)
    }

    @Test
    fun aFailedTurnFailsTheQueuedEchoes() {
        val queued = ChatReducer.onSubmitAccepted(
            ChatReducer.onSubmitted(live.feed(ev("message.start", seq = 1)), "later", "u-l1", flair, queued = true),
            "u-l1", null, status = "queued",
        )
        val failed = queued.feed(ev("error", """{"message":"boom"}""", seq = 2))
        val echo = failed.items.first { it.id == "u-l1" } as ChatItem.UserMessage
        assertTrue(echo.failed)
        assertFalse(echo.queued)
    }

    @Test
    fun anUnknownSubmitStatusKeepsTheEchoQueued() {
        val s = ChatReducer.onSubmitted(live.feed(ev("message.start", seq = 1)), "hi", "u-l1", flair, queued = true)
        assertTrue((ChatReducer.onSubmitAccepted(s, "u-l1", null, status = null).items.first { it.id == "u-l1" } as ChatItem.UserMessage).queued)
    }

    @Test
    fun aTurnStartWithNoQueuedEchoClearsTheFlag() {
        val s = live.copy(queuedTurnNext = true).feed(ev("message.start", seq = 1))
        assertFalse(s.queuedTurnNext)
        // A later send's echo is not taken for that stale turn.
        val later = ChatReducer.onSubmitted(s, "hi", "u-l1", flair, queued = true).feed(ev("message.delta", """{"text":"x"}""", seq = 2))
        assertTrue((later.items.first { it.id == "u-l1" } as ChatItem.UserMessage).queued)
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

    // ---- interrupted tools, the failure predicate and orphan notices ----------------------------

    private fun completed(result: String, name: String = "terminal"): ChatItem.ToolCall =
        live.feed(
            ev("tool.start", """{"tool_id":"t1","name":"$name"}""", 1),
            ev("tool.complete", """{"tool_id":"t1","name":"$name","result":$result}""", 2),
        ).items.single() as ChatItem.ToolCall

    @Test
    fun interruptedTerminalToolIsInterruptedNotFailed() {
        val tool = completed("""{"output":"partial line 1\npartial line 2\n[Command interrupted]","exit_code":130,"error":null}""")
        assertEquals(ToolStatus.Interrupted, tool.status)
        // The marker line is replaced by the row's label: only the partial output is kept.
        assertEquals("partial line 1\npartial line 2", tool.output)
        assertEquals(130, tool.exitCode)
        // Trailing whitespace after the marker is allowed.
        assertEquals(ToolStatus.Interrupted, completed("""{"output":"x\n[Command interrupted]  \n","exit_code":130,"error":null}""").status)
        // Exit 130 without the marker is an ordinary failure; the marker with another exit code too.
        assertEquals(ToolStatus.Failed, completed("""{"output":"killed","exit_code":130,"error":null}""").status)
        assertEquals(ToolStatus.Failed, completed("""{"output":"x\n[Command interrupted]","exit_code":1,"error":null}""").status)
    }

    @Test
    fun successFalseIsAFailure() {
        val tool = completed("""{"error": "Text is required", "success": false}""", name = "text_to_speech")
        assertEquals(ToolStatus.Failed, tool.status)
        // success == false alone (no error, no exit code) is still a failure.
        assertEquals(ToolStatus.Failed, completed("""{"success": false}""", name = "text_to_speech").status)
    }

    @Test
    fun exitCodeOnlyIsAFailure() {
        val tool = completed("""{"output": "ls: cannot access 'nope': No such file or directory", "exit_code": 2, "error": null}""")
        assertEquals(ToolStatus.Failed, tool.status)
        assertEquals(2, tool.exitCode)
    }

    @Test
    fun errorStringIsAFailureAndFalsyErrorsAreNot() {
        val notFound = completed(
            """{"content": "", "total_lines": 0, "error": "File not found: /tmp/nope.txt", "not_found": true}""",
            name = "read_file",
        )
        assertEquals(ToolStatus.Failed, notFound.status)
        // Python truthiness: an empty string or false error is no error.
        assertEquals(ToolStatus.Done, completed("""{"output":"ok","exit_code":0,"error":""}""").status)
        assertEquals(ToolStatus.Done, completed("""{"output":"ok","error":false}""").status)
    }

    @Test
    fun emptyFileReadIsASuccess() {
        val tool = completed("""{"content": "", "total_lines": 0, "file_size": 0, "not_found": false}""", name = "read_file")
        assertEquals(ToolStatus.Done, tool.status)
    }

    private fun history(vararg rows: String): List<ChatItem> = ChatReducer.fromHistory(
        HermesJson.decodeFromString(HistoryResult.serializer(), """{"messages":[${rows.joinToString(",")}]}""").messages,
    )

    @Test
    fun orphanNoticeAttachesToTheTool() {
        // replay_cleanup rewrites an interrupted side-effecting result as the notice; the history projection forwards
        // the result `content` of write_file / patch / skill_manage rows.
        val unknown = history(
            """{"role":"user","text":"write it","row_id":1}""",
            """{"role":"tool","name":"write_file","context":"notes.md","tool_call_id":"c1","row_id":2,
               "content":"[Orphan recovery: interrupted side-effecting tool may have executed; its effect is UNKNOWN. Inspect state before retrying.]"}""",
        )
        assertEquals(2, unknown.size)
        val written = unknown[1] as ChatItem.ToolCall
        assertEquals(ToolStatus.Interrupted, written.status)
        assertEquals("May have partially run — check before retrying", written.orphanNote)

        // The dangling-tail UNKNOWN wording maps the same way.
        val dangling = history(
            """{"role":"tool","name":"patch","tool_call_id":"c2",
               "content":"[Orphan recovery: this tool may have executed before Hermes stopped; its effect is UNKNOWN. Inspect current state before retrying.]"}""",
        ).single() as ChatItem.ToolCall
        assertEquals("May have partially run — check before retrying", dangling.orphanNote)

        // The read-only wording (a read-only call in an interrupted side-effecting block).
        val readOnly = history(
            """{"role":"tool","name":"patch","tool_call_id":"c3","content":"[Orphan recovery: interrupted read-only tool did not complete.]"}""",
        ).single() as ChatItem.ToolCall
        assertEquals(ToolStatus.Interrupted, readOnly.status)
        assertEquals("Did not complete", readOnly.orphanNote)
        val dryWording = history(
            """{"role":"tool","name":"skill_manage","tool_call_id":"c4","content":"[Orphan recovery: this read-only tool did not complete and had no effect.]"}""",
        ).single() as ChatItem.ToolCall
        assertEquals("Did not complete", dryWording.orphanNote)

        // Only the result content carries a notice, not the row's text.
        val viaText = history(
            """{"role":"tool","name":"terminal","tool_call_id":"c6","text":"[Orphan recovery: interrupted read-only tool did not complete.]"}""",
        ).single() as ChatItem.ToolCall
        assertEquals(ToolStatus.Done, viaText.status)
        assertNull(viaText.orphanNote)

        // Ordinary tool rows are unchanged.
        val plain = history("""{"role":"tool","name":"write_file","tool_call_id":"c5","content":"Wrote 12 bytes"}""").single() as ChatItem.ToolCall
        assertEquals(ToolStatus.Done, plain.status)
        assertNull(plain.orphanNote)
    }

    @Test
    fun orphanNoticeTextInAMessageIsNotANotice() {
        // Anyone can type the prefix: a user or assistant message starting with it stays a bubble and changes no tool.
        val notice = "[Orphan recovery: interrupted side-effecting tool may have executed; its effect is UNKNOWN. Inspect state before retrying.]"
        val items = history(
            """{"role":"user","text":"run it","row_id":1}""",
            """{"role":"tool","name":"terminal","tool_call_id":"c1","row_id":2}""",
            """{"role":"assistant","text":"$notice","row_id":3}""",
            """{"role":"user","text":"$notice","row_id":4}""",
        )
        assertEquals(listOf("h-1", "h-2", "h-3", "h-4"), items.map { it.id })
        val tool = items[1] as ChatItem.ToolCall
        assertEquals(ToolStatus.Done, tool.status)
        assertNull(tool.orphanNote)
        assertEquals(notice, (items[2] as ChatItem.AssistantMessage).text)
        assertEquals(notice, (items[3] as ChatItem.UserMessage).text)
    }

    @Test
    fun stopMarksRunningToolsInterruptedAtTurnEnd() {
        val running = live.feed(
            ev("message.start", seq = 1),
            ev("tool.start", """{"tool_id":"t1","name":"terminal","context":"sleep 60"}""", 2),
            ev("tool.start", """{"tool_id":"t2","name":"read_file"}""", 3),
            ev("tool.complete", """{"tool_id":"t2","name":"read_file","result":{"content":"x"}}""", 4),
        )
        val stopped = running.feed(ev("message.complete", """{"text":"","status":"interrupted"}""", 5))
        val tools = stopped.items.filterIsInstance<ChatItem.ToolCall>()
        assertEquals(listOf(ToolStatus.Interrupted, ToolStatus.Done), tools.map { it.status })
        assertNull(tools[0].output)

        // A normal completion leaves a (stray) running tool alone; an error turn end still fails it.
        val normal = running.feed(ev("message.complete", """{"text":"done","status":"complete"}""", 5))
        assertEquals(ToolStatus.Running, normal.items.filterIsInstance<ChatItem.ToolCall>()[0].status)
        val failed = running.feed(ev("error", """{"message":"boom"}""", 5))
        assertEquals(ToolStatus.Failed, failed.items.filterIsInstance<ChatItem.ToolCall>()[0].status)

        // Only this turn's tools: a stray running row from an earlier turn (above the last user message) is left alone,
        // and a queued echo below the live turn does not hide the turn's own tools.
        val earlier = live.feed(ev("tool.start", """{"tool_id":"old","name":"terminal"}""", 1))
        val turn = ChatReducer.onSubmitted(earlier, "go", "u-l1", flair).feed(
            ev("message.start", seq = 2),
            ev("tool.start", """{"tool_id":"t1","name":"terminal"}""", 3),
        )
        val withQueued = ChatReducer.onSubmitted(turn, "later", "u-l2", flair, queued = true)
        val ended = withQueued.feed(ev("message.complete", """{"text":"","status":"interrupted"}""", 4))
        assertEquals(
            mapOf("old" to ToolStatus.Running, "t1" to ToolStatus.Interrupted),
            ended.items.filterIsInstance<ChatItem.ToolCall>().associate { it.id to it.status },
        )
    }

    @Test
    fun onlyTheFinalInterruptMarkerIsStripped() {
        // The marker quoted earlier in the output is data and stays; only the executor's final line goes.
        val tool = completed("""{"output":"grep hit: [Command interrupted]\n[Command interrupted]\nmore\n[Command interrupted]","exit_code":130,"error":null}""")
        assertEquals(ToolStatus.Interrupted, tool.status)
        assertEquals("grep hit: [Command interrupted]\n[Command interrupted]\nmore", tool.output)
        // A marker that is not the last line is not the interrupt shape.
        assertEquals(ToolStatus.Failed, completed("""{"output":"[Command interrupted]\nafter","exit_code":130,"error":null}""").status)
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

    // ---- v0.9.0: todo checklists --------------------------------------------------------------------------------

    private fun todosJson(revision: Int, vararg entries: String) =
        """{"todos":[${entries.joinToString(",")}],"revision":$revision}"""

    private fun todo(id: String, status: String, parent: String? = null, content: String = "Task $id") =
        """{"id":"$id","content":"$content","status":"$status"${parent?.let { ",\"parent\":\"$it\"" } ?: ""}}"""

    @Test
    fun todoUpdatedRendersAChecklist() {
        val s = live.feed(
            ev("message.start", seq = 1),
            ev("tool.start", """{"tool_id":"call_t","name":"todo_list"}""", seq = 2),
            ev(
                "todo.updated",
                todosJson(1, todo("1", "completed"), todo("2", "in_progress"), todo("2a", "pending", parent = "2"), todo("3", "weird")),
                seq = 3,
            ),
        )

        val card = s.items.last() as ChatItem.Todos
        assertEquals(TODOS_ID, card.id)
        assertEquals(1, card.revision)
        assertEquals(
            listOf(
                TodoEntry("1", "Task 1", TodoStatus.Completed),
                TodoEntry("2", "Task 2", TodoStatus.InProgress),
                TodoEntry("2a", "Task 2a", TodoStatus.Pending, parent = "2"),
                TodoEntry("3", "Task 3", TodoStatus.Pending), // an unknown status reads as pending
            ),
            card.todos,
        )
        // A newer snapshot replaces the card in place: it keeps its position below later rows.
        val later = s.feed(
            ev("tool.complete", """{"tool_id":"call_t","name":"todo_list","result":{}}""", seq = 4),
            ev("message.start", seq = 5),
            ev("todo.updated", todosJson(2, todo("1", "completed"), todo("2", "cancelled")), seq = 6),
        )
        assertEquals(listOf("call_t", TODOS_ID, "a-5"), later.items.map { it.id }) // the empty a-1 bubble was dropped
        val replaced = later.items[1] as ChatItem.Todos
        assertEquals(2, replaced.revision)
        assertEquals(listOf(TodoStatus.Completed, TodoStatus.Cancelled), replaced.todos.map { it.status })
        // Malformed snapshots never throw: no list is no change; an entry without an id is skipped.
        assertEquals(later, later.feed(ev("todo.updated", """{"revision":9}""")))
        assertEquals(later, later.feed(ev("todo.updated", """{"todos":"nope","revision":9}""")))
        val sparse = later.feed(ev("todo.updated", """{"todos":[{"content":"x"},7,${todo("4", "pending")}],"revision":"x3"}"""))
        assertEquals(later.items, sparse.items) // an unreadable revision is a malformed snapshot: ignored
        val skipped = later.feed(ev("todo.updated", """{"todos":[{"content":"x"},7,${todo("4", "pending")}],"revision":3}"""))
        assertEquals(listOf("4"), (skipped.items[1] as ChatItem.Todos).todos.map { it.id })
    }

    @Test
    fun staleTodoRevisionIsIgnored() {
        val s = live.feed(ev("todo.updated", todosJson(3, todo("1", "in_progress")), seq = 1))
        val stale = s.feed(ev("todo.updated", todosJson(2, todo("1", "pending"), todo("2", "pending")), seq = 2))
        assertEquals(s.items, stale.items)
        // An equal revision replaces (the same snapshot, from the event and the tool row).
        val same = s.feed(ev("todo.updated", todosJson(3, todo("1", "completed")), seq = 3))
        assertEquals(TodoStatus.Completed, (same.items.single() as ChatItem.Todos).todos.single().status)
    }

    @Test
    fun toolCompleteMirrorsTodos() {
        val s = live.feed(
            ev("tool.start", """{"tool_id":"call_t","name":"todo_list"}""", seq = 1),
            ev(
                "tool.complete",
                """{"tool_id":"call_t","name":"todo_list","result":{"todos":[],"revision":1,"summary":{"total":2}},
                    "todos":[${todo("1", "pending")},${todo("1a", "completed", parent = "1")}],"revision":1}""",
                seq = 2,
            ),
        )
        assertEquals(listOf("call_t", TODOS_ID), s.items.map { it.id })
        assertEquals(ToolStatus.Done, (s.items.first() as ChatItem.ToolCall).status)
        val card = s.items.last() as ChatItem.Todos
        assertEquals(listOf("1", "1a"), card.todos.map { it.id })
        assertEquals("1", card.todos[1].parent)
        // The `todo.updated` that follows at the same revision keeps one card.
        val event = s.feed(ev("todo.updated", todosJson(1, todo("1", "pending"), todo("1a", "completed", parent = "1")), seq = 3))
        assertEquals(s.items, event.items)
        // A tool.complete without todos (any other tool) leaves the card alone.
        val other = event.feed(ev("tool.complete", """{"tool_id":"call_x","name":"terminal","result":{"exit_code":0}}""", seq = 4))
        assertEquals(card, other.items.single { it is ChatItem.Todos })
    }

    @Test
    fun emptyTodosAtRevisionOneClearsTheList() {
        val s = live.feed(
            ev("todo.updated", todosJson(1, todo("1", "pending")), seq = 1),
            ev("message.start", seq = 2),
        )
        val cleared = s.feed(ev("todo.updated", todosJson(2), seq = 3))
        assertTrue(cleared.items.none { it is ChatItem.Todos })
        // The first snapshot can be the clear itself: nothing to show.
        assertEquals(live.items, live.feed(ev("todo.updated", todosJson(1), seq = 1)).items)
    }

    @Test
    fun resumeTodoStateSeedsTheCard() {
        val history = ChatReducer.fromHistory(HermesJson.decodeFromString(HistoryResult.serializer(), historyJson).messages)
        val resumed = live.copy(items = history)
        val seeded = ChatReducer.onTodoState(resumed, obj(todosJson(4, todo("1", "in_progress"))))
        assertEquals(history.map { it.id } + TODOS_ID, seeded.items.map { it.id })
        assertEquals(4, (seeded.items.last() as ChatItem.Todos).revision)
        // The snapshot is authoritative: it replaces a carried card even at a lower revision.
        val carried = live.copy(items = history + ChatItem.Todos(revision = 6, todos = listOf(TodoEntry("9", "old", TodoStatus.Pending))))
        val replaced = ChatReducer.onTodoState(carried, obj(todosJson(4, todo("1", "in_progress"))))
        assertEquals(listOf("1"), (replaced.items.last() as ChatItem.Todos).todos.map { it.id })
        // No snapshot keeps the carried card; a snapshot that cleared the list removes it.
        assertEquals(carried, ChatReducer.onTodoState(carried, null))
        assertTrue(ChatReducer.onTodoState(carried, obj(todosJson(7))).items.none { it is ChatItem.Todos })
    }
}
