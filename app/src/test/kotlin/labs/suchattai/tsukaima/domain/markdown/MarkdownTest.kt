package labs.suchattai.tsukaima.domain.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class MarkdownTest {
    @Test
    fun fencedCodeWithLang() {
        val out = Markdown.parse("before\n```kotlin\nval x = **1**\n```\nafter")
        assertEquals(
            listOf(
                MdBlock.Paragraph(listOf(MdSpan.Text("before"))),
                MdBlock.Code("kotlin", "val x = **1**"),
                MdBlock.Paragraph(listOf(MdSpan.Text("after"))),
            ),
            out,
        )
    }

    @Test
    fun fenceWithTrailingSpacesAndNoLang() {
        assertEquals(listOf(MdBlock.Code(null, "a")), Markdown.parse("```  \na\n```  "))
    }

    @Test
    fun unterminatedFenceRendersAsCode() {
        assertEquals(listOf(MdBlock.Code("sh", "ls\npwd")), Markdown.parse("```sh\nls\npwd"))
    }

    @Test
    fun inlineBoldAndCode() {
        val out = Markdown.parse("is **6.17** (`x`)")
        assertEquals(
            listOf(
                MdBlock.Paragraph(
                    listOf(
                        MdSpan.Text("is "),
                        MdSpan.Bold("6.17"),
                        MdSpan.Text(" ("),
                        MdSpan.Code("x"),
                        MdSpan.Text(")"),
                    ),
                ),
            ),
            out,
        )
    }

    @Test
    fun realReplySample() {
        val out = Markdown.parse("The kernel version is **6.17.2-1-pve** (Proxmox VE kernel, x86_64).")
        assertEquals(
            listOf(
                MdBlock.Paragraph(
                    listOf(
                        MdSpan.Text("The kernel version is "),
                        MdSpan.Bold("6.17.2-1-pve"),
                        MdSpan.Text(" (Proxmox VE kernel, x86_64)."),
                    ),
                ),
            ),
            out,
        )
    }

    @Test
    fun bulletsBecomeList() {
        val out = Markdown.parse("- one\n* two **b**\n\npara")
        assertEquals(
            listOf(
                MdBlock.Bullets(
                    listOf(
                        listOf(MdSpan.Text("one")),
                        listOf(MdSpan.Text("two "), MdSpan.Bold("b")),
                    ),
                ),
                MdBlock.Paragraph(listOf(MdSpan.Text("para"))),
            ),
            out,
        )
    }

    @Test
    fun plainTextIsOneParagraph() {
        assertEquals(
            listOf(MdBlock.Paragraph(listOf(MdSpan.Text("hello world")))),
            Markdown.parse("hello world"),
        )
    }

    @Test
    fun tripleBackticksInsideInlineDoNotCrash() {
        val out = Markdown.parse("a ```b``` c")
        assertTrue(out.single() is MdBlock.Paragraph)
    }

    @Test
    fun italicAndUnmatchedMarkersStayLiteral() {
        assertEquals(
            listOf(MdBlock.Paragraph(listOf(MdSpan.Text("a "), MdSpan.Italic("b"), MdSpan.Text(" "), MdSpan.Italic("c")))),
            Markdown.parse("a *b* _c_"),
        )
        assertEquals(
            listOf(MdBlock.Paragraph(listOf(MdSpan.Text("a **b and `c and *d")))),
            Markdown.parse("a **b and `c and *d"),
        )
        assertEquals(
            listOf(MdBlock.Paragraph(listOf(MdSpan.Text("snake_case_name")))),
            Markdown.parse("snake_case_name"),
        )
    }

    @Test
    fun blankLinesAndCrlf() {
        assertEquals(emptyList<MdBlock>(), Markdown.parse(""))
        assertEquals(emptyList<MdBlock>(), Markdown.parse("\n\n  \n"))
        assertEquals(
            listOf(
                MdBlock.Paragraph(listOf(MdSpan.Text("a"))),
                MdBlock.Paragraph(listOf(MdSpan.Text("b"))),
            ),
            Markdown.parse("\n\na\r\n\r\nb\n\n"),
        )
    }

    @Test
    fun randomInputNeverThrows() {
        val alphabet = "*_`\n -#abc"
        val rnd = Random(42)
        repeat(500) {
            val s = buildString { repeat(rnd.nextInt(0, 60)) { append(alphabet[rnd.nextInt(alphabet.length)]) } }
            Markdown.parse(s)
        }
    }
}
