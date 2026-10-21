package app.tsukaima.voice

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SentencePipelineTest {
    /** A fake synthesis: each text's "file" is "<text>#<n>"; [gates] hold a text's production until completed. */
    private class Synth {
        val produced = mutableListOf<String>()
        val discarded = mutableListOf<String>()
        val gates = mutableMapOf<String, CompletableDeferred<Unit>>()
        var inFlight = 0
        var maxInFlight = 0

        suspend fun produce(text: String): String {
            produced += text
            inFlight++
            maxInFlight = maxOf(maxInFlight, inFlight)
            try {
                gates[text]?.await()
            } finally {
                inFlight--
            }
            return "$text#${produced.size}"
        }
    }

    private fun TestScope.pipeline(s: Synth) =
        SentencePipeline(backgroundScope, produce = s::produce, discard = { s.discarded += it })

    @Test
    fun sentencesComeInOrderWithOneProducedAhead() = runTest {
        val s = Synth()
        val p = pipeline(s)
        val texts = listOf("One.", "Two.", "Three.")
        val played = mutableListOf<String?>()
        texts.forEachIndexed { i, t ->
            played += p.take(t, texts.getOrNull(i + 1))
            // While "playing" t, the next one is the only one ahead.
            assertEquals(texts.getOrNull(i + 1), p.prefetched)
        }
        assertEquals(listOf("One.#1", "Two.#2", "Three.#3"), played)
        assertEquals(texts, s.produced) // each sentence synthesised once, in order
        assertTrue(s.maxInFlight <= 2) // the sentence taken and one ahead, never more
        assertEquals(emptyList<String>(), s.discarded)
    }

    @Test
    fun aPrefetchHintKeepsOnlyOneAheadAndAnotherNextSentenceDropsIt() = runTest {
        val s = Synth()
        val p = pipeline(s)
        assertEquals("A.#1", p.take("A."))
        p.prefetch("B.")
        p.prefetch("B.") // same hint: not produced twice
        runCurrent()
        p.prefetch("C.") // a newer hint replaces it: B's file is dropped
        runCurrent()
        assertEquals(listOf("B.#2"), s.discarded)
        assertEquals("C.", p.prefetched)

        // The next sentence spoken is not the prefetched one: it is discarded and the spoken one produced.
        assertEquals("D.#4", p.take("D."))
        assertEquals(listOf("B.#2", "C.#3"), s.discarded)
        assertEquals(listOf("A.", "B.", "C.", "D."), s.produced)
    }

    @Test
    fun cancelDropsThePrefetchedFileAndTheSentenceInFlight() = runTest {
        val s = Synth()
        val p = pipeline(s)
        assertEquals("A.#1", p.take("A.", next = "B."))
        runCurrent() // B is ready ahead
        p.cancel()
        assertEquals(listOf("B.#2"), s.discarded)
        assertNull(p.prefetched)

        // A stop while a sentence is synthesising: the take returns nothing, the one ahead is cancelled unproduced.
        s.gates["C."] = CompletableDeferred()
        s.gates["D."] = CompletableDeferred()
        val taking = async { p.take("C.", next = "D.") }
        runCurrent()
        p.cancel()
        assertNull(taking.await())
        runCurrent()
        assertEquals(0, s.inFlight)
        assertEquals(listOf("B.#2"), s.discarded) // nothing else was produced to discard

        // The next sentence after the stop is produced afresh.
        assertEquals("E.#5", p.take("E."))
    }
}
