package app.tsukaima.assist

import app.tsukaima.data.prefs.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AssistSessionPolicyTest {
    @Test
    fun reusesWithinThirtyMinutes() = assertEquals(
        "s1",
        AssistSessionPolicy.sessionToResume(Settings(assistSessionId = "s1", assistSessionAt = 1_000_000L), now = 1_000_000L + 29 * 60_000L),
    )

    @Test
    fun startsFreshAfterThirtyMinutes() = assertNull(
        AssistSessionPolicy.sessionToResume(Settings(assistSessionId = "s1", assistSessionAt = 1_000_000L), now = 1_000_000L + 31 * 60_000L),
    )

    @Test
    fun noSessionWithoutATimestamp() = assertNull(AssistSessionPolicy.sessionToResume(Settings(assistSessionId = "s1"), now = 5L))
}
