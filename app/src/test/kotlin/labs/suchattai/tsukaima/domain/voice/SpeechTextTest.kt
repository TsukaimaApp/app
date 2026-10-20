package labs.suchattai.tsukaima.domain.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeechTextTest {
    @Test fun plainSkipsCodeBlocksAndMediaLines() {
        val md = "Done.\n\n```bash\nrm -rf build\n```\nMEDIA:/tmp/out.png\nSee **this** [log](http://x/y) and `uname -a`."
        assertEquals("Done.\nSee this log and uname -a.", SpeechText.plain(md))
    }

    @Test fun plainStripsListAndHeadingMarkers() =
        assertEquals("Steps\nfirst\nsecond", SpeechText.plain("# Steps\n- first\n1. second"))

    @Test fun chunkerEmitsOnlyConfirmedSentences() {
        val c = SentenceChunker()
        assertEquals(emptyList<String>(), c.feed("It failed at 3."))
        assertEquals(listOf("It failed at 3.14 seconds."), c.feed("It failed at 3.14 seconds. The"))
        assertEquals(emptyList<String>(), c.feed("It failed at 3.14 seconds. The token"))
        assertEquals("The token", c.flush())
    }

    @Test fun chunkerPositionsPointIntoTheOriginal() {
        val c = SentenceChunker()
        val chunks = c.feedPositions("Yes. No.\nMaybe")
        assertEquals(listOf("Yes." to 4, "No." to 9), chunks.map { it.text to it.end })
    }

    @Test fun chunkerNeverSplitsInsideACodeFence() {
        val text = "Run:\n```\nrm -rf x. Really.\n```\nDone."
        val expected = listOf("Run:", "```\nrm -rf x. Really.\n```")
        val c = SentenceChunker()
        assertEquals(expected, c.feed(text))
        assertEquals("Done.", c.flush())
        assertEquals("", SpeechText.plain("```\nrm -rf x. Really.\n```"))

        val inc = SentenceChunker()
        val first = inc.feed("Run:\n```\nrm -rf x. ")
        assertEquals(listOf("Run:"), first)
        assertEquals(expected.drop(1), inc.feed(text))
    }

    @Test fun chunkerIgnoresInlineBackticksAfterABoundary() {
        val c = SentenceChunker()
        assertEquals(listOf("Yes."), c.feed("Yes. ```x``` is code."))
        assertEquals(listOf("```x``` is code."), c.feed("Yes. ```x``` is code. Next"))
    }

    @Test fun plainDropsBareUrls() =
        assertEquals("see now", SpeechText.plain("see https://example.com/a?b=1 now"))

    @Test fun plainRemovesStrikeAndUnderscoreEmphasis() =
        assertEquals("gone and soft", SpeechText.plain("~~gone~~ and _soft_"))

    @Test fun plainRemovesUnterminatedFenceToTheEnd() =
        assertEquals("before", SpeechText.plain("before\n```kotlin\nval x = 1\nmore"))

    @Test fun chunkerCutsAtEllipsisAndColon() {
        assertEquals(listOf("Wait…"), SentenceChunker().feed("Wait… Next"))
        assertEquals(listOf("Note:"), SentenceChunker().feed("Note: x"))
    }

    @Test fun chunkerFeedingTheSameTextTwiceEmitsNothingNew() {
        val c = SentenceChunker()
        assertEquals(listOf("A."), c.feed("A. B"))
        assertEquals(emptyList<String>(), c.feed("A. B"))
    }

    @Test fun chunkerFlushResets() {
        val c = SentenceChunker()
        assertEquals(listOf("A."), c.feed("A. B"))
        assertEquals("B", c.flush())
        assertEquals(listOf("C."), c.feed("C. D"))
    }
}
