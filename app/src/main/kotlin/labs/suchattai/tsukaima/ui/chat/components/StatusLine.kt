package labs.suchattai.tsukaima.ui.chat.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import labs.suchattai.tsukaima.domain.chat.AgentStatus
import labs.suchattai.tsukaima.domain.chat.StatusTone
import labs.suchattai.tsukaima.ui.theme.Dimens
import labs.suchattai.tsukaima.ui.theme.Motion
import labs.suchattai.tsukaima.ui.theme.TsukaimaColors
import labs.suchattai.tsukaima.ui.theme.TsukaimaShapes
import labs.suchattai.tsukaima.ui.theme.TsukaimaType
import labs.suchattai.tsukaima.ui.theme.tsukaima

/** Dot colour and pulse flag for a status; pure so it can be unit-tested. */
data class StatusVisual(val color: Color, val pulsing: Boolean)

fun statusVisual(status: AgentStatus, colors: TsukaimaColors): StatusVisual = StatusVisual(
    color = when (status.tone) {
        StatusTone.Grey -> colors.ink3
        StatusTone.Accent -> colors.accent
        StatusTone.Amber -> colors.warn
        StatusTone.Red -> colors.danger
    },
    pulsing = status.pulsing,
)

/** The dot owns the animation so only it (via graphicsLayer) updates per frame. */
@Composable
private fun StatusDot(visual: StatusVisual) {
    val alphaState = if (visual.pulsing) {
        val transition = rememberInfiniteTransition(label = "statusPulse")
        transition.animateFloat(
            initialValue = 1f,
            targetValue = 0.35f,
            // Reverse doubles the period, so half the 2 s cycle each way.
            animationSpec = infiniteRepeatable(
                animation = tween(Motion.dotPulseMs / 2, easing = Motion.easing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "statusPulseAlpha",
        )
    } else {
        null
    }
    Box(
        Modifier
            .size(7.dp)
            .graphicsLayer { alpha = alphaState?.value ?: 1f }
            .background(visual.color, TsukaimaShapes.pill),
    )
}

/** The agent's live status: 7dp dot plus a serif-italic line. */
@Composable
fun StatusLine(status: AgentStatus, onClick: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.tsukaima
    val visual = statusVisual(status, colors)
    Row(
        modifier = modifier
            .then(
                if (onClick != null) {
                    Modifier.heightIn(min = Dimens.hit).clickable(role = Role.Button, onClick = onClick)
                } else {
                    Modifier
                },
            )
            .padding(horizontal = Dimens.gutter, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StatusDot(visual)
        Text(
            text = status.label,
            style = TsukaimaType.statusLine,
            color = colors.ink2,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun StatusLinePreview(dark: Boolean) = PreviewPage(dark) {
    StatusLine(AgentStatus.Idle)
    StatusLine(AgentStatus.Thinking("Steeping matcha…"))
    StatusLine(AgentStatus.Tool("shell"), onClick = {})
    StatusLine(AgentStatus.Approval)
    StatusLine(AgentStatus.Offline)
}

@Preview(name = "StatusLine light")
@Composable
private fun StatusLineLightPreview() = StatusLinePreview(dark = false)

@Preview(name = "StatusLine dark")
@Composable
private fun StatusLineDarkPreview() = StatusLinePreview(dark = true)
