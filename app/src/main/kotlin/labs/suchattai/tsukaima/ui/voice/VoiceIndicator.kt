package labs.suchattai.tsukaima.ui.voice

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.runtime.State
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.StartOffsetType
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.roundToInt
import labs.suchattai.tsukaima.domain.voice.VoicePhase
import labs.suchattai.tsukaima.ui.chat.components.PreviewPage
import labs.suchattai.tsukaima.ui.components.IconName
import labs.suchattai.tsukaima.ui.components.TsukaimaIcon
import labs.suchattai.tsukaima.ui.theme.TsukaimaShapes
import labs.suchattai.tsukaima.ui.theme.tsukaima

private val BarAmplitude = floatArrayOf(0.3f, 0.65f, 1f, 0.65f, 0.3f)

/**
 * The voice orb: a disc of 0.62·[size] with a phase-specific glyph. Listening/Speaking show five bars (speaking adds two
 * expanding rings), Thinking a pulsing dot on a breathing disc, Idle (and Approval, which rests as Idle) a mic, Muted a
 * struck-through mic on a sunken disc. Flat colour only. When [onTap] is set (barge-in while speaking) the orb is a
 * button, without ripple or press feedback. Every animation is read inside `graphicsLayer`, so frames only redraw.
 */
@Composable
fun VoiceIndicator(phase: VoicePhase, size: Dp, modifier: Modifier = Modifier, onTap: (() -> Unit)? = null) {
    val shown = if (phase == VoicePhase.Approval) VoicePhase.Idle else phase
    val s = size.value
    val disc = (s * 0.62f).roundToInt().dp
    val description = when (shown) {
        VoicePhase.Listening -> "Listening"
        VoicePhase.Thinking -> "Thinking"
        VoicePhase.Speaking -> "Speaking"
        VoicePhase.Muted -> "Muted"
        else -> "Tap to speak"
    }
    Box(
        modifier = modifier
            .size(size)
            .then(
                if (onTap != null) {
                    Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        role = Role.Button,
                        onClick = onTap,
                    )
                } else {
                    Modifier
                },
            )
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        if (shown == VoicePhase.Speaking) {
            Ring(disc, startMs = 0)
            Ring(disc, startMs = 1200)
        }
        Disc(shown, s, disc)
    }
}

@Composable
private fun Ring(disc: Dp, startMs: Int) {
    val transition = rememberInfiniteTransition(label = "voiceRing")
    val progress = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        // FastForward: the second ring runs half a cycle ahead from frame one rather than sitting idle for 1.2 s.
        animationSpec = infiniteRepeatable(
            animation = tween(2400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart,
            initialStartOffset = StartOffset(startMs, StartOffsetType.FastForward),
        ),
        label = "voiceRingProgress",
    )
    Box(
        Modifier
            .size(disc)
            .graphicsLayer {
                val p = progress.value
                val scale = 0.72f + (1.3f - 0.72f) * p
                scaleX = scale
                scaleY = scale
                alpha = 0.7f * (1f - p)
            }
            .border(BorderStroke(1.5.dp, MaterialTheme.tsukaima.accentSelected), TsukaimaShapes.pill),
    )
}

@Composable
private fun Disc(phase: VoicePhase, s: Float, disc: Dp) {
    val colors = MaterialTheme.tsukaima
    val breathe: State<Float>? = if (phase == VoicePhase.Thinking) {
        // Reverse doubles the period: 1.2 s each way for the 2.4 s breath.
        rememberInfiniteTransition(label = "voiceBreathe").animateFloat(
            initialValue = 0.96f,
            targetValue = 1.04f,
            animationSpec = infiniteRepeatable(tween(1200, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "voiceBreatheScale",
        )
    } else {
        null
    }
    Box(
        modifier = Modifier
            .size(disc)
            .graphicsLayer {
                val b = breathe?.value ?: 1f
                scaleX = b
                scaleY = b
            }
            .background(if (phase == VoicePhase.Muted) colors.surfaceSunken else colors.accentWash, TsukaimaShapes.pill),
        contentAlignment = Alignment.Center,
    ) {
        val glyph = Math.round(s * 0.2f).dp
        when (phase) {
            VoicePhase.Muted -> TsukaimaIcon(IconName.MicOff, null, size = glyph, tint = colors.ink3)
            VoicePhase.Thinking -> ThinkingDot(Math.round(s * 0.07f).dp)
            VoicePhase.Listening -> Bars(s, disc, periodMs = 1400)
            VoicePhase.Speaking -> Bars(s, disc, periodMs = 900)
            else -> TsukaimaIcon(IconName.Mic, null, size = glyph, tint = colors.accent)
        }
    }
}

@Composable
private fun ThinkingDot(size: Dp) {
    val alpha = rememberInfiniteTransition(label = "voiceThinking").animateFloat(
        initialValue = 1f,
        targetValue = 0.35f,
        // Reverse doubles the period, so half the 2 s cycle each way.
        animationSpec = infiniteRepeatable(tween(1000, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "voiceThinkingAlpha",
    )
    Box(
        Modifier
            .size(size)
            .graphicsLayer { this.alpha = alpha.value }
            .background(MaterialTheme.tsukaima.accent, TsukaimaShapes.pill),
    )
}

@Composable
private fun Bars(s: Float, disc: Dp, periodMs: Int) {
    val transition = rememberInfiniteTransition(label = "voiceBars")
    Row(
        horizontalArrangement = Arrangement.spacedBy(max(3f, Math.round(s * 0.035f).toFloat()).dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BarAmplitude.forEachIndexed { i, a ->
            val scale = transition.animateFloat(
                initialValue = 0.35f,
                targetValue = 1f,
                // Reverse doubles the period, so half the cycle each way (0.35 -> 1 -> 0.35).
                animationSpec = infiniteRepeatable(
                    animation = tween(periodMs / 2, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse,
                    initialStartOffset = StartOffset(i * 130),
                ),
                label = "voiceBar$i",
            )
            Box(
                Modifier
                    .width(max(3f, Math.round(s * 0.04f).toFloat()).dp)
                    .height((disc.value * (0.18f + a * 0.34f)).roundToInt().dp)
                    .graphicsLayer { scaleY = scale.value }
                    .background(MaterialTheme.tsukaima.accent, TsukaimaShapes.pill),
            )
        }
    }
}

@Composable
private fun VoiceIndicatorPreview(dark: Boolean) = PreviewPage(dark) {
    val phases = VoicePhase.entries
    phases.chunked(3).forEach { row ->
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { row.forEach { VoiceIndicator(it, 180.dp) } }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { phases.forEach { VoiceIndicator(it, 72.dp) } }
}

@Preview(name = "VoiceIndicator light", widthDp = 600, heightDp = 640)
@Composable
private fun VoiceIndicatorLightPreview() = VoiceIndicatorPreview(dark = false)

@Preview(name = "VoiceIndicator dark", widthDp = 600, heightDp = 640)
@Composable
private fun VoiceIndicatorDarkPreview() = VoiceIndicatorPreview(dark = true)
