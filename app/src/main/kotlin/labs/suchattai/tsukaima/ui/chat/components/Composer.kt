package labs.suchattai.tsukaima.ui.chat.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import labs.suchattai.tsukaima.ui.components.IconButton44
import labs.suchattai.tsukaima.ui.components.IconName
import labs.suchattai.tsukaima.ui.components.floatShadow
import labs.suchattai.tsukaima.ui.theme.TsukaimaShapes
import labs.suchattai.tsukaima.ui.theme.TsukaimaType
import labs.suchattai.tsukaima.ui.theme.tsukaima

/**
 * Bottom composer pill: attach, multiline field (max 120dp), send. The IME Send action and a
 * hardware Enter send; hardware Shift+Enter inserts a newline. The caller clears [value] in [onSend].
 */
@Composable
fun Composer(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    onAttach: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    placeholder: String = "Message your agent",
) {
    val colors = MaterialTheme.tsukaima
    val shape = TsukaimaShapes.composer
    val send = { if (enabled && value.isNotBlank()) onSend() }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 14.dp)
            .alpha(if (enabled) 1f else 0.55f)
            .floatShadow(shape)
            .background(colors.surfaceRaised, shape)
            .border(1.dp, colors.hairline, shape)
            .padding(6.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        IconButton44(IconName.Add, "Attach", onClick = onAttach, enabled = enabled)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            textStyle = TsukaimaType.body.copy(color = colors.ink1),
            cursorBrush = SolidColor(colors.accent),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { send() }),
            modifier = Modifier
                .weight(1f)
                .heightIn(max = 120.dp)
                .onPreviewKeyEvent { event ->
                    if (event.key == Key.Enter && !event.isShiftPressed) {
                        if (event.type == KeyEventType.KeyDown) send()
                        true
                    } else {
                        false
                    }
                },
            decorationBox = { inner ->
                Box(Modifier.padding(horizontal = 4.dp, vertical = 11.dp)) {
                    if (value.isEmpty()) {
                        Text(
                            text = if (enabled) placeholder else "Hermes is unreachable",
                            style = TsukaimaType.body,
                            color = colors.ink3,
                        )
                    }
                    inner()
                }
            },
        )
        IconButton44(IconName.ArrowUpward, "Send", onClick = send, filled = enabled, enabled = enabled)
    }
}

@Composable
private fun ComposerPreview(dark: Boolean) = PreviewPage(dark) {
    var text by remember { mutableStateOf("") }
    Composer(text, { text = it }, {}, {}, enabled = true)
    Composer("Restart the agent and tail the logs.", {}, {}, {}, enabled = true)
    Composer("", {}, {}, {}, enabled = false)
}

@Preview(name = "Composer light")
@Composable
private fun ComposerLightPreview() = ComposerPreview(dark = false)

@Preview(name = "Composer dark")
@Composable
private fun ComposerDarkPreview() = ComposerPreview(dark = true)
