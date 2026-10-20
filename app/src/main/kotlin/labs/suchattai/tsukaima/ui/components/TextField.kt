package labs.suchattai.tsukaima.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import labs.suchattai.tsukaima.ui.theme.Motion
import labs.suchattai.tsukaima.ui.theme.TsukaimaShapes
import labs.suchattai.tsukaima.ui.theme.TsukaimaTheme
import labs.suchattai.tsukaima.ui.theme.TsukaimaType
import labs.suchattai.tsukaima.ui.theme.tsukaima

/**
 * Settings-style input: tiny uppercase [label], raised field with a hairline border and a 2dp
 * accent focus ring (drawn outside the border, as the design's `0 0 0 2px` shadow), optional
 * [helper] caption, replaced by a danger-coloured [error] (with a danger border) while it is non-null. [mono] switches to Plex Mono for URLs and tokens; for a textarea pass
 * `singleLine = false` and [minLines].
 */
@Composable
fun TsukaimaTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String? = null,
    placeholder: String? = null,
    helper: String? = null,
    error: String? = null,
    mono: Boolean = false,
    singleLine: Boolean = true,
    minLines: Int = 1,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.tsukaima
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val ring by animateFloatAsState(
        targetValue = if (focused) 1f else 0f,
        animationSpec = tween(Motion.quick, easing = Motion.easing),
        label = "focusRing",
    )
    val textStyle = (if (mono) TsukaimaType.mono else TsukaimaType.body).copy(color = colors.ink1)

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (label != null) {
            Text(label.uppercase(), style = TsukaimaType.label, color = colors.ink3)
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxWidth()
                .drawBehind {
                    if (ring > 0f) {
                        val w = 2.dp.toPx()
                        // Stroke is centred on its path, so inflate by half its width to sit outside.
                        val r = 14.dp.toPx() + w / 2
                        drawRoundRect(
                            color = colors.accent.copy(alpha = ring),
                            topLeft = Offset(-w / 2, -w / 2),
                            size = Size(size.width + w, size.height + w),
                            cornerRadius = CornerRadius(r, r),
                            style = Stroke(w),
                        )
                    }
                }
                .background(colors.surfaceRaised, TsukaimaShapes.input)
                .border(1.dp, if (error != null) colors.danger else colors.hairline, TsukaimaShapes.input)
                .defaultMinSize(minHeight = 44.dp),
            textStyle = textStyle,
            singleLine = singleLine,
            minLines = minLines,
            keyboardOptions = keyboardOptions,
            visualTransformation = visualTransformation,
            interactionSource = source,
            cursorBrush = SolidColor(colors.accent),
            decorationBox = { inner ->
                Box(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                    if (value.isEmpty() && placeholder != null) {
                        Text(placeholder, style = textStyle, color = colors.ink3)
                    }
                    inner()
                }
            },
        )
        if (error != null) {
            Text(error, style = TsukaimaType.caption, color = colors.danger)
        } else if (helper != null) {
            Text(helper, style = TsukaimaType.caption, color = colors.ink3)
        }
    }
}

@Composable
private fun TextFieldPreview(dark: Boolean) {
    TsukaimaTheme(darkTheme = dark) {
        Column(
            Modifier
                .background(MaterialTheme.tsukaima.surfacePage)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            var url by remember { mutableStateOf("https://hermes.example.ts.net") }
            var prompt by remember { mutableStateOf("") }
            TsukaimaTextField(
                url, { url = it },
                label = "Server URL", placeholder = "https://", mono = true,
                helper = "Paste the token from your Hermes instance.",
            )
            TsukaimaTextField(
                prompt, { prompt = it },
                label = "Prompt", placeholder = "What should the job do?",
                singleLine = false, minLines = 3,
            )
        }
    }
}

@Preview(name = "TextField light")
@Composable
private fun TextFieldLightPreview() = TextFieldPreview(dark = false)

@Preview(name = "TextField dark")
@Composable
private fun TextFieldDarkPreview() = TextFieldPreview(dark = true)
