package labs.suchattai.tsukaima.domain.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.random.Random

class MediaDirectiveTest {
    private val log = "/home/hermes/workspace/output/session-20261020_075645_44ae73/20261020_075645_44ae73.debug.txt"

    private fun para(s: String) = MdBlock.Paragraph(listOf(MdSpan.Text(s)))

    @Test
    fun wholeLineDirectiveBecomesMediaAndLeavesTheParagraph() {
        assertEquals(
            listOf(para("Here is the log:"), MdBlock.Media(log), para("Anything else?")),
            Markdown.parse("Here is the log:\nMEDIA:$log\nAnything else?"),
        )
        assertEquals(listOf(MdBlock.Media(log)), Markdown.parse("  MEDIA: $log  "))
    }

    @Test
    fun quotedAndBacktickedPathsMayHoldSpaces() {
        assertEquals(listOf(MdBlock.Media("/srv/out/my report.pdf")), Markdown.parse("MEDIA:`/srv/out/my report.pdf`"))
        assertEquals(listOf(MdBlock.Media("/srv/out/my report.pdf")), Markdown.parse("MEDIA:\"/srv/out/my report.pdf\""))
        assertEquals(listOf(MdBlock.Media("/srv/out/a.txt")), Markdown.parse("`MEDIA:/srv/out/a.txt`"))
        assertEquals(listOf(MdBlock.Media("/srv/out/a.txt")), Markdown.parse("\"MEDIA:/srv/out/a.txt\""))
        // Unquoted with spaces only when it ends in a deliverable extension (Hermes desktop).
        assertEquals(listOf(MdBlock.Media("/srv/out/my report.pdf")), Markdown.parse("MEDIA:/srv/out/my report.pdf"))
        assertEquals(listOf(para("MEDIA:/srv/out/see the file")), Markdown.parse("MEDIA:/srv/out/see the file"))
    }

    @Test
    fun homeRelativePath() {
        assertEquals(listOf(MdBlock.Media("~/out/chart.png")), Markdown.parse("MEDIA:~/out/chart.png"))
    }

    @Test
    fun onlyAnchoredPathsCount() {
        assertNull(Markdown.mediaPath("MEDIA:out/chart.png"))
        assertNull(Markdown.mediaPath("MEDIA:~chart.png"))
        assertNull(Markdown.mediaPath("MEDIA:/"))
        assertNull(Markdown.mediaPath("MEDIA:/srv/out/"))
        assertNull(Markdown.mediaPath("MEDIA:"))
        assertNull(Markdown.mediaPath("MEDIA:`/a b.pdf` trailing"))
    }

    @Test
    fun midProseDirectiveStaysText() {
        assertEquals(listOf(para("I saved it as MEDIA:$log for you")), Markdown.parse("I saved it as MEDIA:$log for you"))
        // Inside a code fence or a bullet it is not a directive either.
        assertEquals(listOf(MdBlock.Code(null, "MEDIA:$log")), Markdown.parse("```\nMEDIA:$log\n```"))
        assertEquals(1, (Markdown.parse("- MEDIA:$log").single() as MdBlock.Bullets).items.size)
    }

    @Test
    fun consecutiveDirectivesAreSeparateBlocks() {
        assertEquals(
            listOf(MdBlock.Media("/tmp/a.png"), MdBlock.Media("/tmp/b.pdf")),
            Markdown.parse("MEDIA:/tmp/a.png\nMEDIA:/tmp/b.pdf"),
        )
    }

    @Test
    fun streamingLeavesTheLastLineAsText() {
        val half = "Done.\nMEDIA:/home/hermes/workspace/out"
        assertEquals(listOf(para(half)), Markdown.parse(half, streaming = true))
        assertEquals(listOf(para("Done."), MdBlock.Media("/home/hermes/workspace/out")), Markdown.parse(half, streaming = false))
        // A finished line (newline already streamed) is converted while streaming.
        assertEquals(listOf(para("Done."), MdBlock.Media(log)), Markdown.parse("Done.\nMEDIA:$log\n", streaming = true))
    }

    @Test
    fun crlfInput() {
        assertEquals(
            listOf(para("Here:"), MdBlock.Media(log), para("bye")),
            Markdown.parse("Here:\r\nMEDIA:$log\r\nbye"),
        )
    }

    @Test
    fun randomDirectivesNeverThrow() {
        val alphabet = "MEDIA:/~`\"' .txab\n"
        val rnd = Random(7)
        repeat(500) {
            val s = buildString { repeat(rnd.nextInt(0, 40)) { append(alphabet[rnd.nextInt(alphabet.length)]) } }
            Markdown.parse(s, streaming = rnd.nextBoolean())
        }
    }
}
