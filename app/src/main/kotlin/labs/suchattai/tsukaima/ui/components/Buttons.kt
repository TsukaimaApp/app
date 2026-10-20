package labs.suchattai.tsukaima.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import labs.suchattai.tsukaima.ui.theme.Dimens
import labs.suchattai.tsukaima.ui.theme.Motion
import labs.suchattai.tsukaima.ui.theme.TsukaimaShapes
import labs.suchattai.tsukaima.ui.theme.TsukaimaTheme
import labs.suchattai.tsukaima.ui.theme.TsukaimaType
import labs.suchattai.tsukaima.ui.theme.tsukaima

enum class ButtonSize { Sm, Md }

/** Text tone for [GhostButton]: [Ink] is the secondary ink, [Danger] the red ink. */
enum class Tone { Ink, Danger }

private enum class ButtonVariant { Primary, Secondary, Ghost }

/**
 * Filled accent pill; reserved for approval and connect actions. Pressed state swaps the fill
 * to `accentPressed`.
 */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: IconName? = null,
    enabled: Boolean = true,
    size: ButtonSize = ButtonSize.Md,
) = PillButton(ButtonVariant.Primary, text, onClick, modifier, icon, enabled, size, Tone.Ink)

/** Raised pill with a hairline border and ink text. */
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: IconName? = null,
    enabled: Boolean = true,
    size: ButtonSize = ButtonSize.Md,
) = PillButton(ButtonVariant.Secondary, text, onClick, modifier, icon, enabled, size, Tone.Ink)

/** Borderless pill; [Tone.Danger] renders the label in the danger red. */
@Composable
fun GhostButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: IconName? = null,
    enabled: Boolean = true,
    size: ButtonSize = ButtonSize.Md,
    tone: Tone = Tone.Ink,
) = PillButton(ButtonVariant.Ghost, text, onClick, modifier, icon, enabled, size, tone)

@Composable
private fun PillButton(
    variant: ButtonVariant,
    text: String,
    onClick: () -> Unit,
    modifier: Modifier,
    icon: IconName?,
    enabled: Boolean,
    size: ButtonSize,
    tone: Tone,
) {
    val colors = MaterialTheme.tsukaima
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val sm = size == ButtonSize.Sm

    val fill by animateColorAsState(
        targetValue = when (variant) {
            ButtonVariant.Primary -> if (pressed) colors.accentPressed else colors.accent
            ButtonVariant.Secondary -> colors.surfaceRaised
            ButtonVariant.Ghost -> Color.Transparent
        },
        animationSpec = tween(Motion.quick, easing = Motion.easing),
        label = "buttonFill",
    )
    val content = when (variant) {
        ButtonVariant.Primary -> colors.onAccent
        ButtonVariant.Secondary -> colors.ink1
        ButtonVariant.Ghost -> if (tone == Tone.Danger) colors.danger else colors.ink2
    }
    val border = if (variant == ButtonVariant.Secondary) colors.hairline else Color.Transparent
    val shape = TsukaimaShapes.pill

    // The visual pill is 36dp (sm) / 44dp (md); the wrapper guarantees a 44dp touch target.
    Box(
        modifier = modifier
            .alpha(if (enabled) 1f else 0.45f)
            .sizeIn(minHeight = Dimens.hit)
            .clickable(
                interactionSource = source,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
        // Pass the wrapper's min width on to the pill: a caller's weight/fillMaxWidth/width (fixed width, so
        // min == max) makes the pill fill the wrapper, while a wrap-content wrapper has min 0 and hugs the label.
        propagateMinConstraints = true,
    ) {
        Row(
            modifier = Modifier
                // ...but only the width: the 44dp touch-target min height must not stretch a 36dp (sm) pill.
                .layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints.copy(minHeight = 0))
                    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                }
                .defaultMinSize(minHeight = if (sm) 36.dp else 44.dp)
                .clip(shape)
                .background(fill)
                .border(1.dp, border, shape)
                // Primary swaps its fill on press instead of washing over it.
                .then(if (variant == ButtonVariant.Primary) Modifier else Modifier.pressWash(source, shape))
                .padding(if (sm) PaddingValues(horizontal = 16.dp, vertical = 8.dp) else PaddingValues(horizontal = 22.dp, vertical = 12.dp)),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) TsukaimaIcon(icon, size = if (sm) 18.dp else 20.dp, tint = content)
            val fontSize = if (sm) 13.sp else 14.5.sp
            Text(
                text = text,
                style = TsukaimaType.ui.copy(fontSize = fontSize, lineHeight = fontSize, fontWeight = FontWeight.SemiBold),
                color = content,
                maxLines = 1,
            )
        }
    }
}

/**
 * 44dp circular icon button. Transparent with a press wash by default; [filled] gives the
 * lavender accent fill (the send button only).
 */
@Composable
fun IconButton44(
    icon: IconName,
    contentDescription: String,
    onClick: () -> Unit,
    filled: Boolean = false,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = MaterialTheme.tsukaima
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val fill by animateColorAsState(
        targetValue = when {
            !filled -> Color.Transparent
            pressed -> colors.accentPressed
            else -> colors.accent
        },
        animationSpec = tween(Motion.quick, easing = Motion.easing),
        label = "iconButtonFill",
    )
    Box(
        modifier = modifier
            .size(Dimens.hit)
            .clip(TsukaimaShapes.pill)
            .background(fill)
            .then(if (filled) Modifier else Modifier.pressWash(source, TsukaimaShapes.pill))
            .clickable(interactionSource = source, indication = null, enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        TsukaimaIcon(
            icon,
            size = 24.dp,
            tint = if (filled) colors.onAccent else colors.ink2,
            contentDescription = contentDescription,
        )
    }
}

@Composable
private fun ButtonsPreview(dark: Boolean) {
    TsukaimaTheme(darkTheme = dark) {
        Column(
            Modifier
                .background(MaterialTheme.tsukaima.surfacePage)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            PrimaryButton("Approve", {}, icon = IconName.Check)
            PrimaryButton("Save", {}, enabled = false, size = ButtonSize.Sm)
            SecondaryButton("Deny", {})
            GhostButton("Cancel", {}, size = ButtonSize.Sm)
            GhostButton("Revoke", {}, tone = Tone.Danger)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IconButton44(IconName.MoreVert, "More", {})
                IconButton44(IconName.ArrowUpward, "Send", {}, filled = true)
            }
        }
    }
}

@Preview(name = "Buttons light")
@Composable
private fun ButtonsLightPreview() = ButtonsPreview(dark = false)

@Preview(name = "Buttons dark")
@Composable
private fun ButtonsDarkPreview() = ButtonsPreview(dark = true)
