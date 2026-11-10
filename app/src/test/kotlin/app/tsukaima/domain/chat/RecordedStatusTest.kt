package app.tsukaima.domain.chat

import app.tsukaima.data.gateway.Frame
import app.tsukaima.data.gateway.JsonRpc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `status.update` as Hermes v0.23.1 sends it, recorded from the test server on 2026-11-10:
 * - `recorded-compaction.jsonl`: two turns that each began with an automatic compaction, then a manual
 *   `session.compress` on the idle session;
 * - `recorded-compaction-refused.jsonl`: an automatic compaction the server refused (no done line at all), then a
 *   manual one refused with a warning;
 * - `recorded-fallback-turns.jsonl`: two turns served by the fallback model.
 * Message texts are cut; every status text is as sent.
 */
class RecordedStatusTest {
    private fun frames(name: String): List<Frame.Event> =
        JsonRpc.decodeFrames(checkNotNull(javaClass.getResource("/chat/$name")) { name }.readText()).filterIsInstance<Frame.Event>()

    private fun fold(frames: List<Frame.Event>, from: ChatState = ChatState(liveSessionId = frames.first().sessionId)): List<ChatState> =
        frames.runningFold(from) { s, f -> ChatReducer.reduce(s, f) { "…" } }.drop(1)

    private fun notices(s: ChatState) = s.items.filterIsInstance<ChatItem.Notice>()

    @Test
    fun anAutomaticCompactionSetsTheStateAndItsDoneLineClearsIt() {
        val frames = frames("recorded-compaction.jsonl")
        val states = fold(frames)
        frames.zip(states).forEach { (f, s) ->
            val kind = f.payload["kind"]?.toString()?.trim('"')
            val text = f.payload["text"]?.toString().orEmpty()
            if (f.type == "status.update" && kind == "compacted") assertFalse("seq ${f.seq}", s.compacting)
            if (f.type == "status.update" && kind == "compacting" && "⚠" !in text) assertTrue("seq ${f.seq}", s.compacting)
            if (f.type in setOf("reasoning.delta", "message.complete")) assertFalse("seq ${f.seq}", s.compacting)
        }
        val quiet = notices(states.last()).filter { !it.warning && !it.error }
        assertEquals("one line per finished compaction", listOf("Context compacted", "Context compacted"), quiet.map { it.text })
    }

    @Test
    fun aWarningTaggedCompactingIsAWarningAndNotAState() {
        val frames = frames("recorded-compaction.jsonl")
        val at = frames.indexOfFirst { it.type == "status.update" && "Session compressed" in it.payload["text"].toString() }
        assertEquals("the gateway re-tags it", "\"compacting\"", frames[at].payload["kind"].toString())
        // Alone, it does not start the compacting state.
        val alone = ChatReducer.reduce(ChatState(liveSessionId = frames[at].sessionId), frames[at]) { "…" }
        assertFalse(alone.compacting)
        val warning = notices(alone).single()
        assertTrue(warning.warning)
        assertEquals("Session compressed 2 times — accuracy may degrade. Consider /new to start fresh.", warning.text)
    }

    @Test
    fun aManualCompressEndsWithTheReadyStatusWord() {
        val frames = frames("recorded-compaction.jsonl")
        val states = fold(frames)
        val start = frames.indexOfFirst { it.type == "status.update" && it.payload["kind"].toString() == "\"compressing\"" }
        assertTrue(states[start].compacting)
        val ready = frames.indexOfLast { it.type == "status.update" }
        assertEquals("""{"kind":"status","text":"ready"}""", frames[ready].payload.toString())
        assertFalse(states[ready].compacting)
        assertEquals(AgentStatus.Idle, states.last().status)
    }

    @Test
    fun aRefusedAutomaticCompactionEndsWhenTheTurnProducesOutput() {
        val frames = frames("recorded-compaction-refused.jsonl")
        val states = fold(frames)
        val delta = frames.indexOfFirst { it.type == "message.delta" }
        assertTrue("no done line was sent", frames.take(delta).none { it.payload["kind"].toString() == "\"compacted\"" })
        assertTrue(states[delta - 1].compacting)
        assertFalse(states[delta].compacting)
    }

    @Test
    fun aRefusedManualCompressShowsItsWarningAndEnds() {
        val states = fold(frames("recorded-compaction-refused.jsonl"))
        val end = states.last()
        assertFalse(end.compacting)
        val warning = notices(end).single { it.warning }
        assertTrue(warning.text.startsWith("Compression refused:"))
        assertTrue(notices(end).none { it.text == "Context compacted" })
    }

    @Test
    fun aFallbackTurnLabelsItsReplyAndKeepsTheSessionModel() {
        val frames = frames("recorded-fallback-turns.jsonl")
        val states = fold(frames)
        val end = states.last()
        // session.info named the session's model before the turn; after each fallback turn it names the fallback.
        assertEquals("no-such-model-tsukaima-probe", end.model)
        assertTrue(end.fallback)
        val replies = end.items.filterIsInstance<ChatItem.AssistantMessage>()
        assertEquals(2, replies.size)
        assertTrue(replies.all { it.model == "deepseek-v4.1-flash-1017" })
        // "Primary model restored" at the start of the second turn cleared the mark until that turn fell back again.
        val restored = frames.indexOfFirst { it.type == "status.update" && "Primary model restored" in it.payload["text"].toString() }
        assertFalse(states[restored].fallback)
        val lines = notices(end)
        assertEquals(listOf(true, false, true), lines.map { it.warning })
        assertTrue(lines[1].text.startsWith("Primary model restored:"))
    }

    @Test
    fun aTurnOnTheSessionsOwnModelHasNoLabel() {
        val states = fold(frames("recorded-compaction.jsonl"))
        assertEquals("deepseek-v4.1-flash-1017", states.last().model)
        assertTrue(states.last().items.filterIsInstance<ChatItem.AssistantMessage>().all { it.model == null })
        assertNull(ChatState().model)
    }
}
