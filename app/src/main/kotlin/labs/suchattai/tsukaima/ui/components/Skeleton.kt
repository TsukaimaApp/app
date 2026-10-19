package labs.suchattai.tsukaima.ui.components

import androidx.compose.animation.animateColor
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import labs.suchattai.tsukaima.ui.theme.Motion
import labs.suchattai.tsukaima.ui.theme.TsukaimaTheme
import labs.suchattai.tsukaima.ui.theme.tsukaima

/**
 * Loading placeholder. Pulses slowly (one 1.6 s cycle) between the sunken and raised surfaces;
 * never a shimmer. Size it with [modifier].
 */
@Composable
fun SkeletonBlock(
    modifier: Modifier = Modifier,
    radius: Dp = 6.dp,
) {
    val colors = MaterialTheme.tsukaima
    val transition = rememberInfiniteTransition(label = "skeleton")
    val color by transition.animateColor(
        initialValue = colors.surfaceSunken,
        targetValue = colors.surfaceRaised,
        animationSpec = infiniteRepeatable(
            animation = tween(Motion.skeletonMs / 2, easing = Motion.easing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "skeletonColor",
    )
    // Colour is read in the draw phase so the pulse does not recompose.
    Box(
        modifier.drawBehind {
            val r = radius.toPx()
            drawRoundRect(color, cornerRadius = CornerRadius(r, r))
        },
    )
}

@Composable
private fun SkeletonPreview(dark: Boolean) {
    TsukaimaTheme(darkTheme = dark) {
        Column(
            Modifier
                .background(MaterialTheme.tsukaima.surfacePage)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SkeletonBlock(Modifier.fillMaxWidth(0.56f).height(40.dp), radius = 16.dp)
            SkeletonBlock(Modifier.fillMaxWidth(0.92f).height(14.dp))
            SkeletonBlock(Modifier.fillMaxWidth(0.78f).height(14.dp))
            SkeletonBlock(Modifier.fillMaxWidth(0.4f).height(14.dp))
            SkeletonBlock(Modifier.fillMaxWidth().height(44.dp), radius = 12.dp)
        }
    }
}

@Preview(name = "Skeleton light")
@Composable
private fun SkeletonLightPreview() = SkeletonPreview(dark = false)

@Preview(name = "Skeleton dark")
@Composable
private fun SkeletonDarkPreview() = SkeletonPreview(dark = true)
