package app.tsukaima.ui.theme

import androidx.compose.animation.core.CubicBezierEasing

/** Durations in milliseconds. */
object Motion {
    const val quick = 150
    const val base = 200
    const val screen = 220
    val easing = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f)
    const val dotPulseMs = 2000
    const val skeletonMs = 1600
    const val caretMs = 1000
    const val longPressMs = 480
    const val toastMs = 1600
}
