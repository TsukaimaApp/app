package app.tsukaima.ui.chat.components

import android.text.format.DateFormat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.tsukaima.domain.chat.TranscriptRow
import app.tsukaima.domain.chat.TranscriptRows
import app.tsukaima.ui.components.IconName
import app.tsukaima.ui.components.TsukaimaIcon
import app.tsukaima.ui.theme.Dimens
import app.tsukaima.ui.theme.Motion
import app.tsukaima.ui.theme.TsukaimaShapes
import app.tsukaima.ui.theme.TsukaimaType
import androidx.compose.ui.text.style.TextOverflow
import app.tsukaima.ui.theme.tsukaima
import java.time.ZoneId

/** When the message below was sent ("Yesterday 21:14"), centred between messages; 12- or 24-hour as the phone is set. */
@Composable
fun TimeMark(at: Long, zone: ZoneId, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val label = remember(at, zone) { TranscriptRows.label(at, System.currentTimeMillis(), zone, DateFormat.is24HourFormat(context)) }
    Box(
        modifier
            .fillMaxWidth()
            .padding(top = 10.dp, bottom = 2.dp)
            .semantics { heading() },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = TsukaimaType.caption, color = MaterialTheme.tsukaima.ink3)
    }
}

/**
 * The folded work of an earlier turn: "6 steps · 4 tools · 1 failed". A tap ([onToggle]) unfolds the steps under
 * it, and folds them again.
 */
@Composable
fun StepsLine(row: TranscriptRow.Steps, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.tsukaima
    val rotation by animateFloatAsState(if (row.open) 180f else 0f, tween(Motion.base, easing = Motion.easing), label = "stepsChevron")
    val spoken = stepsText(row)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClickLabel = if (row.open) "Fold steps" else "Show steps", onClick = onToggle)
            .semantics(mergeDescendants = true) {
                contentDescription = spoken
                stateDescription = if (row.open) "Expanded" else "Collapsed"
            }
            .defaultMinSize(minHeight = Dimens.hit)
            .padding(horizontal = Dimens.gutter, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        TsukaimaIcon(IconName.Checklist, contentDescription = null, size = 18.dp, tint = colors.ink3)
        Text(
            text = buildAnnotatedString {
                append(plural(row.count, "step"))
                if (row.tools in 1 until row.count) withStyle(SpanStyle(color = colors.ink3)) { append(" · ${plural(row.tools, "tool")}") }
                if (row.failed > 0) {
                    withStyle(SpanStyle(color = colors.ink3)) { append(" · ") }
                    withStyle(SpanStyle(color = colors.danger)) { append("${row.failed} failed") }
                }
            },
            style = TsukaimaType.ui,
            color = colors.ink2,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        TsukaimaIcon(IconName.ExpandMore, contentDescription = null, size = 18.dp, tint = colors.ink3, modifier = Modifier.rotate(rotation))
    }
}

private fun plural(n: Int, word: String) = if (n == 1) "1 $word" else "$n ${word}s"

internal fun stepsText(row: TranscriptRow.Steps): String = listOfNotNull(
    plural(row.count, "step"),
    plural(row.tools, "tool").takeIf { row.tools in 1 until row.count },
    "${row.failed} failed".takeIf { row.failed > 0 },
).joinToString(" · ")

/**
 * "Latest", over the transcript's bottom corner while the reader is scrolled up ([visible]): a tap goes back to the
 * newest message and follows it again. The dot ([unseen]) says something arrived below since they left.
 */
@Composable
fun LatestButton(visible: Boolean, unseen: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.tsukaima
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(tween(Motion.base)) + scaleIn(tween(Motion.base), initialScale = 0.9f),
        exit = fadeOut(tween(Motion.base)) + scaleOut(tween(Motion.base), targetScale = 0.9f),
    ) {
        Row(
            modifier = Modifier
                .shadow(6.dp, CircleShape)
                .clip(CircleShape)
                .background(colors.surfaceRaised)
                .border(1.dp, colors.hairline, CircleShape)
                .clickable(role = Role.Button, onClick = onClick)
                .semantics(mergeDescendants = true) { contentDescription = if (unseen) "Jump to latest, new messages below" else "Jump to latest" }
                .defaultMinSize(minHeight = Dimens.hit)
                .padding(start = 12.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            TsukaimaIcon(IconName.ArrowDownward, contentDescription = null, size = 18.dp, tint = colors.accent)
            Text("Latest", style = TsukaimaType.ui, color = colors.ink1)
            if (unseen) Box(Modifier.size(7.dp).background(colors.accent, CircleShape))
        }
    }
}

@Composable
private fun MarksPreview(dark: Boolean) = PreviewPage(dark) {
    TimeMark(System.currentTimeMillis() - 86_400_000, ZoneId.systemDefault())
    StepsLine(TranscriptRow.Steps("steps-a", count = 6, tools = 4, failed = 1, open = false), onToggle = {})
    StepsLine(TranscriptRow.Steps("steps-b", count = 3, tools = 3, failed = 0, open = true), onToggle = {})
    LatestButton(visible = true, unseen = true, onClick = {}, modifier = Modifier.padding(16.dp))
}

@Preview(name = "Transcript marks light")
@Composable
private fun MarksLightPreview() = MarksPreview(dark = false)

@Preview(name = "Transcript marks dark")
@Composable
private fun MarksDarkPreview() = MarksPreview(dark = true)

/**
 * The status line's place while Hermes cannot be reached: "Offline", and with [savedAt] (epoch millis) how old the
 * transcript on screen is ("saved copy from 14:02"). A tap is the status line's ([onClick]).
 */
@Composable
fun OfflineBar(savedAt: Long?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.tsukaima
    val context = LocalContext.current
    val copy = remember(savedAt) {
        savedAt?.let { savedCopyText(it, System.currentTimeMillis(), ZoneId.systemDefault(), DateFormat.is24HourFormat(context)) }
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .clip(TsukaimaShapes.row)
            .background(colors.surfaceRaised)
            .border(1.dp, colors.warn.copy(alpha = 0.45f), TsukaimaShapes.row)
            .clickable(role = Role.Button, onClickLabel = "Server status", onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = listOfNotNull("Offline", copy).joinToString(", ") }
            .defaultMinSize(minHeight = Dimens.hit)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        TsukaimaIcon(IconName.CloudOff, contentDescription = null, size = 18.dp, tint = colors.warn)
        Text(
            text = buildAnnotatedString {
                append("Offline")
                if (copy != null) withStyle(SpanStyle(color = colors.ink2)) { append(" · $copy") }
            },
            style = TsukaimaType.ui,
            color = colors.ink1,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** "saved copy from 14:02" for a copy of today, "saved copy from yesterday 21:14", "saved copy from Tue 3 Nov 18:05". */
internal fun savedCopyText(at: Long, now: Long, zone: ZoneId, hour24: Boolean): String {
    val label = TranscriptRows.label(at, now, zone, hour24)
    val time = when {
        label.startsWith("Today ") -> label.removePrefix("Today ")
        label.startsWith("Yesterday ") -> "yesterday " + label.removePrefix("Yesterday ")
        else -> label
    }
    return "saved copy from $time"
}
