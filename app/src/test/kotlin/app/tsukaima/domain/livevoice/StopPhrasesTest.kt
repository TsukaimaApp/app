package app.tsukaima.domain.livevoice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StopPhrasesTest {
    @Test fun stopAloneIsAStop() {
        val stops = listOf(
            "Stop.", "hey hermes, stop", "that's all!", "Please stop",
            "OK, never mind", "ｓｔｏｐ", "Hermes stop listening",
        )
        for (s in stops) {
            assertTrue(s, StopPhrases.isStopCommand(s))
        }
        assertFalse(StopPhrases.isStopCommand("отбой"))
        assertFalse(StopPhrases.isStopCommand(""))
        assertFalse(StopPhrases.isStopCommand("hermes"))
        assertFalse(StopPhrases.isStopCommand("ok"))
    }

    @Test fun stopPhraseInsideARequestIsNotAStop() {
        assertFalse(StopPhrases.isStopCommand("stop the container"))
        assertFalse(StopPhrases.isStopCommand("how do I stop a process"))
        assertFalse(StopPhrases.isStopCommand("hey hermes, stop the docker container"))
    }
}
