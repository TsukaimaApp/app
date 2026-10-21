package app.tsukaima.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import app.tsukaima.ui.theme.Motion
import app.tsukaima.ui.theme.TsukaimaShapes
import app.tsukaima.ui.theme.TsukaimaTheme
import app.tsukaima.ui.theme.TsukaimaType
import app.tsukaima.ui.theme.tsukaima

/** Holds the toast currently on screen. Drive it with [show]; a [ToastHost] renders and expires it. */
class ToastState {
    /** The text on screen, or null when no toast is showing. */
    var current: String? by mutableStateOf(null)
        private set

    /** Bumped on every [show] so repeating the same text restarts the auto-hide timer. */
    internal var serial: Int by mutableIntStateOf(0)
        private set

    fun show(text: String) {
        current = text
        serial++
    }

    fun dismiss() {
        current = null
    }
}

@Composable
fun rememberToastState(): ToastState = remember { ToastState() }

/**
 * Renders [state]'s toast (ink1 card, cream text) and hides it after [Motion.toastMs].
 * Place it in a `Box` and align it, e.g. `Modifier.align(Alignment.BottomCenter)`.
 */
@Composable
fun ToastHost(state: ToastState, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.tsukaima
    val text = state.current
    // Keep the last text around so the fade-out still has something to draw.
    var lastText by remember { mutableStateOf("") }
    if (text != null) lastText = text

    LaunchedEffect(state.serial) {
        if (state.current != null) {
            delay(Motion.toastMs.toLong())
            state.dismiss()
        }
    }

    AnimatedVisibility(
        visible = text != null,
        modifier = modifier,
        enter = fadeIn(tween(Motion.quick, easing = Motion.easing)),
        exit = fadeOut(tween(Motion.quick, easing = Motion.easing)),
    ) {
        Text(
            text = lastText,
            style = TsukaimaType.ui,
            // Cream on ink1; both flip together in dark mode.
            color = colors.surfacePage,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .semantics { liveRegion = LiveRegionMode.Polite }
                .background(colors.ink1, TsukaimaShapes.row)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }
}

@Composable
private fun ToastPreview(dark: Boolean) {
    TsukaimaTheme(darkTheme = dark) {
        val state = rememberToastState().also { if (it.current == null) it.show("Session ID copied") }
        Box(
            Modifier
                .background(MaterialTheme.tsukaima.surfacePage)
                .padding(24.dp),
        ) { ToastHost(state) }
    }
}

@Preview(name = "Toast light")
@Composable
private fun ToastLightPreview() = ToastPreview(dark = false)

@Preview(name = "Toast dark")
@Composable
private fun ToastDarkPreview() = ToastPreview(dark = true)
