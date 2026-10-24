package app.tsukaima.domain.livevoice

import app.tsukaima.domain.chat.AgentStatus
import app.tsukaima.domain.chat.ChatItem
import app.tsukaima.domain.chat.ChatState
import app.tsukaima.domain.chat.ToolStatus
import app.tsukaima.domain.voice.LiveInfo
import app.tsukaima.domain.voice.VoicePhase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveVoiceControllerTest {
    private val scopes = mutableListOf<CoroutineScope>()

    private val waitingSay = "Hermes needs you to answer something on screen."
    private val waitingThink = "Hermes is waiting for the user to answer a request in the app."
    private val noResult = "Hermes finished that request without a spoken result."
    private val nudge = "The user has finished speaking. Respond now to what they said."

    private class Rig(
        val controller: LiveVoiceController,
        val transport: FakeLiveTransport,
        val chat: MutableStateFlow<ChatState>,
        /** "send:<prompt>" and "interrupt", in call order. */
        val log: MutableList<String>,
        val sends: MutableList<Pair<String, String>>,
        val ended: MutableList<Pair<String, Int>>,
    ) {
        var clock = 0L
        val state get() = controller.state.value
        fun status(s: AgentStatus) = chat.update { it.copy(status = s) }
        fun add(item: ChatItem, status: AgentStatus = chat.value.status) =
            chat.update { it.copy(items = it.items + item, status = status) }
        fun replace(item: ChatItem, status: AgentStatus = chat.value.status) =
            chat.update { c -> c.copy(items = c.items.map { if (it.id == item.id) item else it }, status = status) }

        private fun ofType(type: String) = transport.sent.filter { it["type"] == JsonPrimitive(type) }
        private fun JsonObject.content() = (this["content"] as JsonPrimitive).content
        val says get() = ofType("session.commentary.append").map { it.content() }
        val thinks get() = ofType("session.thinking.append").map { it.content() }
        val sayEvents get() = ofType("session.commentary.append")
    }

    /** Runs [body] and cancels the controllers' scopes before runTest drains (the meter loops forever). */
    private fun liveTest(body: suspend TestScope.() -> Unit) = runTest {
        try {
            body()
        } finally {
            scopes.forEach { it.cancel() }
        }
    }

    private fun TestScope.rig(
        started: Boolean = true,
        sendResult: Boolean = true,
        transport: FakeLiveTransport = FakeLiveTransport(),
        history: List<ChatItem> = listOf(ChatItem.UserMessage("u0", "earlier")),
        unconfined: Boolean = false,
    ): Rig {
        val chat = MutableStateFlow(ChatState(items = history))
        val log = mutableListOf<String>()
        val sends = mutableListOf<Pair<String, String>>()
        val ended = mutableListOf<Pair<String, Int>>()
        // unconfined: collectors resume inline inside the chat update, as on the app's Main.immediate.
        val dispatcher =
            if (unconfined) UnconfinedTestDispatcher(testScheduler) else StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher + SupervisorJob()).also { scopes += it }
        var n = 0
        val c = LiveVoiceController(
            transport = transport,
            scope = scope,
            chat = chat,
            voiceName = "marin",
            send = { prompt, context ->
                log += "send:$prompt"
                sends += prompt to context
                // As ChatViewModel.sendLive: the spoken turn shows as a user bubble.
                if (sendResult) chat.update { it.copy(items = it.items + ChatItem.UserMessage("lu${++n}", prompt)) }
                sendResult
            },
            interrupt = { log += "interrupt" },
            onEnded = { reason, seconds -> ended += reason to seconds },
            now = { testScheduler.currentTime },
        )
        c.start(emptyList())
        runCurrent()
        val r = Rig(c, transport, chat, log, sends, ended)
        if (started) event(r, LiveEvent.Started("s1"))
        return r
    }

    private fun TestScope.event(r: Rig, e: LiveEvent) {
        r.transport.emit(e)
        runCurrent()
    }

    private fun TestScope.user(r: Rig, text: String) = fragment(r, LiveSpeaker.User, text)
    private fun TestScope.voice(r: Rig, text: String) = fragment(r, LiveSpeaker.Assistant, text)

    private fun TestScope.fragment(r: Rig, speaker: LiveSpeaker, text: String) {
        r.clock += 100
        event(r, LiveEvent.Transcript(LiveFragment(speaker, text, r.clock, r.clock + 50)))
    }

    private fun TestScope.delegate(r: Rig, id: String) = event(r, LiveEvent.Delegation(id))

    private fun TestScope.settle() = runCurrent()

    private fun reply(id: String, text: String, streaming: Boolean, reasoning: String? = null) =
        ChatItem.AssistantMessage(id, text, streaming, reasoning)

    private fun tool(id: String, status: ToolStatus) =
        ChatItem.ToolCall(id, "terminal", null, null, status, null, null, null)

    private fun approval(id: String, answered: Boolean = false) =
        ChatItem.Approval(id, id, "rm -rf logs", "terminal", answered)

    /** One tool round of a v0.9.3 turn: an empty reasoning bubble, then its tool row. */
    private fun TestScope.toolRound(r: Rig, n: Int, text: String = "") {
        r.add(reply("b$n", text, streaming = true, reasoning = "hm"), AgentStatus.Thinking("hm"))
        settle()
        r.replace(reply("b$n", text, streaming = false, reasoning = "hm"))
        settle()
        r.add(tool("t$n", ToolStatus.Running), AgentStatus.Tool("terminal"))
        settle()
        r.replace(tool("t$n", ToolStatus.Done), AgentStatus.Thinking("hm"))
        settle()
    }

    @Test fun startWithoutStartedTimesOutAndClosesTransport() = liveTest {
        val r = rig(started = false)
        assertEquals(VoicePhase.Idle, r.state.phase)
        assertEquals("Connecting…", r.state.caption)
        assertEquals(LiveInfo("marin", 0), r.state.live)
        advanceTimeBy(LiveVoiceController.START_TIMEOUT_MS - 1)
        runCurrent()
        assertFalse(r.transport.closed)
        advanceTimeBy(1)
        runCurrent()
        assertTrue(r.transport.closed)
        assertEquals(listOf("start_failed" to 0), r.ended)
        assertEquals(VoicePhase.Idle, r.state.phase)
        assertEquals("start_failed", r.state.live?.ended)
        assertTrue(r.transport.sent.isEmpty())
    }

    @Test fun startFailureEndsAtOnce() = liveTest {
        val t = FakeLiveTransport().apply { startResult = Result.failure(LiveStartException("502")) }
        val r = rig(started = false, transport = t)
        assertTrue(t.closed)
        assertEquals(listOf("start_failed" to 0), r.ended)
        r.controller.stop()
        runCurrent()
        assertEquals(1, r.ended.size)
    }

    @Test fun startedThenDelegationSubmitsPromptAndContext() = liveTest {
        val r = rig()
        assertEquals(VoicePhase.Listening, r.state.phase)
        assertEquals("", r.state.caption)
        user(r, "what time is it")
        assertEquals("what time is it", r.state.heard)
        delegate(r, "d1")
        assertEquals(listOf("what time is it" to "User: what time is it"), r.sends)
        assertEquals(VoicePhase.Thinking, r.state.phase)
        assertEquals("marin", r.state.live?.voice)
        assertEquals("", r.state.heard)
        assertEquals(1, r.state.startIndex)
    }

    @Test fun replyIsSpokenSentenceBySentenceThenTailOnSettle() = liveTest {
        val r = rig()
        user(r, "what time is it")
        delegate(r, "d1")
        r.add(reply("a1", "It is noon. In Tokyo", streaming = true), AgentStatus.Replying)
        settle()
        assertEquals(
            listOf(
                """{"type":"session.commentary.append","event_id":"say_1","delegation_id":"d1",""" +
                    """"content":"It is noon."}""",
            ),
            r.sayEvents.map { it.toString() },
        )
        assertEquals(20, r.state.revealed["a1"])
        advanceTimeBy(1_000) // ticks do not repeat a sentence
        runCurrent()
        assertEquals(listOf("It is noon."), r.says)
        r.replace(reply("a1", "It is noon. In Tokyo", streaming = false), AgentStatus.Idle)
        settle()
        assertEquals(listOf("It is noon.", "In Tokyo"), r.says)
        assertEquals("say_2", (r.sayEvents[1]["event_id"] as JsonPrimitive).content)
        assertEquals(VoicePhase.Listening, r.state.phase) // delegation cleared
        assertEquals(20, r.state.revealed["a1"])
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(2, r.says.size)
        assertTrue(r.thinks.isEmpty())
    }

    @Test fun toolStartIsAThinkingAppendOncePerTool() = liveTest {
        val r = rig()
        user(r, "check the disk")
        delegate(r, "d1")
        r.status(AgentStatus.Tool("terminal"))
        settle()
        advanceTimeBy(1_000)
        r.status(AgentStatus.Thinking("hm"))
        settle()
        r.status(AgentStatus.Tool("terminal"))
        settle()
        assertEquals(listOf("Hermes is working: terminal. Not done yet."), r.thinks)
        assertEquals(
            """{"type":"session.thinking.append","event_id":"think_1","delegation_id":"d1",""" +
                """"content":"Hermes is working: terminal. Not done yet."}""",
            r.transport.sent.single().toString(),
        )
        r.status(AgentStatus.Tool("web_search"))
        settle()
        assertEquals("Hermes is working: web_search. Not done yet.", r.thinks.last())
        assertEquals(2, r.thinks.size)
    }

    @Test fun turnWithoutReplySaysSoQuietly() = liveTest {
        val r = rig()
        user(r, "restart it")
        delegate(r, "d1")
        r.status(AgentStatus.Thinking("hm"))
        settle()
        assertEquals(VoicePhase.Thinking, r.state.phase)
        r.status(AgentStatus.Idle)
        settle()
        assertEquals(listOf(noResult), r.thinks)
        assertTrue(r.says.isEmpty())
        assertEquals(VoicePhase.Listening, r.state.phase)
    }

    @Test fun turnNeverSeenRunningSettlesAfterTheGrace() = liveTest {
        val r = rig()
        user(r, "restart it")
        delegate(r, "d1")
        advanceTimeBy(LiveVoiceController.SUBMIT_SETTLE_GRACE_MS)
        runCurrent()
        assertTrue(r.thinks.isEmpty())
        advanceTimeBy(LiveVoiceController.TICK_MS * 2)
        runCurrent()
        assertEquals(listOf(noResult), r.thinks)
        assertEquals(VoicePhase.Listening, r.state.phase)
    }

    @Test fun newerDelegationStopsFeedingTheOlderOne() = liveTest {
        val r = rig()
        user(r, "what time is it")
        delegate(r, "d1")
        voice(r, "Checking.")
        r.add(reply("a1", "It is noon. In", streaming = true), AgentStatus.Replying)
        settle()
        assertEquals(listOf("It is noon."), r.says)
        r.status(AgentStatus.Idle) // the old turn was cut short, still streaming its bubble
        settle()
        user(r, "in Tokyo")
        delegate(r, "d2")
        assertEquals(listOf("send:what time is it", "send:in Tokyo"), r.log) // idle: nothing interrupted
        val d1Says = r.sayEvents.count { it["delegation_id"] == JsonPrimitive("d1") }
        r.replace(reply("a1", "It is noon. In Paris. Also", streaming = true), AgentStatus.Replying)
        settle()
        r.replace(reply("a1", "It is noon. In Paris. Also rain.", streaming = false))
        settle()
        r.add(reply("a2", "Tokyo is at nine. Late", streaming = true))
        settle()
        r.replace(reply("a2", "Tokyo is at nine. Late", streaming = false), AgentStatus.Idle)
        settle()
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(1, d1Says)
        assertEquals(d1Says, r.sayEvents.count { it["delegation_id"] == JsonPrimitive("d1") })
        assertEquals(listOf("It is noon.", "Tokyo is at nine.", "Late"), r.says)
        assertTrue(r.thinks.isEmpty())
    }

    @Test fun toolTurnSpeaksTheReplyAfterEmptyBubbles() = liveTest {
        val r = rig()
        user(r, "how full is the disk")
        delegate(r, "d1")
        toolRound(r, 1)
        toolRound(r, 2)
        r.add(reply("a3", "The disk is 40% full. All", streaming = true), AgentStatus.Replying)
        settle()
        r.replace(reply("a3", "The disk is 40% full. All good.", streaming = false), AgentStatus.Idle)
        settle()
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(listOf("The disk is 40% full.", "All good."), r.says)
        assertEquals(listOf("Hermes is working: terminal. Not done yet."), r.thinks)
        assertTrue(r.sayEvents.all { it["delegation_id"] == JsonPrimitive("d1") })
        assertEquals(VoicePhase.Listening, r.state.phase)
    }

    @Test fun toolTurnSpeaksEachBubbleInOrder() = liveTest {
        val r = rig()
        user(r, "how full is the disk")
        delegate(r, "d1")
        toolRound(r, 1, text = "Let me check. One moment")
        assertEquals(listOf("Let me check.", "One moment"), r.says) // bubble 1's tail as soon as it stopped
        r.add(reply("a2", "It is 40% full.", streaming = true), AgentStatus.Replying)
        settle()
        r.replace(reply("a2", "It is 40% full.", streaming = false), AgentStatus.Idle)
        settle()
        assertEquals(listOf("Let me check.", "One moment", "It is 40% full."), r.says)
        assertFalse(noResult in r.thinks)
    }

    @Test fun toolTurnWithOnlyEmptyBubblesSaysSoQuietly() = liveTest {
        val r = rig()
        user(r, "restart it")
        delegate(r, "d1")
        toolRound(r, 1)
        r.add(reply("b2", "", streaming = false, reasoning = "done"), AgentStatus.Idle)
        settle()
        assertTrue(r.says.isEmpty())
        assertEquals(noResult, r.thinks.last())
        assertEquals(VoicePhase.Listening, r.state.phase)
    }

    @Test fun boldSpanIsChunkedRawThenMadePlain() = liveTest {
        val r = rig()
        user(r, "anything to note")
        delegate(r, "d1")
        r.add(reply("a1", "**Note. Mo", streaming = true), AgentStatus.Replying)
        settle()
        assertEquals(listOf("Note."), r.says)
        r.replace(reply("a1", "**Note. More**", streaming = false), AgentStatus.Idle)
        settle()
        assertEquals(listOf("Note.", "More"), r.says)
    }

    @Test fun delegationWhileRunningInterruptsFirst() = liveTest {
        val r = rig()
        r.status(AgentStatus.Replying)
        settle()
        user(r, "never mind that, list files")
        delegate(r, "d1")
        assertEquals(listOf("interrupt", "send:never mind that, list files"), r.log)
    }

    @Test fun stopPhraseEndsInsteadOfDelegating() = liveTest {
        val r = rig()
        user(r, "stop")
        advanceTimeBy(LiveVoiceController.UTTERANCE_SETTLE_MS - 1)
        runCurrent()
        assertFalse("session.close" in r.transport.sentTypes)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf("session.close"), r.transport.sentTypes)
        assertTrue(r.ended.isEmpty()) // waits for session.closed
        advanceTimeBy(LiveVoiceController.CLOSE_TIMEOUT_MS)
        runCurrent()
        assertEquals("stop_phrase", r.ended.single().first)
        assertTrue(r.transport.closed)
        assertTrue(r.sends.isEmpty())

        val r2 = rig()
        user(r2, "Hey Hermes, stop.")
        delegate(r2, "d1")
        assertEquals(listOf("session.close"), r2.transport.sentTypes)
        assertTrue(r2.sends.isEmpty())
        event(r2, LiveEvent.Closed("close_requested", 2.0))
        assertEquals(listOf("close_requested" to 2), r2.ended)
    }

    @Test fun aLongerUtteranceIsNotAStop() = liveTest {
        val r = rig()
        user(r, "stop the container")
        advanceTimeBy(LiveVoiceController.UTTERANCE_SETTLE_MS * 2)
        runCurrent()
        assertTrue(r.transport.sent.isEmpty())
        assertTrue(r.ended.isEmpty())
    }

    @Test fun waitingCardIsSpokenOnce() = liveTest {
        val r = rig()
        user(r, "delete the logs")
        delegate(r, "d1")
        r.status(AgentStatus.Thinking("hm"))
        settle()
        r.add(approval("srq-1"), AgentStatus.Approval)
        settle()
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(VoicePhase.Approval, r.state.phase)
        assertEquals(listOf(waitingSay), r.says)
        assertEquals(listOf(waitingThink), r.thinks) // a paused turn is not a finished one
        r.status(AgentStatus.Tool("terminal"))
        settle()
        r.status(AgentStatus.Idle)
        settle()
        assertEquals(1, r.says.count { it == waitingSay })
    }

    @Test fun closedEventEndsWithUsage() = liveTest {
        val r = rig()
        event(r, LiveEvent.Closed("closed", 84.2))
        assertEquals(listOf("closed" to 84), r.ended)
        assertEquals(VoicePhase.Idle, r.state.phase)
        assertEquals("closed", r.state.live?.ended)
        assertTrue(r.transport.closed)
        r.controller.stop()
        runCurrent()
        assertEquals(1, r.ended.size)
        assertTrue(r.transport.sent.isEmpty())
    }

    @Test fun stopSendsCloseAndEndsOnClosed() = liveTest {
        val r = rig()
        advanceTimeBy(3_000)
        runCurrent()
        r.controller.stop()
        r.controller.stop()
        runCurrent()
        assertEquals(listOf("session.close"), r.transport.sentTypes)
        assertEquals("""{"type":"session.close"}""", r.transport.sent.single().toString())
        event(r, LiveEvent.Closed("close_requested", null))
        assertEquals(listOf("close_requested" to 3), r.ended)
    }

    @Test fun stopOnAClosedChannelEndsAtOnce() = liveTest {
        val r = rig()
        r.transport.channelOpen = false
        r.controller.stop("left_chat")
        assertEquals(listOf("left_chat" to 0), r.ended)
        assertTrue(r.transport.closed)
        assertEquals("left_chat", r.state.live?.ended)
    }

    @Test fun errorIsShownNotFatal() = liveTest {
        val r = rig()
        event(r, LiveEvent.Error("x", null))
        assertEquals("Live voice: x", r.state.error)
        assertEquals(VoicePhase.Listening, r.state.phase)
        assertTrue(r.ended.isEmpty())
        event(r, LiveEvent.Error("y", "context_injection_incomplete"))
        assertEquals("Live voice: x", r.state.error)
        assertFalse(r.transport.closed)
    }

    @Test fun muteAndTapAreForwarded() = liveTest {
        val r = rig()
        r.controller.tapIndicator()
        assertEquals(
            """{"type":"session.instructions.append","event_id":"instr_1","delegation_id":null,"content":"$nudge"}""",
            r.transport.sent.last().toString(),
        )
        r.controller.setMuted(true)
        runCurrent()
        assertTrue(r.transport.muted)
        assertEquals("""{"type":"session.input_audio.mute","event_id":"mute_2"}""", r.transport.sent.last().toString())
        assertEquals(VoicePhase.Muted, r.state.phase)
        assertTrue(r.state.muted)
        r.controller.setMuted(false)
        runCurrent()
        assertFalse(r.transport.muted)
        assertEquals(
            """{"type":"session.input_audio.unmute","event_id":"unmute_3"}""",
            r.transport.sent.last().toString(),
        )
        assertEquals(VoicePhase.Listening, r.state.phase)
    }

    @Test fun speakingShowsSpeaking() = liveTest {
        val r = rig()
        r.transport.speaking.value = true
        runCurrent()
        assertEquals(VoicePhase.Speaking, r.state.phase)
        r.transport.speaking.value = false
        runCurrent()
        assertEquals(VoicePhase.Listening, r.state.phase)
    }

    @Test fun timerCountsSeconds() = liveTest {
        val r = rig()
        advanceTimeBy(61_000)
        runCurrent()
        assertEquals(61, r.state.live?.seconds)
    }

    @Test fun captionShowsTheVoiceUtterance() = liveTest {
        val r = rig()
        voice(r, "Sure,")
        voice(r, " checking.")
        assertEquals("Sure, checking.", r.state.caption)
        user(r, "thanks")
        assertEquals("", r.state.caption)
        assertEquals("thanks", r.state.heard)
        voice(r, "You’re welcome.")
        assertEquals("You’re welcome.", r.state.caption)
        assertEquals("", r.state.heard)
        voice(r, " " + "x".repeat(300))
        assertEquals(240, r.state.caption.length)
    }

    @Test fun failedSubmitApologisesAndClears() = liveTest {
        val r = rig(sendResult = false)
        user(r, "what time is it")
        delegate(r, "d1")
        assertEquals(listOf("Sorry, I could not reach Hermes for that request."), r.says)
        assertEquals(JsonPrimitive("d1"), r.sayEvents.single()["delegation_id"])
        assertEquals(VoicePhase.Listening, r.state.phase)
    }

    @Test fun rebasedKeepsTheReplyCursor() = liveTest {
        val r = rig()
        user(r, "what time is it")
        delegate(r, "d1")
        r.add(reply("a1", "It is noon. In Tokyo", streaming = true), AgentStatus.Replying)
        settle()
        val before = r.chat.value.items
        // A reconnect rebuilt the transcript with new ids and one more old item at the top.
        r.chat.value = r.chat.value.copy(
            items = listOf(ChatItem.UserMessage("h0", "older"), ChatItem.UserMessage("h1", "earlier"),
                ChatItem.UserMessage("h2", "what time is it"), reply("h3", "It is noon. In Tokyo", streaming = true)),
        )
        r.controller.rebased(before)
        settle()
        assertEquals(2, r.state.startIndex)
        r.chat.update { c ->
            c.copy(items = c.items.dropLast(1) + reply("h3", "It is noon. In Tokyo", streaming = false),
                status = AgentStatus.Idle)
        }
        settle()
        assertEquals(listOf("It is noon.", "In Tokyo"), r.says)
        assertEquals(20, r.state.revealed["h3"])
        assertNull(r.state.error)
    }

    @Test fun connectionCardCountsAsWaiting() = liveTest {
        val r = rig()
        user(r, "connect my mail")
        delegate(r, "d1")
        r.status(AgentStatus.Thinking("hm"))
        settle()
        val card = ChatItem.ConnectionRequest("conn-1", "1", null, 0.0, emptyList(), settled = false, settledBy = null)
        r.add(card, AgentStatus.Connection)
        settle()
        assertEquals(VoicePhase.Approval, r.state.phase)
        assertEquals(listOf(waitingSay), r.says)
        assertEquals(listOf(waitingThink), r.thinks)
    }

    @Test fun twoApprovalsInOneDelegationAreBothAnnounced() = liveTest {
        val r = rig()
        user(r, "clean up")
        delegate(r, "d1")
        r.add(approval("srq-1"), AgentStatus.Approval)
        settle()
        r.replace(approval("srq-1", answered = true), AgentStatus.Tool("terminal"))
        settle()
        r.add(approval("srq-2"), AgentStatus.Approval)
        settle()
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(listOf(waitingSay, waitingSay), r.says)
    }

    @Test fun rebuildSeenBeforeRebasedDoesNotSpeakAgain() = liveTest {
        val r = rig(unconfined = true)
        user(r, "what time is it")
        delegate(r, "d1")
        r.add(reply("a1", "It is noon. In Tokyo", streaming = true), AgentStatus.Replying)
        settle()
        assertEquals(listOf("It is noon."), r.says)
        val before = r.chat.value.items
        // The collector sees the rebuilt items (new ids, one more old item on top) inside this update.
        r.chat.value = r.chat.value.copy(
            items = listOf(ChatItem.UserMessage("h0", "older"), ChatItem.UserMessage("h1", "earlier"),
                ChatItem.UserMessage("h2", "what time is it"), reply("h3", "It is noon. In Tokyo", streaming = true)),
        )
        r.controller.rebased(before)
        settle()
        assertEquals(listOf("It is noon."), r.says)
        r.chat.update { c ->
            c.copy(items = c.items.dropLast(1) + reply("h3", "It is noon. In Tokyo", streaming = false),
                status = AgentStatus.Idle)
        }
        settle()
        assertEquals(listOf("It is noon.", "In Tokyo"), r.says)
    }

    @Test fun stopDuringTheStartWindowEndsWithItsReason() = liveTest {
        val t = FakeLiveTransport().apply { startGate = CompletableDeferred() }
        val r = rig(started = false, transport = t)
        r.controller.stop("x")
        assertEquals(listOf("session.close"), t.sentTypes) // the channel is open: the server may hold a session
        // The connect the stop abandoned fails late: that is not a start failure any more.
        t.startResult = Result.failure(LiveStartException("502"))
        t.startGate?.complete(Unit)
        runCurrent()
        advanceTimeBy(LiveVoiceController.START_TIMEOUT_MS + LiveVoiceController.CLOSE_TIMEOUT_MS)
        runCurrent()
        assertEquals(listOf("x" to 0), r.ended)
        assertTrue(t.closed)
    }

    @Test fun midTurnResumeMergingBubblesDoesNotSpeakAgain() = liveTest {
        val r = rig(unconfined = true)
        user(r, "what time is it")
        delegate(r, "d1")
        val turnUser = r.chat.value.items.last()
        toolRound(r, 1)
        r.add(reply("b2", "Let me check.", streaming = true, reasoning = "hm"), AgentStatus.Replying)
        r.replace(reply("b2", "Let me check.", streaming = false, reasoning = "hm"))
        r.add(tool("t2", ToolStatus.Running), AgentStatus.Tool("terminal"))
        r.replace(tool("t2", ToolStatus.Done), AgentStatus.Thinking("hm"))
        r.add(reply("b3", " It is n", streaming = true), AgentStatus.Replying)
        settle()
        assertEquals(listOf("Let me check."), r.says)
        val before = r.chat.value.items
        // A resume while the turn runs: persisted rows (no tools, no blank bubbles) plus one in-flight bubble that
        // carries every delta of the turn so far.
        r.chat.value = r.chat.value.copy(
            items = listOf(
                ChatItem.UserMessage("h0", "earlier"),
                ChatItem.UserMessage("h1", (turnUser as ChatItem.UserMessage).text),
                reply("a-inflight", "Let me check. It is n", streaming = true),
            ),
        )
        r.controller.rebased(before)
        settle()
        assertEquals(listOf("Let me check."), r.says)
        r.replace(reply("a-inflight", "Let me check. It is noon.", streaming = true))
        settle()
        r.replace(reply("a-inflight", "Let me check. It is noon.", streaming = false), AgentStatus.Idle)
        settle()
        assertEquals(listOf("Let me check.", "It is noon."), r.says)
        assertEquals(VoicePhase.Listening, r.state.phase) // settled
        assertFalse(noResult in r.thinks)
    }

    @Test fun literalDoubleMarkersAreKept() = liveTest {
        val r = rig()
        user(r, "what is two to the tenth")
        delegate(r, "d1")
        r.add(reply("a1", "2**10 is 1024.", streaming = false), AgentStatus.Idle)
        settle()
        assertEquals(listOf("2**10 is 1024."), r.says)
    }

    @Test fun delegationBeforeStartedIsIgnored() = liveTest {
        val r = rig(started = false)
        user(r, "what time is it")
        delegate(r, "d1")
        assertTrue(r.sends.isEmpty())
        assertEquals(VoicePhase.Idle, r.state.phase)
    }
}
