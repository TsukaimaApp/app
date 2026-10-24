package app.tsukaima.domain.livevoice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LiveEventTest {
    @Test fun parseStartedTranscriptDelegationErrorClosed() {
        assertEquals(
            LiveEvent.Started("s1"),
            LiveEvent.parse("""{"type":"session.started","session":{"id":"s1"}}"""),
        )
        assertEquals(LiveEvent.Started(null), LiveEvent.parse("""{"type":"session.started"}"""))
        assertEquals(
            LiveEvent.Transcript(LiveFragment(LiveSpeaker.User, "hi", 10, 20)),
            LiveEvent.parse("""{"type":"session.input_transcript.delta","delta":"hi","start_ms":10,"end_ms":20}"""),
        )
        assertEquals(
            LiveEvent.Transcript(LiveFragment(LiveSpeaker.Assistant, "yo", 5, 0)),
            LiveEvent.parse("""{"type":"session.output_transcript.delta","delta":"yo","start_ms":5}"""),
        )
        assertEquals(
            LiveEvent.Delegation("d1"),
            LiveEvent.parse("""{"type":"session.delegation.created","delegation":{"id":"d1","type":"x"}}"""),
        )
        assertEquals(
            LiveEvent.Unknown("session.delegation.created"),
            LiveEvent.parse("""{"type":"session.delegation.created"}"""),
        )
        assertEquals(
            LiveEvent.Error("boom", "context_injection_incomplete"),
            LiveEvent.parse("""{"type":"error","error":{"message":"boom","code":"context_injection_incomplete"}}"""),
        )
        assertEquals(LiveEvent.Error("GPT-Live error", null), LiveEvent.parse("""{"type":"error"}"""))
        assertEquals(
            LiveEvent.Closed("bye", 84.2),
            LiveEvent.parse("""{"type":"session.closed","reason":"bye","usage":{"seconds":84.2}}"""),
        )
        assertEquals(LiveEvent.Closed("closed", null), LiveEvent.parse("""{"type":"session.closed"}"""))
    }

    @Test fun fractionalTimesAreTruncatedNotZero() = assertEquals(
        LiveEvent.Transcript(LiveFragment(LiveSpeaker.User, "hi", 10, 20)),
        LiveEvent.parse("""{"type":"session.input_transcript.delta","delta":"hi","start_ms":10.7,"end_ms":20.2}"""),
    )

    @Test fun emptyDelegationIdIsUnknown() = assertEquals(
        LiveEvent.Unknown("session.delegation.created"),
        LiveEvent.parse("""{"type":"session.delegation.created","delegation":{"id":""}}"""),
    )

    @Test fun parseGarbageIsNull() {
        val garbage = listOf(
            "", "nope", "[1]", "42", "\"x\"", "{}",
            """{"type":""}""", """{"type":"  "}""", """{"type":3}""",
        )
        for (raw in garbage) {
            assertNull(raw, LiveEvent.parse(raw))
        }
    }

    @Test fun parseUnknownTypeIsUnknown() =
        assertEquals(LiveEvent.Unknown("rate_limits.updated"), LiveEvent.parse("""{"type":"rate_limits.updated"}"""))
}
