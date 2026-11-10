package app.tsukaima.domain.chat

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import app.tsukaima.data.api.HermesJson
import app.tsukaima.data.gateway.Frame
import app.tsukaima.data.gateway.JsonRpc
import app.tsukaima.data.gateway.TranscriptMessage
import app.tsukaima.ui.chat.ChatEdits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A turn that was steered while its first tool ran, recorded from the test Hermes (v0.23.1, 2026-11-10):
 * `recorded-steer-turn.jsonl` holds the turn's events, `recorded-steer-turn-history.json` the answer to
 * `session.steer`, the last event seq seen before it, and `session.history` after the turn.
 */
class RecordedSteerTest {
    private fun resource(name: String) = checkNotNull(javaClass.getResource("/chat/$name")) { name }.readText()

    private val frames: List<Frame.Event> = JsonRpc.decodeFrames(resource("recorded-steer-turn.jsonl")).map { it as Frame.Event }
    private val snapshot: JsonObject = HermesJson.parseToJsonElement(resource("recorded-steer-turn-history.json")).jsonObject
    private val live = snapshot["live"]!!.jsonPrimitive.content
    private val userRow = snapshot["user_row_id"]!!.jsonPrimitive.long
    private val steerAfter = snapshot["steer_after_seq"]!!.jsonPrimitive.long
    private val history: List<TranscriptMessage> =
        HermesJson.decodeFromJsonElement(ListSerializer(TranscriptMessage.serializer()), snapshot["history"]!!)

    private fun start(): ChatState {
        val sent = ChatReducer.onSubmitted(ChatState(liveSessionId = live), "run the two commands", "local-1", flair = { "…" })
        return ChatReducer.onSubmitAccepted(sent, "local-1", userRow)
    }

    /** The turn's states, the steer sent after the frame it was sent after in the recording (null = never). */
    private fun states(steerAt: Long? = steerAfter, upTo: Int = frames.size): List<ChatState> =
        frames.take(upTo).runningFold(start()) { s, f ->
            val next = ChatReducer.reduce(s, f) { "…" }
            if (f.seq == steerAt) ChatReducer.onSteered(next, "end with banana", "steer-1") else next
        }

    private fun ids(s: ChatState) = s.items.map { it.id }
    private fun steer(s: ChatState) = s.items.first { it.id == "steer-1" } as ChatItem.UserMessage

    @Test
    fun theServerTookTheSteerAndRefusedOneOnAnIdleSession() {
        assertEquals("queued", snapshot["steer"]!!.jsonObject["status"]!!.jsonPrimitive.content)
        assertEquals("rejected", snapshot["steer_idle"]!!.jsonObject["status"]!!.jsonPrimitive.content)
    }

    @Test
    fun theSteerStaysWhereItWasSentAndNothingAboveMoves() {
        states().zipWithNext().forEachIndexed { step, (before, after) ->
            val a = ids(before)
            val b = ids(after)
            val kept = minOf(a.size - 1, b.size).coerceAtLeast(0)
            assertEquals("step $step", a.take(kept), b.take(kept))
        }
        val end = states().last()
        assertEquals(
            listOf("user", "tool", "steer", "tool", "reply"),
            end.items.map {
                when (it) {
                    is ChatItem.UserMessage -> if (it.steer) "steer" else "user"
                    is ChatItem.ToolCall -> "tool"
                    is ChatItem.AssistantMessage -> "reply"
                    else -> it::class.simpleName
                }
            },
        )
    }

    @Test
    fun theSteerWaitsUntilTheRunningToolEnds() {
        val all = states()
        val sent = all[frames.indexOfFirst { it.seq == steerAfter } + 1]
        assertTrue(steer(sent).steerWaiting)
        val read = all[frames.indexOfFirst { it.type == "tool.complete" } + 1]
        assertFalse(steer(read).steerWaiting)
        assertTrue(steer(read).steer)
    }

    @Test
    fun theTurnEndGivesTheSteerItsRowAndARebuildKeepsEveryId() {
        val end = states().last()
        // persisted_turn.user_row_ids = [the turn's row, the steer's row]
        assertEquals(history.single { it.displayKind == "steer" }.rowId, steer(end).rowId)
        assertEquals(userRow, (end.items.first() as ChatItem.UserMessage).rowId)
        val rebuilt = ChatReducer.rebuild(ChatReducer.fromHistory(history), end.items)
        assertEquals(ids(end), rebuilt.map { it.id })
        assertTrue((rebuilt[2] as ChatItem.UserMessage).steer)
    }

    @Test
    fun theSteerDoesNotSplitTheTurn() {
        val end = states().last()
        // The reply's row is bound to the final reply although a user bubble sits inside the turn.
        val reply = end.items.last() as ChatItem.AssistantMessage
        assertEquals(279L, reply.rowId)
        // Regenerate on the reply resends the turn's own message, not the steer.
        assertEquals("local-1", ChatEdits.regenerateSource(end.items, reply.id)?.id)
    }

    @Test
    fun aSteerTheTurnNeverReadBecomesAQueuedMessageAndThenItsOwnTurn() {
        // Sent after the last tool finished: no tool result is left to carry it.
        val lastTool = frames.last { it.type == "tool.complete" }.seq
        val end = states(steerAt = lastTool).last()
        val bubble = end.items.last() as ChatItem.UserMessage
        assertEquals("steer-1", bubble.id)
        assertTrue(bubble.queued)
        assertFalse(bubble.steer)
        assertTrue(end.queuedTurnNext)
        val next = ChatReducer.reduce(end, Frame.Event("message.start", live, 100, JsonObject(emptyMap()))) { "…" }
        assertFalse((next.items.first { it.id == "steer-1" } as ChatItem.UserMessage).queued)
        assertEquals("steer-1", next.items[next.items.size - 2].id)
    }

    @Test
    fun stopFailsASteerTheAgentHasNotRead() {
        val mid = states(upTo = frames.indexOfFirst { it.seq == steerAfter } + 1).last()
        val stopped = ChatReducer.onStopRequested(mid, true)
        val payload = HermesJson.parseToJsonElement("""{"text":"","status":"interrupted"}""").jsonObject
        val end = ChatReducer.reduce(stopped, Frame.Event("message.complete", live, 100, payload)) { "…" }
        val bubble = steer(end)
        assertTrue(bubble.failed)
        assertFalse(bubble.steer)
        assertNull(bubble.rowId)
    }

    @Test
    fun aRefusedSteerLeavesNoBubble() {
        val mid = states(upTo = frames.indexOfFirst { it.seq == steerAfter } + 1).last()
        assertTrue(ChatReducer.onSteerDropped(mid, "steer-1").items.none { it.id == "steer-1" })
    }

    /** The turn ended before the server answered the steer, and the answer is a refusal: nothing may stay behind. */
    @Test
    fun aSteerRefusedAfterTheTurnEndedLeavesNoQueuedGhost() {
        val lastTool = frames.last { it.type == "tool.complete" }.seq
        val end = states(steerAt = lastTool).last()
        assertTrue(end.queuedTurnNext)
        val dropped = ChatReducer.onSteerDropped(end, "steer-1")
        assertTrue(dropped.items.none { it.id == "steer-1" })
        assertFalse(dropped.queuedTurnNext)
        assertFalse(dropped.queuesSends)
    }
}
