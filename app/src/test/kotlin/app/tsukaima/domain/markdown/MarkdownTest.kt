package app.tsukaima.domain.markdown

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
                        MdListItem(listOf(MdSpan.Text("one")), 0),
                        MdListItem(listOf(MdSpan.Text("two "), MdSpan.Bold("b")), 0),
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
        val alphabet = "*_`\n -#abc$\\[]x"
        val rnd = Random(42)
        repeat(500) {
            val s = buildString { repeat(rnd.nextInt(0, 60)) { append(alphabet[rnd.nextInt(alphabet.length)]) } }
            Markdown.parse(s)
        }
    }

    @Test
    fun oneLineTripleBacktickIsInlineCodeAndDoesNotSwallowTheRest() {
        val blocks = Markdown.parse("```ls -la```\nthen this\n\nand this")
        assertEquals(
            listOf(
                MdBlock.Paragraph(listOf(MdSpan.Code("ls -la"), MdSpan.Text("\nthen this"))),
                MdBlock.Paragraph(listOf(MdSpan.Text("and this"))),
            ),
            blocks,
        )
    }

    @Test
    fun fourBacktickFenceClosesAndKeepsInnerTripleFence() {
        val blocks = Markdown.parse("````md\n```kotlin\nval x = 1\n```\n````\nafter")
        assertEquals(
            listOf(
                MdBlock.Code("md", "```kotlin\nval x = 1\n```"),
                MdBlock.Paragraph(listOf(MdSpan.Text("after"))),
            ),
            blocks,
        )
    }

    @Test
    fun codeSpanNeedsAMatchingRun() {
        assertEquals(
            listOf(MdBlock.Paragraph(listOf(MdSpan.Text("a "), MdSpan.Code("b`c"), MdSpan.Text(" d")))),
            Markdown.parse("a ``b`c`` d"),
        )
        assertEquals(listOf(MdBlock.Paragraph(listOf(MdSpan.Text("x `` y")))), Markdown.parse("x `` y"))
    }

    private fun t(s: String) = listOf(MdSpan.Text(s))
    private fun para(vararg spans: MdSpan) = MdBlock.Paragraph(spans.toList())

    @Test
    fun tableWithAlignment() {
        val out = Markdown.parse("| Name | Kind | Size |\n|:--|:-:|--:|\n| a | **dir** | 4 |\n| b | file | 12 |")
        assertEquals(
            listOf(
                MdBlock.Table(
                    header = listOf(t("Name"), t("Kind"), t("Size")),
                    align = listOf(MdAlign.Start, MdAlign.Center, MdAlign.End),
                    rows = listOf(
                        listOf(t("a"), listOf(MdSpan.Bold("dir")), t("4")),
                        listOf(t("b"), t("file"), t("12")),
                    ),
                ),
            ),
            out,
        )
    }

    @Test
    fun tableWithoutOuterPipesAfterAParagraph() {
        val out = Markdown.parse("Here:\na | b\n--- | ---\n1 | 2\n\nafter")
        assertEquals(
            listOf(
                para(MdSpan.Text("Here:")),
                MdBlock.Table(listOf(t("a"), t("b")), listOf(MdAlign.Start, MdAlign.Start), listOf(listOf(t("1"), t("2")))),
                para(MdSpan.Text("after")),
            ),
            out,
        )
    }

    @Test
    fun tableEscapedPipeIsLiteral() {
        val table = Markdown.parse("| cmd | note |\n|---|---|\n| `a \\| b` | x\\|y |").single() as MdBlock.Table
        assertEquals(listOf(listOf(listOf(MdSpan.Code("a | b")), t("x|y"))), table.rows)
    }

    @Test
    fun tableRaggedRowsArePaddedAndTruncated() {
        val table = Markdown.parse("| a | b | c |\n|---|---|---|\n| 1 |\n| 1 | 2 | 3 | 4 |").single() as MdBlock.Table
        assertEquals(
            listOf(listOf(t("1"), emptyList(), emptyList()), listOf(t("1"), t("2"), t("3"))),
            table.rows,
        )
    }

    @Test
    fun tableSeparatorWithWrongCellCountIsNoTable() {
        val out = Markdown.parse("| a | b |\n|---|")
        assertTrue(out.single() is MdBlock.Paragraph)
    }

    @Test
    fun tableHeaderWithoutSeparatorStaysAParagraph() {
        assertEquals(listOf(para(MdSpan.Text("| a | b |"))), Markdown.parse("| a | b |", streaming = true))
        assertEquals(
            listOf(para(MdSpan.Text("Compare:\n| a | b |"))),
            Markdown.parse("Compare:\n| a | b |", streaming = true),
        )
    }

    @Test
    fun streamingTableRendersTheHalfRow() {
        val out = Markdown.parse("| a | b |\n|---|---|\n| 1 | 2 |\n| 3", streaming = true)
        val table = out.single() as MdBlock.Table
        assertEquals(listOf(listOf(t("1"), t("2")), listOf(t("3"), emptyList())), table.rows)
        // Separator in, no rows yet: an empty table already.
        assertEquals(emptyList<List<List<MdSpan>>>(), (Markdown.parse("| a | b |\n|---|---|", streaming = true).single() as MdBlock.Table).rows)
    }

    @Test
    fun mediaLineNeverStartsATable() {
        val out = Markdown.parse("MEDIA:/tmp/a|b.png\n|---|---|")
        assertEquals(MdBlock.Media("/tmp/a|b.png"), out.first())
    }

    @Test
    fun atxHeadings() {
        val out = Markdown.parse("# One\n## Two ##\n### Three\n#### Four\n##### Five\n###### Six\n####### Seven\n#nospace\n## C#")
        assertEquals(
            listOf(
                MdBlock.Heading(1, t("One")),
                MdBlock.Heading(2, t("Two")),
                MdBlock.Heading(3, t("Three")),
                MdBlock.Heading(4, t("Four")),
                MdBlock.Heading(5, t("Five")),
                MdBlock.Heading(6, t("Six")),
                para(MdSpan.Text("####### Seven\n#nospace")),
                MdBlock.Heading(2, t("C#")),
            ),
            out,
        )
    }

    @Test
    fun setextHeadingsTakeOnlyTheLineAbove() {
        val out = Markdown.parse("intro\nTitle\n===\nSub **b**\n---\ntext")
        assertEquals(
            listOf(
                para(MdSpan.Text("intro")),
                MdBlock.Heading(1, t("Title")),
                MdBlock.Heading(2, listOf(MdSpan.Text("Sub "), MdSpan.Bold("b"))),
                para(MdSpan.Text("text")),
            ),
            out,
        )
    }

    @Test
    fun rulesVersusSetext() {
        assertEquals(
            listOf(para(MdSpan.Text("a")), MdBlock.Rule, para(MdSpan.Text("b")), MdBlock.Rule, MdBlock.Rule),
            Markdown.parse("a\n\n---\nb\n\n***\n_ _ _"),
        )
        assertEquals(listOf(MdBlock.Rule), Markdown.parse("- - -"))
        assertEquals(listOf(MdBlock.Heading(2, t("a"))), Markdown.parse("a\n---"))
    }

    @Test
    fun orderedListWithParenMarkersAndANestedBullet() {
        val out = Markdown.parse("1) first\n  - nested\n2) second\n3) third")
        assertEquals(
            listOf(
                MdBlock.Numbered(
                    listOf(
                        MdListItem(t("first"), 0, 1),
                        MdListItem(t("nested"), 1),
                        MdListItem(t("second"), 0, 2),
                        MdListItem(t("third"), 0, 3),
                    ),
                ),
            ),
            out,
        )
    }

    @Test
    fun orderedListNumbersFromItsFirstMarker() {
        val out = Markdown.parse("1. a\n1. b\n\n4. c\n5. d") // agents often write 1. for every item
        assertEquals(
            listOf(
                MdBlock.Numbered(listOf(MdListItem(t("a"), 0, 1), MdListItem(t("b"), 0, 2))),
                MdBlock.Numbered(listOf(MdListItem(t("c"), 0, 4), MdListItem(t("d"), 0, 5))),
            ),
            out,
        )
    }

    @Test
    fun nestedListDepthsFollowIndentation() {
        val out = Markdown.parse("- a\n  - b\n    + c\n      - d\n        - e\n- f\n    1. four-space nested")
        val items = (out.single() as MdBlock.Bullets).items
        assertEquals(listOf(0, 1, 2, 3, 3, 0, 1), items.map { it.depth })
        assertEquals(1, items.last().number)
    }

    @Test
    fun indentedLineContinuesTheItem() {
        val out = Markdown.parse("- first line\n  wraps **here**\n- second\nafter")
        assertEquals(
            listOf(
                MdBlock.Bullets(
                    listOf(
                        MdListItem(listOf(MdSpan.Text("first line wraps "), MdSpan.Bold("here")), 0),
                        MdListItem(t("second"), 0),
                    ),
                ),
                para(MdSpan.Text("after")),
            ),
            out,
        )
    }

    @Test
    fun listSwitchingMarkerTypeAtTopLevelStartsANewList() {
        val out = Markdown.parse("- a\n1. b")
        assertEquals(
            listOf(MdBlock.Bullets(listOf(MdListItem(t("a"), 0))), MdBlock.Numbered(listOf(MdListItem(t("b"), 0, 1)))),
            out,
        )
    }

    @Test
    fun indentedFenceInsideAListKeepsItsBodyDedented() {
        val out = Markdown.parse("1. Run:\n   ```sh\n   ls -la\n     nested\n   ```\n2. Done")
        assertEquals(
            listOf(
                MdBlock.Numbered(listOf(MdListItem(t("Run:"), 0, 1))),
                MdBlock.Code("sh", "ls -la\n  nested"),
                MdBlock.Numbered(listOf(MdListItem(t("Done"), 0, 2))),
            ),
            out,
        )
    }

    @Test
    fun blockquoteContainingABulletList() {
        val out = Markdown.parse("> Note:\n> - one\n> - two\n>\n> end\nafter")
        assertEquals(
            listOf(
                MdBlock.Quote(
                    listOf(
                        para(MdSpan.Text("Note:")),
                        MdBlock.Bullets(listOf(MdListItem(t("one"), 0), MdListItem(t("two"), 0))),
                        para(MdSpan.Text("end")),
                    ),
                ),
                para(MdSpan.Text("after")),
            ),
            out,
        )
    }

    @Test
    fun nestedQuotes() {
        assertEquals(
            listOf(MdBlock.Quote(listOf(MdBlock.Quote(listOf(para(MdSpan.Text("deep"))))))),
            Markdown.parse("> > deep"),
        )
    }

    @Test
    fun linksAndAutolinks() {
        assertEquals(
            listOf(
                para(
                    MdSpan.Text("See "),
                    MdSpan.Link("the docs", "https://ex.com/a_(b)"),
                    MdSpan.Text(", "),
                    MdSpan.Link("mail", "mailto:x@y.z"),
                    MdSpan.Text(" and local or "),
                    MdSpan.Link("https://ex.com/p?q=1", "https://ex.com/p?q=1"),
                    MdSpan.Text("."),
                ),
            ),
            Markdown.parse("See [the docs](https://ex.com/a_(b)), [mail](mailto:x@y.z) and [local](file.txt) or https://ex.com/p?q=1."),
        )
    }

    @Test
    fun autolinkStopsAtTrailingPunctuationAndUnbalancedParen() {
        assertEquals(
            listOf(para(MdSpan.Text("("), MdSpan.Link("http://a.b/c", "http://a.b/c"), MdSpan.Text("):"))),
            Markdown.parse("(http://a.b/c):"),
        )
        assertEquals(
            listOf(para(MdSpan.Link("https://en.wikipedia.org/wiki/Foo_(bar)", "https://en.wikipedia.org/wiki/Foo_(bar)"))),
            Markdown.parse("https://en.wikipedia.org/wiki/Foo_(bar)"),
        )
        assertEquals(listOf(para(MdSpan.Text("xhttps://a.b"))), Markdown.parse("xhttps://a.b"))
        assertEquals(listOf(para(MdSpan.Text("https://"))), Markdown.parse("https://"))
    }

    @Test
    fun halfStreamedLinkStaysText() {
        assertEquals(listOf(para(MdSpan.Text("see [docs](https://ex"))), Markdown.parse("see [docs](https://ex", streaming = true))
        assertEquals(listOf(para(MdSpan.Text("see [docs"))), Markdown.parse("see [docs", streaming = true))
    }

    @Test
    fun strikethrough() {
        assertEquals(
            listOf(para(MdSpan.Strike("old"), MdSpan.Text(" new ~~open"))),
            Markdown.parse("~~old~~ new ~~open"),
        )
    }

    @Test
    fun imageBlockOnlyForHttpUrls() {
        assertEquals(
            listOf(MdBlock.Image("chart", "https://example.com/c.png")),
            Markdown.parse("![chart](https://example.com/c.png)"),
        )
        // Surrounding whitespace is fine, a title is dropped, and an empty alt stays empty.
        assertEquals(
            listOf(
                MdBlock.Paragraph(listOf(MdSpan.Text("Here:"))),
                MdBlock.Image("", "http://h:9119/a.jpg"),
                MdBlock.Paragraph(listOf(MdSpan.Text("Done."))),
            ),
            Markdown.parse("Here:\n   ![](http://h:9119/a.jpg \"title\")  \nDone."),
        )
        // Not http(s): a relative image reads as its alt text, a mailto: one as a link.
        assertEquals(listOf(para(MdSpan.Text("logo"))), Markdown.parse("![logo](img/logo.png)"))
        assertEquals(
            listOf(para(MdSpan.Link("me", "mailto:a@b.c"))),
            Markdown.parse("![me](mailto:a@b.c)"),
        )
        // A link is not an image, and an image with text after it is not a whole line.
        assertEquals(listOf(para(MdSpan.Link("x", "https://e.com/x.png"))), Markdown.parse("[x](https://e.com/x.png)"))
        assertEquals(
            listOf(para(MdSpan.Link("a", "https://e.com/a.png"), MdSpan.Text(" and more"))),
            Markdown.parse("![a](https://e.com/a.png) and more"),
        )
    }

    @Test
    fun inlineImageStaysALink() {
        assertEquals(
            listOf(para(MdSpan.Text("See "), MdSpan.Link("plot", "https://e.com/p.png"), MdSpan.Text(" here."))),
            Markdown.parse("See ![plot](https://e.com/p.png) here."),
        )
        // Two images on one line are not one image.
        assertEquals(
            listOf(para(MdSpan.Link("a", "https://e.com/a.png"), MdSpan.Text(" "), MdSpan.Link("b", "https://e.com/b.png"))),
            Markdown.parse("![a](https://e.com/a.png) ![b](https://e.com/b.png)"),
        )
    }

    @Test
    fun imageLineInsideAListItemStaysInTheItem() {
        val out = Markdown.parse("- shot\n  ![s](https://e.com/s.png)")
        assertEquals(1, out.size)
        assertTrue(out.single() is MdBlock.Bullets)
    }

    @Test
    fun imageInAQuote() {
        assertEquals(
            listOf(MdBlock.Quote(listOf(MdBlock.Image("q", "https://e.com/q.png")))),
            Markdown.parse("> ![q](https://e.com/q.png)"),
        )
    }

    @Test
    fun pathologicalInputsDoNotThrow() {
        val inputs = listOf(
            "|||", "|", "||\n||", "|\n|-|", "[", "](", "[a](", "[a](b", "~~", "~~~~", "> > >", ">", "> ```", "#", "# ",
            "######", "1.", "1. ", "- ", "-", "---|---", "|:-:|\n|:-:|", "\\|", "| \\", "http://", "![", "![a](http://x)",
            ">".repeat(10_000) + " deep", "[".repeat(10_000), "|".repeat(10_000) + "\n" + "|-".repeat(5_000) + "|",
            "  ".repeat(500) + "- deep",
        )
        for (s in inputs) {
            Markdown.parse(s)
            Markdown.parse(s, streaming = true)
        }
    }

    @Test
    fun randomStructuredInputNeverThrows() {
        val alphabet = "*_`~|[]()>#:-=+.1 \n\\habcp/"
        val rnd = Random(7)
        repeat(2_000) {
            val s = buildString { repeat(rnd.nextInt(0, 120)) { append(alphabet[rnd.nextInt(alphabet.length)]) } }
            Markdown.parse(s)
            Markdown.parse(s, streaming = true)
        }
    }

    @Test
    fun longUnbalancedLinesParseInLinearTime() {
        val n = 200_000
        val inputs = listOf("[".repeat(n), "[a]".repeat(n / 3), "~~a".repeat(n / 3), "http://a ".repeat(n / 9), "| a ".repeat(n / 4))
        val start = System.nanoTime()
        for (s in inputs) Markdown.parse(s)
        assertTrue("took too long", System.nanoTime() - start < 5_000_000_000L)
    }

    @Test
    fun linksAndCodeInsideEmphasisKeepTheEmphasis() {
        assertEquals(
            listOf(
                para(
                    MdSpan.Bold("see "),
                    MdSpan.Link("docs", "https://x.io", bold = true),
                    MdSpan.Bold(" or "),
                    MdSpan.Code("man ls"),
                    MdSpan.Text(", "),
                    MdSpan.Link("https://y.io", "https://y.io", italic = true),
                    MdSpan.Text(" "),
                    MdSpan.Link("old", "https://z.io", strike = true),
                ),
            ),
            Markdown.parse("**see [docs](https://x.io) or `man ls`**, *https://y.io* ~~[old](https://z.io)~~"),
        )
        // Emphasis markers inside emphasis stay literal, as before.
        assertEquals(listOf(para(MdSpan.Bold("a *b* [c"))), Markdown.parse("**a *b* [c**"))
    }

    @Test
    fun backslashEscapesRenderThePunctuation() {
        assertEquals(listOf(para(MdSpan.Text("*not italic*"))), Markdown.parse("\\*not italic\\*"))
        assertEquals(
            listOf(para(MdSpan.Text("_a_ `b` [c](d) # ~~e~~ \\ \\q"))),
            Markdown.parse("\\_a\\_ \\`b\\` \\[c\\](d) \\# \\~\\~e\\~\\~ \\\\ \\q"),
        )
        // An escaped closer does not close: the italic runs to the next real `*`.
        assertEquals(listOf(para(MdSpan.Italic("a * b"))), Markdown.parse("*a \\* b*"))
        // Code spans are literal.
        assertEquals(listOf(para(MdSpan.Code("\\*x\\*"))), Markdown.parse("`\\*x\\*`"))
        // Inside emphasis too.
        assertEquals(listOf(para(MdSpan.Bold("a*b"))), Markdown.parse("**a\\*b**"))
    }

    @Test
    fun escapedPipeInATableCellIsUnescapedOnce() {
        val table = Markdown.parse("| a |\n|---|\n| x \\| y |").single() as MdBlock.Table
        assertEquals(listOf(t("x | y")), table.rows.single())
    }

    @Test
    fun escapedDollarIsNotMath() {
        assertEquals(listOf(para(MdSpan.Text("costs $5 and $6"))), Markdown.parse("costs \\$5 and \\$6"))
        assertEquals(listOf(para(MdSpan.Text("\$x$"))), Markdown.parse("\\\$x\\$"))
    }

    @Test
    fun taskListItemsCarryChecked() {
        assertEquals(
            listOf(
                MdBlock.Bullets(
                    listOf(
                        MdListItem(t("a"), 0, checked = false),
                        MdListItem(t("b"), 0, checked = true),
                        MdListItem(t("c"), 0, checked = true),
                        MdListItem(t("plain"), 0),
                    ),
                ),
            ),
            Markdown.parse("- [ ] a\n- [x] b\n- [X] c\n- plain"),
        )
        assertEquals(
            listOf(MdBlock.Numbered(listOf(MdListItem(t("d"), 0, 1, checked = true), MdListItem(t("e"), 1, checked = false)))),
            Markdown.parse("1. [x] d\n   * [ ] e"),
        )
        // No text after the box, or no space: not a task.
        assertEquals(listOf(MdBlock.Bullets(listOf(MdListItem(t("[ ]"), 0), MdListItem(t("[x]b"), 0)))), Markdown.parse("- [ ]\n- [x]b"))
    }

    @Test
    fun inlineMathBetweenDollars() {
        assertEquals(
            listOf(para(MdSpan.Text("area "), MdSpan.Math("\\pi r^2"), MdSpan.Text(" here"))),
            Markdown.parse("area $\\pi r^2$ here"),
        )
        assertEquals(
            listOf(para(MdSpan.Text("a "), MdSpan.Math("x_1 + y_2"), MdSpan.Text(" b "), MdSpan.Math("\\alpha"), MdSpan.Text(" c [z]"))),
            Markdown.parse("a \\(x_1 + y_2\\) b $$\\alpha$$ c \\[z\\]"),
        )
        // Math inside emphasis is split out, like code.
        assertEquals(listOf(para(MdSpan.Bold("is "), MdSpan.Math("x"))), Markdown.parse("**is \$x$**"))
    }

    @Test
    fun dollarAmountsAreNotMath() {
        assertEquals(listOf(para(MdSpan.Text("costs $5 and $10 today"))), Markdown.parse("costs $5 and $10 today"))
        assertEquals(listOf(para(MdSpan.Text("$ 5 $"))), Markdown.parse("$ 5 $"))
        assertEquals(listOf(para(MdSpan.Text("from \$a$5"))), Markdown.parse("from \$a$5"))
    }

    @Test
    fun displayMathBlock() {
        assertEquals(listOf(MdBlock.Math("\\frac{a}{b}")), Markdown.parse("$$\n\\frac{a}{b}\n$$"))
        assertEquals(
            listOf(para(MdSpan.Text("Then:")), MdBlock.Math("a\n= b"), para(MdSpan.Text("done"))),
            Markdown.parse("Then:\n$\$a\n= b$$\ndone"),
        )
        assertEquals(listOf(MdBlock.Math("E = mc^2")), Markdown.parse("$\$E = mc^2$$"))
        assertEquals(listOf(MdBlock.Math("x^2")), Markdown.parse("  \\[\n  x^2\n  \\]"))
        assertEquals(listOf(MdBlock.Math("- y\n# z")), Markdown.parse("$$\n- y\n# z\n$$"))
    }

    @Test
    fun unbalancedDollarsStayText() {
        assertEquals(listOf(para(MdSpan.Text("a \$x b"))), Markdown.parse("a \$x b"))
        assertEquals(listOf(para(MdSpan.Text("$$\n\\frac{a}{b}"))), Markdown.parse("$$\n\\frac{a}{b}"))
        assertEquals(listOf(para(MdSpan.Text("$$\\frac{a}{b}"))), Markdown.parse("$$\\frac{a}{b}", streaming = true))
        assertEquals(listOf(para(MdSpan.Text("(x"))), Markdown.parse("\\(x"))
        assertEquals(listOf(para(MdSpan.Text("\$a\nb$"))), Markdown.parse("\$a\nb$"))
    }

    @Test
    fun manyDollarsAndBackslashesParseInLinearTime() {
        val n = 200_000
        val inputs = listOf("\$a ".repeat(n / 3), "\\".repeat(n), "\\(".repeat(n / 2), "$$\n".repeat(n / 3), "$\$a\n".repeat(n / 3), "\\[a\n".repeat(n / 3), "*a\\*".repeat(n / 4))
        val start = System.nanoTime()
        for (s in inputs) Markdown.parse(s)
        assertTrue("took too long", System.nanoTime() - start < 5_000_000_000L)
    }

    @Test
    fun displayMathSearchStopsAtABlankLine() {
        assertEquals(
            listOf(para(MdSpan.Text("$$" + "a = b")), MdBlock.Heading(1, t("Title")), para(MdSpan.Text("more $$"))),
            Markdown.parse("$$" + "a = b\n\n# Title\n\nmore $$"),
        )
        assertEquals(listOf(para(MdSpan.Text("$$\nx")), MdBlock.Math("y")), Markdown.parse("$$\nx\n\n$$" + "y$$"))
    }

    @Test
    fun anEscapedBackslashBeforeTheDollarStillCloses() {
        assertEquals(listOf(para(MdSpan.Math("a\\\\"))), Markdown.parse("\$a\\\\\$"))
        assertEquals(listOf(para(MdSpan.Text("\$a\$"))), Markdown.parse("\$a\\\$"))
    }
}
