package labs.suchattai.tsukaima.ui.chat.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.ui.unit.dp
import labs.suchattai.tsukaima.domain.markdown.MdBlock
import labs.suchattai.tsukaima.domain.markdown.MdSpan
import labs.suchattai.tsukaima.domain.markdown.Markdown
import labs.suchattai.tsukaima.ui.theme.Motion
import labs.suchattai.tsukaima.ui.theme.TsukaimaColors
import labs.suchattai.tsukaima.ui.theme.TsukaimaType
import labs.suchattai.tsukaima.ui.theme.tsukaima

private const val CaretId = "caret"

private fun AnnotatedString.Builder.appendSpans(spans: List<MdSpan>, colors: TsukaimaColors) {
    for (span in spans) when (span) {
        is MdSpan.Text -> append(span.s)
        is MdSpan.Bold -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(span.s) }
        is MdSpan.Italic -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(span.s) }
        is MdSpan.Code -> withStyle(
            SpanStyle(
                fontFamily = TsukaimaType.mono.fontFamily,
                fontSize = TsukaimaType.mono.fontSize,
                background = colors.surfaceSunken,
            ),
        ) { append(span.s) }
    }
}

private fun inline(spans: List<MdSpan>, colors: TsukaimaColors, caret: Boolean): AnnotatedString =
    buildAnnotatedString {
        appendSpans(spans, colors)
        if (caret) appendInlineContent(CaretId, "|")
    }

/**
 * Renders parsed markdown blocks. With [streaming], a blinking accent caret is appended inline
 * to the last text run (so it follows wrapping); after a trailing code block or when empty it
 * sits on its own line.
 */
@Composable
fun MarkdownText(blocks: List<MdBlock>, streaming: Boolean = false, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.tsukaima
    val lastTextIndex = blocks.indexOfLast { it !is MdBlock.Code }
    val caretInline = streaming && blocks.isNotEmpty() && lastTextIndex == blocks.lastIndex
    val caretContent = caretInlineContent()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEachIndexed { index, block ->
            val caret = caretInline && index == blocks.lastIndex
            when (block) {
                is MdBlock.Paragraph -> Text(
                    text = inline(block.spans, colors, caret),
                    style = TsukaimaType.body,
                    color = colors.ink1,
                    inlineContent = caretContent,
                )
                is MdBlock.Code -> CodeBlock(code = block.code, lang = block.lang)
                is MdBlock.Bullets -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    block.items.forEachIndexed { i, item ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("•", style = TsukaimaType.body, color = colors.ink2)
                            Text(
                                text = inline(item, colors, caret && i == block.items.lastIndex),
                                style = TsukaimaType.body,
                                color = colors.ink1,
                                inlineContent = caretContent,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
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

/** 2dp accent caret, as tall as the body line, blinking in two hard steps over 1 s. */
@Composable
private fun caretInlineContent(): Map<String, InlineTextContent> {
    val density = LocalDensity.current
    val lineHeight = TsukaimaType.body.lineHeight
    val width = with(density) { 4.dp.toSp() }
    return mapOf(
        CaretId to InlineTextContent(
            Placeholder(width, lineHeight, PlaceholderVerticalAlign.TextCenter),
        ) { Caret() },
    )
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

@Composable
private fun MarkdownPreview(dark: Boolean) = PreviewPage(dark) {
    MarkdownText(Markdown.parse(SampleMd), modifier = Modifier.padding(16.dp))
    MarkdownText(Markdown.parse("Streaming reply that keeps going and going until it wraps onto a second line"), streaming = true, modifier = Modifier.padding(16.dp))
}

@Preview(name = "Markdown light")
@Composable
private fun MarkdownLightPreview() = MarkdownPreview(dark = false)

@Preview(name = "Markdown dark")
@Composable
private fun MarkdownDarkPreview() = MarkdownPreview(dark = true)
