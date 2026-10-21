package app.tsukaima.ui.chat.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.tsukaima.domain.markdown.MdBlock
import app.tsukaima.domain.markdown.MdListItem
import app.tsukaima.domain.markdown.MdSpan
import app.tsukaima.domain.markdown.Markdown
import app.tsukaima.ui.theme.Motion
import app.tsukaima.ui.theme.TsukaimaColors
import app.tsukaima.ui.theme.TsukaimaType
import app.tsukaima.ui.theme.tsukaima

private const val CaretId = "caret"

private fun codeStyle(colors: TsukaimaColors) = SpanStyle(
    fontFamily = TsukaimaType.mono.fontFamily,
    fontSize = TsukaimaType.mono.fontSize,
    background = colors.surfaceSunken,
)

private fun AnnotatedString.Builder.appendSpans(spans: List<MdSpan>, colors: TsukaimaColors, math: Set<String>) {
    for (span in spans) when (span) {
        is MdSpan.Text -> append(span.s)
        is MdSpan.Bold -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(span.s) }
        is MdSpan.Italic -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(span.s) }
        is MdSpan.Code -> withStyle(codeStyle(colors)) { append(span.s) }
        is MdSpan.Math -> {
            val id = mathId(span.tex)
            if (id in math) appendInlineContent(id, span.tex) else withStyle(codeStyle(colors)) { append(span.tex) }
        }
        is MdSpan.Strike -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough, color = colors.ink2)) { append(span.s) }
        is MdSpan.Link -> withLink(
            LinkAnnotation.Url(
                span.url,
                TextLinkStyles(
                    SpanStyle(
                        color = colors.accent,
                        fontWeight = if (span.bold) FontWeight.SemiBold else null,
                        fontStyle = if (span.italic) FontStyle.Italic else null,
                        textDecoration = if (span.strike) {
                            TextDecoration.combine(listOf(TextDecoration.Underline, TextDecoration.LineThrough))
                        } else {
                            TextDecoration.Underline
                        },
                    ),
                ),
            ),
        ) { append(span.text) }
    }
}

/**
 * [spans] styled for a `Text`: links are tappable [LinkAnnotation.Url]s that open the browser. A math span whose
 * [mathId] is in [math] is an inline image (the `Text` gets its [rememberMathContent]); any other shows its TeX as code.
 */
internal fun markdownInline(
    spans: List<MdSpan>,
    colors: TsukaimaColors,
    caret: Boolean = false,
    math: Set<String> = emptySet(),
): AnnotatedString =
    buildAnnotatedString {
        appendSpans(spans, colors, math)
        if (caret) appendInlineContent(CaretId, "|")
    }

/** [markdownInline], rebuilt only when its input changes (not on every caret blink or parent recomposition). */
@Composable
private fun rememberInline(spans: List<MdSpan>, colors: TsukaimaColors, caret: Boolean, math: Set<String>): AnnotatedString =
    remember(spans, colors, caret, math) { markdownInline(spans, colors, caret, math) }

/**
 * [spans] as a `Text` in [style]: the annotated string and its inline content (the caret and any drawn formulas).
 * With math, the paragraph's width is measured first so a formula wider than it can shrink or clip to fit
 * ([rememberMathContent]); text without math is laid out directly.
 */
@Composable
private fun InlineText(
    spans: List<MdSpan>,
    style: TextStyle,
    caret: Boolean,
    caretContent: Map<String, InlineTextContent>,
    modifier: Modifier = Modifier,
) {
    if (spans.none { it is MdSpan.Math }) {
        InlineTextAt(spans, style, caret, caretContent, Int.MAX_VALUE, modifier)
        return
    }
    BoxWithConstraints(modifier) {
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else Int.MAX_VALUE
        InlineTextAt(spans, style, caret, caretContent, width, Modifier)
    }
}

/** [InlineText] with its formulas fitted to [maxWidth] pixels. */
@Composable
private fun InlineTextAt(
    spans: List<MdSpan>,
    style: TextStyle,
    caret: Boolean,
    caretContent: Map<String, InlineTextContent>,
    maxWidth: Int,
    modifier: Modifier,
) {
    val colors = MaterialTheme.tsukaima
    val math = rememberMathContent(spans, style, colors.ink1, maxWidth)
    Text(
        text = rememberInline(spans, colors, caret, math.keys),
        style = style,
        color = colors.ink1,
        inlineContent = if (math.isEmpty()) caretContent else caretContent + math,
        modifier = modifier,
    )
}

