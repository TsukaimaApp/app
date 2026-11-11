package app.tsukaima.ui.chat.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.tsukaima.domain.markdown.Markdown
import app.tsukaima.ui.components.IconName
import app.tsukaima.ui.components.TsukaimaIcon
import app.tsukaima.ui.components.longPressMenu
import app.tsukaima.ui.theme.Dimens
import app.tsukaima.ui.theme.TsukaimaType
import app.tsukaima.ui.theme.tsukaima

/**
 * Assistant prose directly on the page: no bubble, 20dp gutters, 640dp max, optional reasoning, and file cards
 * for its `MEDIA:` deliveries ([media]). [onLongPress] opens the message menu (copy). The reasoning is a
 * [ThoughtBlock] above the reply ([thoughtFrom] and [thoughtMs] time it). Its open state is the chat's
 * ([reasoningOpen], toggled through [onToggleReasoning]), not the composable's: a list that recycles or rebuilds
 * its items must not fold a block the reader is in the middle of.
 * [model] is shown under the finished reply when another model than the session's wrote it (a fallback).
 * [padding] is its outer spacing (none in the assist sheet).
 */
@Composable
fun AssistantMessage(
    text: String,
    streaming: Boolean,
    reasoning: String?,
    modifier: Modifier = Modifier,
    media: MediaHost = MediaHost(),
    onLongPress: (() -> Unit)? = null,
    reasoningOpen: Boolean = false,
    onToggleReasoning: () -> Unit = {},
    model: String? = null,
    thoughtFrom: Long? = null,
    thoughtMs: Long? = null,
    padding: PaddingValues = PaddingValues(horizontal = Dimens.gutter, vertical = 4.dp),
) {
    val colors = MaterialTheme.tsukaima
    val blocks = remember(text, streaming) { Markdown.parse(text, streaming) }
    Column(
        modifier = modifier
            .widthIn(max = 640.dp)
            .then(if (onLongPress != null) Modifier.longPressMenu(onLongPress) else Modifier)
            .padding(padding),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (reasoning != null) {
            ThoughtBlock(
                reasoning, live = streaming && text.isEmpty(), open = reasoningOpen, onToggle = onToggleReasoning,
                since = thoughtFrom, thoughtMs = thoughtMs,
            )
        }
        MarkdownText(blocks, streaming = streaming, media = media)
        if (model != null && !streaming) {
            Row(
                modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = "Answered by $model" },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                TsukaimaIcon(IconName.Memory, contentDescription = null, size = 14.dp, tint = colors.ink3)
                Text(model, style = TsukaimaType.caption, color = colors.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun AssistantPreview(dark: Boolean) = PreviewPage(dark) {
    AssistantMessage("The cron job was failing because the **PATH** was empty.\n\n```bash\ncrontab -l\n```", streaming = false, reasoning = null)
    AssistantMessage("Restarted; the unit is active again.", streaming = false, reasoning = null, model = "hf:moonshotai/Kimi-K3.1-Pro")
    AssistantMessage("Looking at the logs now and", streaming = true, reasoning = "Check the unit file, then the journal.")
}

@Preview(name = "AssistantMessage light")
@Composable
private fun AssistantLightPreview() = AssistantPreview(dark = false)

@Preview(name = "AssistantMessage dark")
@Composable
private fun AssistantDarkPreview() = AssistantPreview(dark = true)
