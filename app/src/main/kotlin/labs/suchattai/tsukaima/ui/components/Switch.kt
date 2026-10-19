package labs.suchattai.tsukaima.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import labs.suchattai.tsukaima.ui.theme.Dimens
import labs.suchattai.tsukaima.ui.theme.Motion
import labs.suchattai.tsukaima.ui.theme.TsukaimaShapes
import labs.suchattai.tsukaima.ui.theme.TsukaimaTheme
import labs.suchattai.tsukaima.ui.theme.tsukaima

/**
 * 44x26 track with an 18dp thumb; accent track when on, sunken track with a hairline when off.
 * The control sits in a 44dp-tall hit area. Label it from the surrounding row; the whole row
 * should be the toggle target when it has a title.
 */
@Composable
fun TsukaimaSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.tsukaima
    val spec = tween<Color>(Motion.base, easing = Motion.easing)
    val track by animateColorAsState(if (checked) colors.accent else colors.surfaceSunken, spec, label = "track")
    val border by animateColorAsState(if (checked) colors.accent else colors.hairline, spec, label = "border")
    val thumb by animateColorAsState(if (checked) colors.onAccent else colors.ink3, spec, label = "thumb")
    // 1dp border + 3dp padding = 4dp inset on every side; the thumb travels 44 - 2*4 - 18 = 18dp.
    val thumbX by animateDpAsState(
        targetValue = if (checked) 22.dp else 4.dp,
        animationSpec = tween(Motion.base, easing = Motion.easing),
        label = "thumbX",
    )

    Box(
        modifier = modifier
            .size(Dimens.hit)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(width = 44.dp, height = 26.dp)
                .clip(TsukaimaShapes.pill)
                .background(track)
                .border(1.dp, border, TsukaimaShapes.pill),
        ) {
            Box(
                Modifier
                    .offset(x = thumbX, y = 4.dp)
                    .size(18.dp)
                    .clip(TsukaimaShapes.pill)
                    .background(thumb),
            )
        }
    }
}

@Composable
private fun SwitchPreview(dark: Boolean) {
    TsukaimaTheme(darkTheme = dark) {
        Row(
            Modifier
                .background(MaterialTheme.tsukaima.surfacePage)
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            TsukaimaSwitch(checked = true, onCheckedChange = {})
            TsukaimaSwitch(checked = false, onCheckedChange = {})
        }
    }
}

@Preview(name = "Switch light")
@Composable
private fun SwitchLightPreview() = SwitchPreview(dark = false)

@Preview(name = "Switch dark")
@Composable
private fun SwitchDarkPreview() = SwitchPreview(dark = true)
