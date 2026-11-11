package app.tsukaima.ui.chat.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import app.tsukaima.domain.chat.TranscriptRows
import app.tsukaima.ui.components.IconName
import app.tsukaima.ui.components.TsukaimaIcon
import app.tsukaima.ui.theme.Dimens
import app.tsukaima.ui.theme.Motion
import app.tsukaima.ui.theme.TsukaimaType
import app.tsukaima.ui.theme.tsukaima
import kotlinx.coroutines.delay

/** Lines of reasoning a folded block shows. */
private const val FOLDED_LINES = 2

/**
 * The agent's reasoning as a margin note: an accent rule down the left, a header, the text in the serif italic of
 * the status line. Folded (the default) it shows [FOLDED_LINES] lines: the newest while it streams ([live]), the
 * first once done, so the block keeps its height when the reply starts. A tap unfolds all of it ([open],
 * [onToggle]). The header reads "Thinking… 0:12" while [live] (counted from [since], epoch millis, when known) and
 * "Thought for 12s" after ([thoughtMs]; "Thought" for reasoning loaded from history, which was not timed).
 */
@Composable
fun ThoughtBlock(
    text: String,
    live: Boolean,
    open: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    since: Long? = null,
    thoughtMs: Long? = null,
) {
    val colors = MaterialTheme.tsukaima
    val rule = colors.accent
    val rotation by animateFloatAsState(if (open) 180f else 0f, tween(Motion.base, easing = Motion.easing), label = "thoughtChevron")
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClickLabel = if (open) "Fold reasoning" else "Show all reasoning", onClick = onToggle)
            .semantics { stateDescription = if (open) "Expanded" else "Collapsed" }
            .defaultMinSize(minHeight = Dimens.hit)
            .drawBehind {
                val w = 2.dp.toPx()
                drawLine(rule, Offset(w / 2, 0f), Offset(w / 2, size.height), strokeWidth = w)
            }
            .padding(start = 12.dp, top = 4.dp, bottom = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TsukaimaIcon(IconName.AutoAwesome, contentDescription = null, size = 16.dp, tint = colors.accent)
            if (live) LiveHeader(since) else Text(TranscriptRows.thoughtLabel(thoughtMs), style = TsukaimaType.ui, color = colors.accent, maxLines = 1)
            TsukaimaIcon(IconName.ExpandMore, contentDescription = null, size = 16.dp, tint = colors.ink3, modifier = Modifier.rotate(rotation))
        }
        when {
            open -> Text(text.trim(), style = TsukaimaType.thought, color = colors.ink2)
            live -> NewestLines(TranscriptRows.tail(text, TAIL_CHARS), FOLDED_LINES)
            else -> Text(text.trim(), style = TsukaimaType.thought, color = colors.ink2, maxLines = FOLDED_LINES, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Enough text to fill [FOLDED_LINES] lines on any phone; the rest of a long stream is not laid out per delta. */
private const val TAIL_CHARS = 400

/** "Thinking… 0:12" with a slow sheen across it; plain accent text when the system's animations are off. */
@Composable
private fun LiveHeader(since: Long?) {
    val colors = MaterialTheme.tsukaima
    val seconds by produceState(elapsed(since), since) {
        while (true) {
            value = elapsed(since)
            delay(1000)
        }
    }
    val label = if (seconds == null) "Thinking…" else "Thinking… ${elapsedText(seconds ?: 0)}"
    if (motionOff()) {
        Text(label, style = TsukaimaType.ui, color = colors.accent, maxLines = 1)
        return
    }
    val shift by rememberInfiniteTransition(label = "thoughtSheen")
        .animateFloat(0f, 1f, infiniteRepeatable(tween(SHEEN_MS, easing = LinearEasing), RepeatMode.Restart), label = "thoughtSheenShift")
    val span = with(LocalDensity.current) { 220.dp.toPx() }
    val brush = remember(shift, span, colors.accent, colors.ink1) {
        val x = (shift * 2f - 0.5f) * span
        Brush.linearGradient(
            0f to colors.accent, 0.5f to colors.ink1, 1f to colors.accent,
            start = Offset(x - span / 3, 0f), end = Offset(x + span / 3, 0f),
        )
    }
    Text(label, style = TsukaimaType.ui.copy(brush = brush), color = Color.Unspecified, maxLines = 1)
}

private const val SHEEN_MS = 2200

private fun elapsed(since: Long?): Long? = since?.let { ((System.currentTimeMillis() - it) / 1000).coerceAtLeast(0) }

/**
 * [text] in a window of at most [lines] lines that shows its end: the text is laid out in full and placed so its
 * last line sits at the bottom, the earlier ones clipped above. Streaming text then scrolls up inside the window.
 */
@Composable
private fun NewestLines(text: String, lines: Int) {
    val colors = MaterialTheme.tsukaima
    val style = TsukaimaType.thought
    val window = with(LocalDensity.current) { (style.lineHeight * lines).roundToPx() }
    Layout(
        content = { Text(text, style = style, color = colors.ink2) },
        modifier = Modifier
            .fillMaxWidth()
            .clipToBounds(),
    ) { measurables, constraints ->
        val placeable = measurables.first().measure(Constraints(maxWidth = constraints.maxWidth))
        val height = minOf(placeable.height, window)
        layout(constraints.maxWidth, height) { placeable.place(0, height - placeable.height) }
    }
}

@Composable
private fun ThoughtPreview(dark: Boolean) = PreviewPage(dark) {
    val text = "The job runs at 03:00 every night. Sunday is also when the weekly snapshot runs, so both may want the same lock. " +
        "Check the journal for the unit first, then compare the start times."
    Column(Modifier.padding(horizontal = Dimens.gutter), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ThoughtBlock(text, live = true, open = false, onToggle = {}, since = System.currentTimeMillis() - 12_000)
        ThoughtBlock(text, live = false, open = false, onToggle = {}, thoughtMs = 12_000)
        ThoughtBlock(text, live = false, open = true, onToggle = {})
    }
}

@Preview(name = "ThoughtBlock light")
@Composable
private fun ThoughtLightPreview() = ThoughtPreview(dark = false)

@Preview(name = "ThoughtBlock dark")
@Composable
private fun ThoughtDarkPreview() = ThoughtPreview(dark = true)
