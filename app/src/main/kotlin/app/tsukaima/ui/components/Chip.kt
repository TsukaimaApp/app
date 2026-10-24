package app.tsukaima.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.tsukaima.ui.theme.Dimens
import app.tsukaima.ui.theme.Motion
import app.tsukaima.ui.theme.TsukaimaShapes
import app.tsukaima.ui.theme.TsukaimaTheme
import app.tsukaima.ui.theme.TsukaimaType
import app.tsukaima.ui.theme.tsukaima

/**
 * Outlined pill chip (model/profile pickers, filters). [selected] fills with the pale lavender
 * `accentSelected` and switches the label to ink1; [borderless] drops the hairline (the top-bar
 * model chip). A null [onClick] makes the chip passive: a label, not a button
 * (and without the `selected` semantics).
 *
 * Visual height follows `Chip.jsx` (min 36dp, 7/12 padding); the touch target is 44dp.
 */
@Composable
fun Chip(
    label: String,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    leading: IconName? = null,
    trailing: IconName? = null,
    borderless: Boolean = false,
) {
    val colors = MaterialTheme.tsukaima
    val source = remember { MutableInteractionSource() }
    val shape = TsukaimaShapes.pill
    val fill by animateColorAsState(
        targetValue = if (selected) colors.accentSelected else Color.Transparent,
        animationSpec = tween(Motion.quick, easing = Motion.easing),
        label = "chipFill",
    )
    val content = if (selected) colors.ink1 else colors.ink2
    val border = if (selected || borderless) Color.Transparent else colors.hairline

    // The visual pill is 36dp; the clickable wrapper guarantees the 44dp touch target.
    Box(
        modifier = modifier
            .sizeIn(minHeight = Dimens.hit)
            .then(
                // A passive chip is a label: no selectable state to announce.
                if (onClick == null) Modifier
                else Modifier
                    .semantics { this.selected = selected }
                    .clickable(
                        interactionSource = source,
                        indication = null,
                        role = Role.Button,
                        onClick = onClick,
                    ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .defaultMinSize(minHeight = 36.dp)
                .clip(shape)
                .background(fill)
                .border(1.dp, border, shape)
                .then(if (onClick == null) Modifier else Modifier.pressWash(source, shape))
                .padding(horizontal = 12.dp, vertical = 7.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leading != null) TsukaimaIcon(leading, contentDescription = null, size = 18.dp, tint = content)
            Text(label, style = TsukaimaType.ui, color = content, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (trailing != null) TsukaimaIcon(trailing, contentDescription = null, size = 18.dp, tint = content)
        }
    }
}

@Composable
private fun ChipPreview(dark: Boolean) {
    TsukaimaTheme(darkTheme = dark) {
        Row(
            Modifier
                .background(MaterialTheme.tsukaima.surfacePage)
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Chip("Hermes 4 · 405B", {}, trailing = IconName.ExpandMore)
            Chip("Default", {}, selected = true)
            Chip("~/work", {}, leading = IconName.Folder, borderless = true)
        }
    }
}

@Preview(name = "Chip light")
@Composable
private fun ChipLightPreview() = ChipPreview(dark = false)

@Preview(name = "Chip dark")
@Composable
private fun ChipDarkPreview() = ChipPreview(dark = true)
