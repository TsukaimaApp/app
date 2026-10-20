package labs.suchattai.tsukaima.domain.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class VadTest {
    private val quiet = 100.0
    private val loud = 3000.0

    /** Feeds [rms] for [ms] milliseconds of 20 ms frames; returns every event. */
    private fun UtteranceDetector.feedFor(ms: Int, rms: Double): List<VadEvent> = List(ms / 20) { feed(rms) }

    @Test fun silenceOnlyEndsWithNoSpeechAfterEightSeconds() {
        val d = UtteranceDetector()
        val events = d.feedFor(8000, quiet)
        assertEquals(VadEvent.NoSpeech, events.last())
        assertEquals(399, events.indexOfFirst { it == VadEvent.NoSpeech }) // the 400th frame completes 8 s
        assertNull(d.speechStartFrame)
    }

    @Test fun speechThenSilenceEndsTheUtteranceAfterTheEndSilence() {
        val d = UtteranceDetector()
        assertEquals(VadEvent.Listening, d.feedFor(1000, quiet).last())
        val speech = d.feedFor(1000, loud)
        assertEquals(VadEvent.Listening, speech.first())
        assertEquals(VadEvent.Speech, speech.last())
        assertEquals(50, d.speechStartFrame) // the first loud frame
        val after = d.feedFor(2000, quiet)
        assertEquals(59, after.indexOfFirst { it == VadEvent.EndOfUtterance }) // 60 × 20 ms = 1.2 s
        assertEquals(VadEvent.Speech, after[58])
        assertEquals(VadEvent.EndOfUtterance, d.feed(loud)) // ended stays ended
    }

    @Test fun pausesShorterThanTheEndSilenceKeepTheUtterance() {
        val d = UtteranceDetector()
        d.feedFor(400, quiet)
        d.feedFor(600, loud)
        val pause = d.feedFor(1000, quiet)
        assertEquals(listOf(VadEvent.Speech), pause.distinct())
        assertEquals(VadEvent.Speech, d.feedFor(600, loud).last())
    }

    @Test fun speechShorterThanTheMinimumIsIgnored() {
        val d = UtteranceDetector()
        d.feedFor(400, quiet)
        val click = d.feedFor(200, loud) + d.feedFor(1000, quiet) + d.feedFor(280, loud) + d.feedFor(1000, quiet)
        assertEquals(listOf(VadEvent.Listening), click.distinct())
        assertNull(d.speechStartFrame)
        val events = d.feedFor(8000, quiet)
        assertEquals(VadEvent.NoSpeech, events.last())
    }

    @Test fun longSpeechIsCutAtTheMaximumLength() {
        val d = UtteranceDetector()
        d.feedFor(400, quiet)
        val events = d.feedFor(31000, loud)
        val cut = events.indexOfFirst { it == VadEvent.EndOfUtterance }
        assertEquals(20 + 1500 - 1, cut + 20) // 30 s after the first speech frame (frame 20)
    }

    @Test fun theNoiseFloorAdaptsToANoisyRoom() {
        val d = UtteranceDetector()
        val room = 250.0
        assertEquals(listOf(VadEvent.Listening), d.feedFor(2000, room).distinct())
        assertEquals(VadEvent.Listening, d.feedFor(1000, 600.0).last()) // over minSpeechRms, under 2.5 × floor
        assertEquals(VadEvent.Speech, d.feedFor(1000, 2000.0).last())
    }

    @Test fun aQuietRoomStillNeedsTheMinimumLevel() {
        val d = UtteranceDetector()
        d.feedFor(400, 10.0)
        assertEquals(VadEvent.Listening, d.feedFor(1000, 300.0).last()) // 30 × floor, under 400
        assertEquals(VadEvent.Speech, d.feedFor(1000, 800.0).last())
    }

    @Test fun speechFromFrameZeroIsDetected() {
        val d = UtteranceDetector()
        val speech = d.feedFor(1000, 2000.0)
        assertEquals(VadEvent.Speech, speech.last())
        assertEquals(0, d.speechStartFrame)
        val after = d.feedFor(2000, quiet)
        assertEquals(59, after.indexOfFirst { it == VadEvent.EndOfUtterance })
    }

    @Test fun loudCalibrationDoesNotRaiseTheFloorAboveTheCap() {
        val d = UtteranceDetector()
        d.feedFor(300, 5000.0) // all of calibration is speech
        assertTrue(d.noiseFloor <= 300.0)
        d.feedFor(800, quiet) // a pause inside the utterance
        assertEquals(VadEvent.Speech, d.feedFor(400, 2000.0).last()) // still speech at a normal level
        assertEquals(0, d.speechStartFrame)
    }

    @Test fun aCandidateNearTheTimeoutIsNotCutByNoSpeech() {
        val d = UtteranceDetector()
        assertEquals(VadEvent.Listening, d.feedFor(7800, quiet).last())
        val events = d.feedFor(600, loud)
        assertFalse(VadEvent.NoSpeech in events)
        assertEquals(VadEvent.Speech, events.last())
        assertEquals(390, d.speechStartFrame)
    }

    @Test fun aNewSteadyNoiseIsAbsorbedIntoTheFloor() {
        val d = UtteranceDetector()
        d.feedFor(400, 50.0) // quiet calibration: threshold 400
        assertEquals(listOf(VadEvent.Listening), d.feedFor(3000, 350.0).distinct()) // a fan starts
        assertTrue(d.noiseFloor > 250.0)
        assertEquals(listOf(VadEvent.Listening), d.feedFor(1000, 600.0).distinct()) // would have been speech before
        assertEquals(VadEvent.Speech, d.feedFor(1000, 2000.0).last())
    }

    @Test fun syllablesWithShortDipsAreOneUtterance() {
        val d = UtteranceDetector()
        d.feedFor(400, quiet)
        val during = buildList {
            repeat(12) {
                addAll(d.feedFor(100, 3000.0))
                addAll(d.feedFor(100, 800.0))
                addAll(d.feedFor(60, quiet))
            }
        }
        assertFalse(VadEvent.EndOfUtterance in during)
        val after = d.feedFor(2000, quiet)
        assertEquals(1, (during + after).zipWithNext().count { (a, b) -> a != VadEvent.EndOfUtterance && b == VadEvent.EndOfUtterance })
        assertEquals(59 - 3, after.indexOfFirst { it == VadEvent.EndOfUtterance }) // the last dip counts as silence
    }
}
