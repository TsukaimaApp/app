package app.tsukaima.domain.voice

import app.tsukaima.domain.markdown.Markdown
import app.tsukaima.domain.markdown.MdBlock
import app.tsukaima.domain.markdown.MdSpan
import org.junit.Assert.assertEquals
import org.junit.Test

class SpeechTextTest {
    @Test fun plainSkipsCodeBlocksAndMediaLines() {
        val md = "Done.\n\n```bash\nrm -rf build\n```\nMEDIA:/tmp/out.png\nSee **this** [log](http://x/y) and `uname -a`."
        assertEquals("Done.\nSee this log and uname -a.", SpeechText.plain(md))
    }

    @Test fun plainDropsWholeLineImages() {
        val md = "Here is the chart:\n  ![CPU load](https://e.com/cpu.png)  \nLoad peaked at 3. See ![it](https://e.com/x.png) inline."
        assertEquals("Here is the chart:\nLoad peaked at 3. See it inline.", SpeechText.plain(md))
        val parens = "Map:\n![Foo](https://en.wikipedia.org/wiki/Foo_(bar))\n> ![q](https://e.com/q.png \"Title\")\nEnd."
        assertEquals("Map:\nEnd.", SpeechText.plain(parens))
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

    @Test fun plainReadsTableRowsAsCommaSeparatedCells() =
        assertEquals(
            "Service, State\nhermes, running\nbridge, stopped | maybe",
            SpeechText.plain("| Service | State |\n|:--|--:|\n| **hermes** | running |\n| bridge | stopped \\| maybe |"),
        )

    @Test fun plainDropsRulesAndSetextUnderlines() =
        assertEquals("a\nTitle\nb\nc", SpeechText.plain("a\n\n---\nTitle\n===\nb\n* * *\n___\nc"))

    @Test fun plainReadsAQuotedTableRow() =
        assertEquals("a, b\n1, 2", SpeechText.plain("> | a | b |\n> |---|---|\n> | 1 | 2 |\n> ---"))

    @Test fun plainStripsNestedQuoteMarkers() =
        assertEquals("deep", SpeechText.plain("> > deep"))

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

    @Test fun chunkerNeverSplitsInsideATildeFence() {
        val c = SentenceChunker()
        assertEquals(listOf("Run:", "~~~~sh\nrm -rf x. Really.\n~~~~"), c.feed("Run:\n~~~~sh\nrm -rf x. Really.\n~~~~\nDone."))
        assertEquals("Done.", c.flush())
        assertEquals("", SpeechText.plain("~~~~sh\nrm -rf x. Really.\n~~~~"))
    }

    @Test fun chunkerIgnoresASameLineFence() {
        val c = SentenceChunker()
        assertEquals(listOf("Use:", "```x``` is it."), c.feed("Use:\n```x``` is it. Next one"))
        assertEquals(listOf("Next one."), c.feed("Use:\n```x``` is it. Next one. Then"))
        assertEquals(listOf("~~ not a fence.", "A."), SentenceChunker().feed("~~ not a fence. A. B"))
    }

    @Test fun mathIsNotSpoken() =
        assertEquals(
            "The area is and the sum:\nDone.",
            SpeechText.plain("The area is $\\pi r^2$ and the sum:\n$$\n\\sum_i x_i\n$$\nDone."),
        )

    @Test fun mathKeepsDollarAmounts() =
        assertEquals("costs $5 and $10 today", SpeechText.plain("costs $5 and $10 today"))

    @Test fun taskMarkersAreStripped() =
        assertEquals("buy milk\nwalk dog\nfeed cat", SpeechText.plain("- [ ] buy milk\n- [x] walk dog\n1. [X] feed cat"))

    @Test fun chunkerKeepsDisplayMathInOneChunk() {
        val c = SentenceChunker()
        assertEquals(listOf("So:"), c.feed("So:\n$$\nx = 1.\n"))
        val chunks = c.feed("So:\n$$\nx = 1.\ny = 2\n$$\nDone.\n")
        assertEquals(listOf("$$\nx = 1.\ny = 2\n$$", "Done."), chunks)
        assertEquals("", SpeechText.plain(chunks[0]))
    }

    @Test fun aStrayDisplayMathOpenerIsText() {
        val spoken = SpeechText.plain("$$ is the PID\nThen restart.")
        assertEquals(true, "Then restart." in spoken)
        // Unclosed before the blank line: text in the chat, so read as text too.
        assertEquals("$$\n\\sum x\nThen restart.", SpeechText.plain("$$\n\\sum x\n\nThen restart."))
    }

    @Test fun chunkerIsNotBlockedByAStrayDisplayMathOpener() {
        val c = SentenceChunker()
        assertEquals(listOf("$$ is the PID", "Then restart."), c.feed("$$ is the PID\n\nThen restart. Next"))
        val long = SentenceChunker()
        val text = "$$ x\n" + (1..25).joinToString("") { "line $it\n" }
        assertEquals(true, long.feed(text).isNotEmpty())
    }

    @Test fun speechMatchesTheParserForDollars() {
        // The parser reads `$a $b$` as one formula (the space before the middle `$` keeps it from closing).
        assertEquals("see now", SpeechText.plain("see \$a \$b\$ now"))
        assertEquals("echo \$HOME/\$USER", SpeechText.plain("echo \$HOME/\$USER"))
        assertEquals("a\$b\$c", SpeechText.plain("a\$b\$c"))
        assertEquals("(a\$x\$) () .", SpeechText.plain("(a\$x\$) (\$y\$) \$z\$."))
        // Whatever the parser shows as text is what is spoken (no other markup in these lines).
        val lines = listOf(
            "see \$a \$b\$ now", "\$HOME/\$USER", "a\$b\$c", "(\$a+b\$)", "\$x\$.", "costs \$5 and \$10", "\$a \$b \$c\$ d\$",
            "x \$y\$z \$w\$", "\$\$a\$ b\$",
        )
        for (line in lines) {
            val shown = Markdown.parse(line).filterIsInstance<MdBlock.Paragraph>().flatMap { it.spans }
                .joinToString("") { if (it is MdSpan.Text) it.s else "" }
            assertEquals(line, shown.replace(Regex(" {2,}"), " ").trim(), SpeechText.plain(line))
        }
    }

    @Test fun emptyDisplayMathIsTextInBoth() {
        // The parser shows an empty `$$`/`$$` pair as text, so speech reads it too.
        assertEquals("$$\n$$", SpeechText.plain("$$\n$$"))
        assertEquals("So:\n$$\n$$\nDone.", SpeechText.plain("So:\n$$\n$$\nDone."))
        assertEquals("[\n]", SpeechText.plain("\\[\n\\]"))
    }
}