/**
 * Renders parsed markdown blocks; `MEDIA:` blocks become [MediaCard]s and whole-line images [ImageCard]s, both
 * served by [media], tables [MarkdownTable]s and display math [MathBlock]s. With [streaming], a blinking accent caret is appended inline to
 * the last text run (so it follows wrapping) of a trailing paragraph, list or heading; after any other trailing
 * block (code, table, quote, rule, file card, image), or when empty, it sits on its own line.
 */
@Composable
fun MarkdownText(
    blocks: List<MdBlock>,
    modifier: Modifier = Modifier,
    streaming: Boolean = false,
    media: MediaHost = MediaHost(),
) {
    val colors = MaterialTheme.tsukaima
    val last = blocks.lastOrNull()
    val caretInline = streaming &&
        (last is MdBlock.Paragraph || last is MdBlock.Bullets || last is MdBlock.Numbered || last is MdBlock.Heading)
    val caretContent = caretInlineContent()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEachIndexed { index, block ->
            val caret = caretInline && index == blocks.lastIndex
            when (block) {
                is MdBlock.Paragraph -> InlineText(block.spans, TsukaimaType.body, caret, caretContent)
                is MdBlock.Heading -> InlineText(
                    spans = block.spans,
                    style = headingStyle(block.level),
                    caret = caret,
                    caretContent = caretContent,
                    modifier = if (index > 0) Modifier.padding(top = 4.dp) else Modifier,
                )
                is MdBlock.Code -> CodeBlock(code = block.code, lang = block.lang)
                is MdBlock.Media -> MediaCard(block.path, media)
                is MdBlock.Image -> ImageCard(block.alt, block.url, media)
                is MdBlock.Bullets -> MarkdownList(block.items, caret, caretContent)
                is MdBlock.Numbered -> MarkdownList(block.items, caret, caretContent)
                is MdBlock.Table -> MarkdownTable(block)
                is MdBlock.Math -> MathBlock(block.tex)
                is MdBlock.Quote -> MarkdownText(
                    blocks = block.blocks,
                    media = media,
                    modifier = Modifier
                        .drawBehind { drawRect(colors.accentSelected, size = Size(2.dp.toPx(), size.height)) }
                        .padding(start = 12.dp),
                )
                MdBlock.Rule -> Box(
                    Modifier
                        .padding(vertical = 4.dp)
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(colors.hairline),
                )
            }
        }
        if (streaming && !caretInline) {
            Text(
                text = buildAnnotatedString { appendInlineContent(CaretId, "|") },
                style = TsukaimaType.body,
                inlineContent = caretContent,
            )
        }
    }
}

