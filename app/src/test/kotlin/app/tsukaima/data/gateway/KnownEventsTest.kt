package app.tsukaima.data.gateway

import app.tsukaima.domain.chat.ChatReducer
import app.tsukaima.domain.chat.ChatState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KnownEventsTest {
    @Test
    fun everyRecordedEventTypeIsKnown() {
        val recorded = listOf(
            "recorded-tool-turn.jsonl", "recorded-steer-turn.jsonl", "recorded-compaction.jsonl",
            "recorded-compaction-refused.jsonl", "recorded-fallback-turns.jsonl", "recorded-subagent.jsonl",
        ).flatMap { name -> JsonRpc.decodeFrames(checkNotNull(javaClass.getResource("/chat/$name")).readText()) }
            .filterIsInstance<Frame.Event>().map { it.type }.toSet()
        assertTrue(recorded.size > 10)
        assertEquals(emptyList<String>(), recorded.filterNot(KnownEvents::isKnown))
    }

    @Test
    fun anUnknownTypeIsReportedOnceAndChangesNothing() {
        assertTrue(KnownEvents.isKnown("subagent.tool"))
        assertTrue(KnownEvents.isKnown("tool.progress"))
        assertFalse(KnownEvents.isKnown("hologram.projected"))
        assertTrue(KnownEvents.isNew("hologram.projected"))
        assertFalse("only the first time", KnownEvents.isNew("hologram.projected"))
        assertFalse(KnownEvents.isNew("message.delta"))
        val state = ChatState(liveSessionId = "live")
        val after = ChatReducer.reduce(state, Frame.Event("hologram.projected", "live", 1, kotlinx.serialization.json.JsonObject(emptyMap())))
        assertEquals(state.copy(lastSeq = 1), after)
    }
}
