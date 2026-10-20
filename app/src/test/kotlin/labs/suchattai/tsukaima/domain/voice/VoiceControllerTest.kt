package labs.suchattai.tsukaima.domain.voice

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import labs.suchattai.tsukaima.domain.chat.AgentStatus
import labs.suchattai.tsukaima.domain.chat.ChatItem
import labs.suchattai.tsukaima.domain.chat.ChatState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceControllerTest {
    private val scopes = mutableListOf<CoroutineScope>()

    @After fun tearDown() = scopes.forEach { it.cancel() }

    private val approvalLine = "That needs your approval. I’ve opened it in Tsukaima."

    private class Rig(
        val controller: VoiceController,
        val input: FakeSpeechInput,
        val output: FakeSpeechOutput,
        val chat: MutableStateFlow<ChatState>,
        val sent: MutableList<String>,
        val approvals: IntArray,
    ) {
        val state get() = controller.state.value
        fun status(s: AgentStatus) = chat.update { it.copy(status = s) }
        fun add(item: ChatItem, status: AgentStatus = chat.value.status) = chat.update { it.copy(items = it.items + item, status = status) }
        fun replace(item: ChatItem, status: AgentStatus = chat.value.status) =
            chat.update { c -> c.copy(items = c.items.map { if (it.id == item.id) item else it }, status = status) }
    }

    private fun TestScope.rig(
        mode: VoiceMode = VoiceMode.InApp,
        input: FakeSpeechInput = FakeSpeechInput(),
        output: FakeSpeechOutput = FakeSpeechOutput(),
        history: List<ChatItem> = listOf(ChatItem.UserMessage("u0", "earlier")),
        sendResult: Boolean = true,
    ): Rig {
        val chat = MutableStateFlow(ChatState(items = history))
        val sent = mutableListOf<String>()
        val approvals = intArrayOf(0)
        // Not backgroundScope: TestScope.advanceUntilIdle does not run background work, so the jobs get their own scope.
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob()).also { scopes += it }
        val c = VoiceController(input, output, scope, chat, { sent += it; sendResult }, mode, { approvals[0]++ }, { testScheduler.currentTime })
        c.start()
        advanceUntilIdle()
        return Rig(c, input, output, chat, sent, approvals)
    }

    private fun reply(id: String, text: String, streaming: Boolean) = ChatItem.AssistantMessage(id, text, streaming, null)

    /** The user says [text]; the chat starts the turn as ChatViewModel.sendText would. */
    private fun TestScope.say(r: Rig, text: String) {
        r.input.emit(SpeechEvent.Final(text))
        advanceUntilIdle()
        r.status(AgentStatus.Thinking("flair"))
        advanceUntilIdle()
    }

    @Test fun finalResultIsSentAndPhaseGoesThinking() = runTest {
        val r = rig()
        assertEquals(VoicePhase.Listening, r.state.phase)
        assertEquals(1, r.input.listenCount)
        r.input.emit(SpeechEvent.Final("restart it"))
        advanceUntilIdle()
        assertEquals(listOf("restart it"), r.sent)
        assertFalse(r.input.active)
        r.status(AgentStatus.Thinking("flair"))
        advanceUntilIdle()
        assertEquals(VoicePhase.Thinking, r.state.phase)
        assertEquals(1, r.state.startIndex)
    }

    @Test fun emptyFinalResultIsNotSent() = runTest {
        val r = rig()
        r.input.emit(SpeechEvent.Final("  "))
        advanceUntilIdle()
        assertTrue(r.sent.isEmpty())
        assertEquals(2, r.input.listenCount)
        assertTrue(r.input.active)
        assertEquals(VoicePhase.Listening, r.state.phase)
    }

    @Test fun partialTextIsHeard() = runTest {
        val r = rig()
        r.input.emit(SpeechEvent.Partial("rest"))
        advanceUntilIdle()
        assertEquals("rest", r.state.heard)
        r.input.emit(SpeechEvent.Final("restart it"))
        advanceUntilIdle()
        assertEquals("", r.state.heard)
    }

    @Test fun streamedReplyIsSpokenBySentenceAndRevealed() = runTest {
        val r = rig()
        say(r, "is it up")
        r.add(reply("a1", "Yes. No", streaming = true), AgentStatus.Replying)
        advanceUntilIdle()
        assertEquals(listOf("Yes."), r.output.spoken)
        assertEquals(4, r.state.revealed["a1"])
        assertEquals(VoicePhase.Speaking, r.state.phase)
        r.replace(reply("a1", "Yes. No.", streaming = false), AgentStatus.Idle)
        advanceUntilIdle()
        assertEquals(listOf("Yes."), r.output.spoken) // the queue waits for the first sentence
        r.output.finishSpeaking()
        advanceUntilIdle()
        assertEquals(listOf("Yes.", "No."), r.output.spoken)
        assertEquals(8, r.state.revealed["a1"])
    }

    @Test fun codeIsNotSpoken() = runTest {
        val r = rig(output = FakeSpeechOutput(instant = true))
        say(r, "clean up")
        val text = "Run:\n```\nrm -rf x\n```\nDone."
        r.add(reply("a1", text, streaming = false), AgentStatus.Idle)
        advanceUntilIdle()
        assertEquals(listOf("Run:", "Done."), r.output.spoken)
        assertEquals(text.length, r.state.revealed["a1"])
    }

    @Test fun afterTheReplyInAppListensAgainAssistIdles() = runTest {
        for (mode in VoiceMode.entries) {
            val r = rig(mode = mode)
            say(r, "status")
            r.add(reply("a1", "All good.", streaming = false), AgentStatus.Idle)
            advanceUntilIdle()
            assertEquals(VoicePhase.Speaking, r.state.phase)
            assertFalse(r.input.active)
            r.output.finishSpeaking()
            advanceUntilIdle()
            if (mode == VoiceMode.InApp) {
                assertEquals(VoicePhase.Listening, r.state.phase)
                assertEquals(2, r.input.listenCount)
                assertTrue(r.input.active)
            } else {
                assertEquals(VoicePhase.Idle, r.state.phase)
                assertEquals(1, r.input.listenCount)
                assertFalse(r.input.active)
            }
            r.controller.stop()
        }
    }

    @Test fun approvalCardSwitchesToApprovalPhase() = runTest {
        val r = rig()
        say(r, "delete the logs")
        r.add(ChatItem.Approval("srq-1", "srq-1", "rm -rf logs", "terminal", answered = false), AgentStatus.Approval)
        advanceUntilIdle()
        assertEquals(VoicePhase.Approval, r.state.phase)
        assertTrue(r.output.spoken.isEmpty())
        assertEquals(0, r.approvals[0])
    }

    @Test fun assistApprovalSpeaksAndHandsOffOnce() = runTest {
        val r = rig(mode = VoiceMode.Assist)
        say(r, "delete the logs")
        val card = ChatItem.Approval("srq-1", "srq-1", "rm -rf logs", "terminal", answered = false)
        r.add(card, AgentStatus.Approval)
        advanceUntilIdle()
        assertEquals(listOf(approvalLine), r.output.spoken)
        assertEquals(VoicePhase.Approval, r.state.phase)
        r.output.finishSpeaking()
        advanceUntilIdle()
        assertEquals(1, r.approvals[0])
        r.chat.update { it.copy(title = "again") } // a second emission with the same card
        advanceUntilIdle()
        assertEquals(listOf(approvalLine), r.output.spoken)
        assertEquals(1, r.approvals[0])
    }

    @Test fun muteReleasesTheMic() = runTest {
        val r = rig()
        assertTrue(r.input.active)
        r.controller.setMuted(true)
        advanceUntilIdle()
        assertFalse(r.input.active)
        assertTrue(r.state.muted)
        assertEquals(VoicePhase.Muted, r.state.phase)
        r.controller.setMuted(false)
        advanceUntilIdle()
        assertTrue(r.input.active)
        assertEquals(2, r.input.listenCount)
        assertEquals(VoicePhase.Listening, r.state.phase)
    }

    @Test fun bargeInStopsSpeechAndListens() = runTest {
        val r = rig()
        say(r, "tell me a story")
        r.add(reply("a1", "Once. Upon. A time.", streaming = false), AgentStatus.Idle)
        advanceUntilIdle()
        assertEquals(VoicePhase.Speaking, r.state.phase)
        r.controller.tapIndicator()
        advanceUntilIdle()
        assertEquals(1, r.output.stopped)
        assertEquals(listOf("Once."), r.output.spoken)
        assertEquals("Once. Upon. A time.".length, r.state.revealed["a1"])
        assertEquals(VoicePhase.Listening, r.state.phase)
        assertTrue(r.input.active)
    }

    @Test fun stopDuringSpeakingLeavesTheTurnRunning() = runTest {
        val r = rig()
        say(r, "explain")
        r.add(reply("a1", "First. Second", streaming = true), AgentStatus.Replying)
        advanceUntilIdle()
        assertEquals(listOf("First."), r.output.spoken)
        r.controller.stop()
        advanceUntilIdle()
        assertEquals(1, r.output.stopped)
        assertFalse(r.output.speaking)
        r.replace(reply("a1", "First. Second. Third.", streaming = false), AgentStatus.Idle)
        advanceUntilIdle()
        assertEquals(listOf("First."), r.output.spoken)
        assertEquals(listOf("explain"), r.sent)
        assertFalse(r.input.active)
        assertEquals(1, r.input.listenCount)
    }

    @Test fun offlineReleasesTheMicUntilIdle() = runTest {
        val r = rig()
        assertTrue(r.input.active)
        r.status(AgentStatus.Offline)
        advanceUntilIdle()
        assertFalse(r.input.active)
        assertEquals(VoicePhase.Idle, r.state.phase)
        r.status(AgentStatus.Idle)
        advanceUntilIdle()
        assertTrue(r.input.active)
        assertEquals(2, r.input.listenCount)
        assertEquals(VoicePhase.Listening, r.state.phase)
    }

    @Test fun unavailableInputSetsError() = runTest {
        val r = rig(input = FakeSpeechInput(available = false))
        assertEquals("Speech recognition isn’t available on this device", r.state.error)
        assertEquals(0, r.input.listenCount)
        assertFalse(r.input.active)
    }

    @Test fun retryableErrorRelistensAfterADelay() = runTest {
        val r = rig()
        r.input.emit(SpeechEvent.Error("busy", retry = true))
        runCurrent()
        assertEquals(1, r.input.listenCount)
        advanceTimeBy(301)
        runCurrent()
        assertEquals(2, r.input.listenCount)
        assertEquals(null, r.state.error)
    }

    @Test fun fatalErrorStopsListening() = runTest {
        val r = rig()
        r.input.emit(SpeechEvent.Error("Microphone permission denied", retry = false))
        advanceUntilIdle()
        assertEquals("Microphone permission denied", r.state.error)
        assertFalse(r.input.active)
        assertEquals(1, r.input.listenCount)
    }

    @Test fun unsentFinalKeepsListening() = runTest {
        val r = rig(sendResult = false)
        r.input.emit(SpeechEvent.Final("hello"))
        advanceUntilIdle()
        assertEquals(listOf("hello"), r.sent)
        assertEquals(2, r.input.listenCount)
        assertEquals(VoicePhase.Listening, r.state.phase)
    }

    @Test fun rebasedTranscriptIsRevealedNotSpokenAgain() = runTest {
        val r = rig(output = FakeSpeechOutput(instant = true))
        say(r, "which kernel")
        r.add(ChatItem.UserMessage("u-l1", "which kernel"))
        r.add(reply("a1", "Version 6.17.", streaming = false), AgentStatus.Idle)
        advanceUntilIdle()
        assertEquals(listOf("Version 6.17."), r.output.spoken)
        val before = r.chat.value.items // [u0 | u-l1, a1]: two voice-era items

        // A reconnect rebuilt the transcript from stored history: new ids, one more earlier item, a reply streaming.
        r.chat.update {
            it.copy(
                items = listOf(
                    ChatItem.UserMessage("h-1", "earlier"),
                    ChatItem.UserMessage("h-2", "older still"),
                    ChatItem.UserMessage("h-3", "which kernel"),
                    reply("h-4", "Version 6.17.", streaming = false),
                    reply("h-5", "And more", streaming = true),
                ),
                status = AgentStatus.Replying,
            )
        }
        r.controller.rebased(before)
        advanceUntilIdle()
        assertEquals(listOf("Version 6.17."), r.output.spoken)
        assertEquals(3, r.state.startIndex) // five items minus the two voice-era ones
        assertEquals("Version 6.17.".length, r.state.revealed["h-4"])
        assertEquals("And more".length, r.state.revealed["h-5"])

        r.replace(reply("h-5", "And more. Done.", streaming = false), AgentStatus.Idle)
        advanceUntilIdle()
        assertEquals("a reply present at the rebuild is never spoken", listOf("Version 6.17."), r.output.spoken)
        assertEquals("And more. Done.".length, r.state.revealed["h-5"])

        r.add(reply("a2", "New one.", streaming = false), AgentStatus.Idle)
        advanceUntilIdle()
        assertEquals(listOf("Version 6.17.", "New one."), r.output.spoken)
    }

    @Test fun rebasedClampsTheStartIndex() = runTest {
        val r = rig()
        r.chat.update { it.copy(items = emptyList()) }
        r.controller.rebased(listOf(ChatItem.UserMessage("u0", "earlier"), ChatItem.UserMessage("u1", "x")))
        advanceUntilIdle()
        assertEquals(0, r.state.startIndex)
    }

    /** Fails the current run with [event], then waits [ms]; true when a new run started by then. */
    private fun TestScope.failAndWait(r: Rig, event: SpeechEvent.Error, ms: Long): Boolean {
        val runs = r.input.listenCount
        r.input.emit(event)
        runCurrent()
        advanceTimeBy(ms)
        runCurrent()
        return r.input.listenCount > runs
    }

    @Test fun recogniserErrorsBackOffExponentially() = runTest {
        val r = rig()
        val busy = SpeechEvent.Error("busy", retry = true)
        assertTrue(failAndWait(r, busy, 300))
        assertFalse(failAndWait(r, busy, 599))
        advanceTimeBy(1); runCurrent()
        assertEquals(3, r.input.listenCount)
        assertFalse(failAndWait(r, busy, 1199))
        advanceTimeBy(1); runCurrent()
        assertFalse(failAndWait(r, busy, 2399))
        advanceTimeBy(1); runCurrent()
        assertEquals(5, r.input.listenCount)
        assertEquals(null, r.state.error)
    }

    @Test fun silenceKeepsTheShortDelayAndIsNeverFatal() = runTest {
        val r = rig()
        repeat(8) { assertTrue(failAndWait(r, SpeechEvent.Error("No speech heard", retry = true, silence = true), 300)) }
        assertEquals(null, r.state.error)
        assertEquals(VoicePhase.Listening, r.state.phase)
    }

    @Test fun fiveErrorsInARowAreFatalUntilTapped() = runTest {
        val r = rig()
        repeat(4) {
            r.input.emit(SpeechEvent.Error("busy", retry = true))
            advanceUntilIdle()
        }
        r.input.emit(SpeechEvent.Partial("he")) // speech in between resets the count
        advanceUntilIdle()
        r.input.emit(SpeechEvent.Error("busy", retry = true))
        advanceUntilIdle()
        assertEquals(null, r.state.error)
        repeat(4) {
            r.input.emit(SpeechEvent.Error("Speech recognition failed", retry = true))
            advanceUntilIdle()
        }
        assertEquals("Speech recognition failed", r.state.error)
        assertEquals(VoicePhase.Idle, r.state.phase)
        assertFalse(r.input.active)
        val runs = r.input.listenCount

        r.controller.tapIndicator()
        advanceUntilIdle()
        assertEquals(null, r.state.error)
        assertEquals(VoicePhase.Listening, r.state.phase)
        assertEquals(runs + 1, r.input.listenCount)
        r.input.emit(SpeechEvent.Error("busy", retry = true))
        advanceUntilIdle()
        assertEquals("the tap reset the count", null, r.state.error)
    }

    @Test fun inAppRestsAfterTwoMinutesWithoutAFinal() = runTest {
        val r = rig()
        advanceTimeBy(60_000)
        assertTrue(failAndWait(r, SpeechEvent.Error("No speech heard", retry = true, silence = true), 300))
        advanceTimeBy(59_700)
        r.input.emit(SpeechEvent.Error("No speech heard", retry = true, silence = true))
        advanceUntilIdle()
        assertEquals(VoicePhase.Idle, r.state.phase)
        assertFalse(r.input.active)
        assertEquals(null, r.state.error)

        r.controller.tapIndicator()
        advanceUntilIdle()
        assertEquals(VoicePhase.Listening, r.state.phase)
        assertTrue(r.input.active)
        advanceTimeBy(119_000)
        assertTrue("a fresh window after the tap", failAndWait(r, SpeechEvent.Error("No speech heard", retry = true, silence = true), 300))
    }

    @Test fun aFinalStartsANewListeningWindow() = runTest {
        val r = rig(output = FakeSpeechOutput(instant = true))
        advanceTimeBy(100_000)
        say(r, "status")
        r.add(reply("a1", "Fine.", streaming = false), AgentStatus.Idle)
        advanceUntilIdle()
        assertTrue(r.input.active)
        advanceTimeBy(100_000)
        assertTrue(failAndWait(r, SpeechEvent.Error("No speech heard", retry = true, silence = true), 300))
        assertEquals(VoicePhase.Listening, r.state.phase)
    }
}
