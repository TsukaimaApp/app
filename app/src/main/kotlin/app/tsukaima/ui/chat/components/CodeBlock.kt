package app.tsukaima.ui.chat.components

import android.content.ClipData
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import app.tsukaima.ui.components.IconName
import app.tsukaima.ui.components.TsukaimaIcon
import app.tsukaima.ui.theme.Dimens
import app.tsukaima.ui.theme.TsukaimaShapes
import app.tsukaima.ui.theme.TsukaimaType
import app.tsukaima.ui.theme.tsukaima

private const val CopiedMs = 1500L

/**
 * Warm-dark code well with a language label and a Copy action. [readOnly] makes the code
 * text selectable in place (long-press), for tool output where the whole-block Copy is too coarse.
 */
@Composable
fun CodeBlock(
    code: String,
    lang: String?,
    modifier: Modifier = Modifier,
    readOnly: Boolean = false,
) {
    val colors = MaterialTheme.tsukaima
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    // Taps since the label last reset: each tap is a new key, so a re-tap restarts the full 1.5 s.
    var copies by remember { mutableIntStateOf(0) }
    val copied = copies > 0
    LaunchedEffect(copies) {
        if (copies > 0) {
            delay(CopiedMs)
            copies = 0
        }
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.codeBg, TsukaimaShapes.code),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = (lang?.takeIf { it.isNotBlank() } ?: "code").uppercase(),
                style = TsukaimaType.label,
                color = colors.codeInk.copy(alpha = 0.55f),
            )
            Row(
                modifier = Modifier
                    .clickable(role = Role.Button, onClickLabel = "Copy code") {
                        scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("code", code))) }
                        copies++
                    }
                    .heightIn(min = Dimens.hit)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                TsukaimaIcon(
                    if (copied) IconName.Check else IconName.ContentCopy,
                    contentDescription = null, // the "Copy"/"Copied" label beside it says it
                    size = 16.dp,
                    tint = colors.codeInk.copy(alpha = 0.75f),
                )
                Text(
                    text = if (copied) "Copied" else "Copy",
                    style = TsukaimaType.caption,
                    color = colors.codeInk.copy(alpha = 0.75f),
                )
            }
        }
        val body: @Composable () -> Unit = {
            Text(
                text = code,
                style = TsukaimaType.mono,
                color = colors.codeInk,
                softWrap = false,
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 12.dp),
            )
        }
        if (readOnly) SelectionContainer { body() } else body()
    }
}

@Composable
private fun CodeBlockPreview(dark: Boolean) = PreviewPage(dark) {
    CodeBlock(
        code = "systemctl restart hermes-agent\njournalctl -u hermes-agent -f --since '5 minutes ago' --no-pager",
        lang = "bash",
        modifier = Modifier.padding(16.dp),
    )
    CodeBlock(code = "plain output", lang = null, readOnly = true, modifier = Modifier.padding(16.dp))
}

@Preview(name = "CodeBlock light")
@Composable
private fun CodeBlockLightPreview() = CodeBlockPreview(dark = false)

@Preview(name = "CodeBlock dark")
@Composable
private fun CodeBlockDarkPreview() = CodeBlockPreview(dark = true)