/** Levels 1–2 in the serif (19sp, 17sp), 3 at 15sp semibold, 4–6 at UI size semibold. */
private fun headingStyle(level: Int): TextStyle = when (level) {
    1 -> TsukaimaType.titleSmall
    2 -> TsukaimaType.sectionTitle
    3 -> TsukaimaType.ui.copy(fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
    else -> TsukaimaType.ui.copy(fontWeight = FontWeight.SemiBold)
}

/**
 * A bullet or numbered list: numbers right-aligned in a two-digit column, bullets `•` / `◦` / `▪` by depth, each
 * nesting level 16dp further in. A task item shows `☐` / `☑` in place of its bullet (after its number when
 * numbered). The caret, if any, follows the last item.
 */
@Composable
private fun MarkdownList(items: List<MdListItem>, caret: Boolean, caretContent: Map<String, InlineTextContent>) {
    val colors = MaterialTheme.tsukaima
    // Two digits and the dot (about 1.6 em of the body size, so it follows font scaling): 1–99 share one column;
    // only a 100th item grows it rather than clipping.
    val numberColumn = with(LocalDensity.current) { (TsukaimaType.body.fontSize * 1.6f).toDp() }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items.forEachIndexed { i, item ->
            Row(
                modifier = Modifier.padding(start = 16.dp * item.depth),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val number = item.number
                val box = item.checked?.let { if (it) "☑" else "☐" }
                if (number != null) {
                    Text(
                        "$number.",
                        style = TsukaimaType.body,
                        color = colors.ink2,
                        textAlign = TextAlign.End,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.widthIn(min = numberColumn),
                    )
                } else if (box == null) {
                    val glyph = when (item.depth) { 0 -> "•"; 1 -> "◦"; else -> "▪" }
                    Text(glyph, style = TsukaimaType.body, color = colors.ink2)
                }
                if (box != null) {
                    Text(
                        box,
                        style = TsukaimaType.body,
                        color = colors.ink2,
                        modifier = Modifier.semantics {
                            stateDescription = if (item.checked == true) "Checked" else "Not checked"
                        },
                    )
                }
                InlineText(
                    spans = item.spans,
                    style = TsukaimaType.body,
                    caret = caret && i == items.lastIndex,
                    caretContent = caretContent,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** 2dp accent caret, as tall as the body line, blinking in two hard steps over 1 s. */
@Composable
private fun caretInlineContent(): Map<String, InlineTextContent> {
    val density = LocalDensity.current
    val lineHeight = TsukaimaType.body.lineHeight
    return remember(density, lineHeight) {
        val width = with(density) { 4.dp.toSp() }
        mapOf(
            CaretId to InlineTextContent(
                Placeholder(width, lineHeight, PlaceholderVerticalAlign.TextCenter),
            ) { Caret() },
        )
    }
}

@Composable
private fun Caret() {
    val transition = rememberInfiniteTransition(label = "caret")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = Motion.caretMs
                1f at 0
                1f at Motion.caretMs / 2 - 1
                0f at Motion.caretMs / 2
                0f at Motion.caretMs - 1
            },
            repeatMode = RepeatMode.Restart,
        ),
        label = "caretAlpha",
    )
    Box(
        Modifier
            .padding(start = 2.dp)
            .width(2.dp)
            .fillMaxHeight()
            .alpha(alpha)
            .background(MaterialTheme.tsukaima.accent),
    )
}

private const val SampleMd = "The cron job was failing because of a **missing path**. Try *this*:\n\n" +
    "```bash\nsystemctl restart hermes-agent\n```\n\n- first `item`\n- second item that is long enough to wrap across more than one line on a phone"

private const val RichMd = "## Services\n\n| Service | State | Uptime |\n|:--|:-:|--:|\n| hermes-agent | **running** | 3 d |\n" +
    "| matrix-bridge | running, though its last sync stalled twice overnight | 41 min |\n\n" +
    "### Next steps\n\n1. Restart the bridge\n   - check `journalctl -u matrix-bridge`\n     - look for sync errors\n2. ~~Reboot the host~~ not needed\n\n" +
    "> The watchdog restarted it at 03:12.\n> - once\n\n---\n\nDocs: [Hermes guide](https://example.com/hermes) or https://example.com/status."

private const val MathMd = "The roots of \$ax^2 + bx + c = 0\$ are\n\n" +
    "$$\nx = \\frac{-b \\pm \\sqrt{b^2 - 4ac}}{2a}\n$$\n\n" +
    "- [x] derive \\(\\Delta = b^2 - 4ac\\)\n- [ ] check \$\\Delta < 0\$\n- [ ] costs \$5 and \$10, not math\n\n" +
    "$$\\sum_{i=1}^{n} i = \\frac{n(n+1)}{2} \\quad \\int_0^\\infty e^{-x^2}\\,dx = \\frac{\\sqrt{\\pi}}{2}$$\n\n" +
    "Broken: \$\\frac{1}{\$"

@Composable
private fun MarkdownPreview(dark: Boolean) = PreviewPage(dark) {
    MarkdownText(Markdown.parse(SampleMd), modifier = Modifier.padding(16.dp))
    MarkdownText(Markdown.parse(RichMd), modifier = Modifier.padding(16.dp))
    MarkdownText(Markdown.parse("| a | b |\n|---|---|\n| 1 | 2", streaming = true), streaming = true, modifier = Modifier.padding(16.dp))
    MarkdownText(Markdown.parse("Here is the log:\nMEDIA:/home/hermes/workspace/output/agent.debug.txt"), modifier = Modifier.padding(16.dp))
    MarkdownText(Markdown.parse("The chart:\n![CPU load](https://example.com/cpu.png)"), modifier = Modifier.padding(16.dp))
    MarkdownText(Markdown.parse(MathMd), modifier = Modifier.padding(16.dp))
    MarkdownText(Markdown.parse("Streaming reply that keeps going and going until it wraps onto a second line"), streaming = true, modifier = Modifier.padding(16.dp))
}

@Preview(name = "Markdown light")
@Composable
private fun MarkdownLightPreview() = MarkdownPreview(dark = false)

@Preview(name = "Markdown dark")
@Composable
private fun MarkdownDarkPreview() = MarkdownPreview(dark = true)
