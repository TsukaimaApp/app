package app.tsukaima.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventTimeoutCancellationException
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.tsukaima.ui.theme.Motion
import app.tsukaima.ui.theme.TsukaimaShapes
import app.tsukaima.ui.theme.TsukaimaTheme
import app.tsukaima.ui.theme.TsukaimaType
import app.tsukaima.ui.theme.tsukaima

/**
 * Press feedback: an 8% (dark 10%) ink wash drawn over the content while [interactionSource]
 * reports a press, cross-faded over [Motion.quick]. No ripple and no scale. The wash follows
 * [shape]. Hover (desktop only) is intentionally not handled.
 *
 * Pair with `clickable(interactionSource = <same source>, indication = null, ...)`.
 */
@Composable
fun Modifier.pressWash(
    interactionSource: InteractionSource,
    shape: Shape = RectangleShape,
): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val wash = MaterialTheme.tsukaima.pressWash
    // Fade from the same hue at alpha 0 so the interpolation never passes through dark tints.
    val color by animateColorAsState(
        targetValue = if (pressed) wash else wash.copy(alpha = 0f),
        animationSpec = tween(Motion.quick, easing = Motion.easing),
        label = "pressWash",
    )
    return drawWithContent {
        drawContent()
        if (color.alpha > 0f) {
            drawOutline(shape.createOutline(size, layoutDirection, this), color)
        }
    }
}

/**
 * Fires [onLongPress] after exactly [Motion.longPressMs] (480 ms) of holding. A tap does nothing.
 *
 * The gesture loop is hand-rolled because `detectTapGestures` reads the platform long-press
 * timeout from the pointer scope, which a modifier cannot override. The long press is also
 * exposed to accessibility services as a long-click action. For a surface that is also
 * tappable, use [TsukaimaRow] (`onLongClick`), which gets the same 480 ms via
 * [ProvideLongPressTimeout].
 */
fun Modifier.longPressMenu(onLongPress: () -> Unit): Modifier = this
    .semantics {
        onLongClick {
            onLongPress()
            true
        }
    }
    .pointerInput(onLongPress) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            try {
                // Member of AwaitPointerEventScope (throws PointerEventTimeoutCancellationException).
                // Null (cancelled or consumed by a scroll) or an early up: not a long press.
                withTimeout(Motion.longPressMs.toLong()) { waitForUpOrCancellation() }
            } catch (_: PointerEventTimeoutCancellationException) {
                onLongPress()
                // Swallow the rest of the gesture so the eventual release is not a tap. In the Initial
                // (parent-first) pass, so children — a link, a toggle, a copy button — never see the release.
                do {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    event.changes.forEach { it.consume() }
                } while (event.changes.any { it.pressed })
            }
        }
    }

/**
 * Makes `combinedClickable` / gesture detectors created inside [content] use the design's
 * [Motion.longPressMs] (480 ms) instead of the platform long-press timeout. Layout nodes read
 * their view configuration from the composition at the point they are created, so this applies
 * to everything composed inside the block.
 */
@Composable
internal fun ProvideLongPressTimeout(content: @Composable () -> Unit) {
    val base = LocalViewConfiguration.current
    val config = remember(base) { LongPressViewConfiguration(base) }
    CompositionLocalProvider(LocalViewConfiguration provides config, content = content)
}

private class LongPressViewConfiguration(base: ViewConfiguration) : ViewConfiguration by base {
    override val longPressTimeoutMillis: Long get() = Motion.longPressMs.toLong()
}

@Composable
private fun PressPreviewBox(dark: Boolean) {
    TsukaimaTheme(darkTheme = dark) {
        val source = remember { MutableInteractionSource() }
        Box(
            Modifier
                .background(MaterialTheme.tsukaima.surfacePage)
                .padding(16.dp),
        ) {
            Box(
                Modifier
                    .size(160.dp, 52.dp)
                    .background(MaterialTheme.tsukaima.surfaceRaised, TsukaimaShapes.row)
                    .pressWash(source, TsukaimaShapes.row)
                    .clickable(source, indication = null) {}
                    .longPressMenu {},
                contentAlignment = Alignment.Center,
            ) {
                Text("Press me", style = TsukaimaType.ui, color = MaterialTheme.tsukaima.ink1)
            }
        }
    }
}

@Preview(name = "Press light")
@Composable
private fun PressLightPreview() = PressPreviewBox(dark = false)

@Preview(name = "Press dark")
@Composable
private fun PressDarkPreview() = PressPreviewBox(dark = true)
