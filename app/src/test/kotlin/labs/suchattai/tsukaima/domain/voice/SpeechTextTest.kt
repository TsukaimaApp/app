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
}
