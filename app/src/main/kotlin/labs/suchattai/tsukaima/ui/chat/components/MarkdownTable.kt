package labs.suchattai.tsukaima.ui.chat.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import labs.suchattai.tsukaima.domain.markdown.Markdown
import labs.suchattai.tsukaima.domain.markdown.MdAlign
import labs.suchattai.tsukaima.domain.markdown.MdBlock
import labs.suchattai.tsukaima.domain.markdown.MdSpan
import labs.suchattai.tsukaima.ui.theme.TsukaimaShapes
import labs.suchattai.tsukaima.ui.theme.TsukaimaType
import labs.suchattai.tsukaima.ui.theme.tsukaima

private val CellPadH = 8.dp
private val CellPadV = 6.dp
private val MaxColumn = 220.dp
private val Hairline = 1.dp

/**
 * A GFM table: content-sized columns (widest cell plus padding, capped at 220dp, where cells wrap), a semibold
 * header and hairline rules, inside a 12dp hairline-bordered card. A table wider than the reply scrolls
 * sideways instead of squeezing its columns.
 */
@Composable
fun MarkdownTable(table: MdBlock.Table, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.tsukaima
    val columns = table.header.size
    if (columns == 0) return
    val rowCount = 1 + table.rows.size
    Box(
        modifier
            .clip(TsukaimaShapes.row)
            .background(colors.surfaceRaised)
            .border(Hairline, colors.hairline, TsukaimaShapes.row)
            .horizontalScroll(rememberScrollState()),
    ) {
        Layout(
            content = {
                table.header.forEachIndexed { c, spans -> TableCell(spans, table.align.getOrNull(c), header = true) }
                for (row in table.rows) {
                    for (c in 0 until columns) TableCell(row.getOrNull(c).orEmpty(), table.align.getOrNull(c), header = false)
                }
                repeat(rowCount - 1) { Box(Modifier.background(colors.hairline)) }
            },
        ) { measurables, constraints ->
            val padH = CellPadH.roundToPx()
            val padV = CellPadV.roundToPx()
            val cap = MaxColumn.roundToPx()
            val line = Hairline.roundToPx().coerceAtLeast(1)
            val cells = measurables.subList(0, columns * rowCount)
            val rules = measurables.subList(columns * rowCount, measurables.size)

            val widths = IntArray(columns) { c ->
                var widest = 0
                for (r in 0 until rowCount) widest = maxOf(widest, cells[r * columns + c].maxIntrinsicWidth(Constraints.Infinity))
                minOf(widest + 2 * padH, cap)
            }
            val placeables = cells.mapIndexed { k, cell ->
                cell.measure(Constraints.fixedWidth((widths[k % columns] - 2 * padH).coerceAtLeast(0)))
            }
            val heights = IntArray(rowCount) { r ->
                var tallest = 0
                for (c in 0 until columns) tallest = maxOf(tallest, placeables[r * columns + c].height)
                tallest + 2 * padV
            }
            val width = constraints.constrainWidth(widths.sum())
            val height = constraints.constrainHeight(heights.sum() + line * (rowCount - 1))
            val rulePlaceables = rules.map { it.measure(Constraints.fixed(width, line)) }

            layout(width, height) {
                var y = 0
                for (r in 0 until rowCount) {
                    var x = 0
                    for (c in 0 until columns) {
                        placeables[r * columns + c].placeRelative(x + padH, y + padV)
                        x += widths[c]
                    }
                    y += heights[r]
                    if (r < rowCount - 1) {
                        rulePlaceables[r].placeRelative(0, y)
                        y += line
                    }
                }
            }
        }
    }
}

@Composable
private fun TableCell(spans: List<MdSpan>, align: MdAlign?, header: Boolean) {
    val colors = MaterialTheme.tsukaima
    Text(
        text = remember(spans, colors) { markdownInline(spans, colors) },
        style = if (header) TsukaimaType.ui.copy(fontWeight = FontWeight.SemiBold) else TsukaimaType.ui,
        color = colors.ink1,
        textAlign = when (align) {
            MdAlign.Center -> TextAlign.Center
            MdAlign.End -> TextAlign.End
            else -> TextAlign.Start
        },
    )
}

private const val SampleTable = "| Service | State | Uptime |\n|:--|:-:|--:|\n" +
    "| hermes-agent | **running** | 3 d |\n" +
    "| honcho | stopped | — |\n" +
    "| matrix-bridge | running, though its last sync stalled twice overnight and it was restarted by the watchdog | 41 min |"

@Composable
private fun MarkdownTablePreview(dark: Boolean) = PreviewPage(dark) {
    val table = Markdown.parse(SampleTable).single() as MdBlock.Table
    MarkdownTable(table, Modifier.padding(16.dp))
    MarkdownTable(Markdown.parse("| a | b |\n|---|---|\n| 1 | 2 |").single() as MdBlock.Table, Modifier.padding(16.dp))
}

@Preview(name = "MarkdownTable light")
@Composable
private fun MarkdownTableLightPreview() = MarkdownTablePreview(dark = false)

@Preview(name = "MarkdownTable dark")
@Composable
private fun MarkdownTableDarkPreview() = MarkdownTablePreview(dark = true)
