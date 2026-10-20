package labs.suchattai.tsukaima.ui.chat.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import labs.suchattai.tsukaima.domain.markdown.Markdown
import labs.suchattai.tsukaima.ui.components.IconName
import labs.suchattai.tsukaima.ui.components.TsukaimaIcon
import labs.suchattai.tsukaima.ui.components.longPressMenu
import labs.suchattai.tsukaima.ui.theme.Dimens
import labs.suchattai.tsukaima.ui.theme.Motion
import labs.suchattai.tsukaima.ui.theme.TsukaimaType
import labs.suchattai.tsukaima.ui.theme.tsukaima

/**
 * Assistant prose directly on the page: no bubble, 20dp gutters, 640dp max, optional reasoning, and file cards
 * for its `MEDIA:` deliveries ([media]). [onLongPress] opens the message menu (copy).
 */
@Composable
fun AssistantMessage(
    text: String,
    streaming: Boolean,
    reasoning: String?,
    modifier: Modifier = Modifier,
    media: MediaHost = MediaHost(),
    onLongPress: (() -> Unit)? = null,
) {
    val colors = MaterialTheme.tsukaima
    val blocks = remember(text, streaming) { Markdown.parse(text, streaming) }
    var open by rememberSaveable { mutableStateOf(false) }
    Column(
        modifier = modifier
            .widthIn(max = 640.dp)
            .then(if (onLongPress != null) Modifier.longPressMenu(onLongPress) else Modifier)
            .padding(horizontal = Dimens.gutter, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (reasoning != null) {
            val rotation by animateFloatAsState(if (open) 180f else 0f, tween(Motion.base, easing = Motion.easing), label = "reasoningChevron")
            Row(
                modifier = Modifier
                    .clickable(role = Role.Button, onClickLabel = "Toggle reasoning") { open = !open }
                    .heightIn(min = Dimens.hit)
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("Reasoning", style = TsukaimaType.caption, color = colors.ink3)
                TsukaimaIcon(IconName.ExpandMore, size = 16.dp, tint = colors.ink3, modifier = Modifier.rotate(rotation))
            }
            if (open) Text(reasoning, style = TsukaimaType.caption, color = colors.ink2)
        }
        MarkdownText(blocks, streaming = streaming, media = media)
    }
}

@Composable
private fun AssistantPreview(dark: Boolean) = PreviewPage(dark) {
    AssistantMessage("The cron job was failing because the **PATH** was empty.\n\n```bash\ncrontab -l\n```", streaming = false, reasoning = null)
    AssistantMessage("Looking at the logs now and", streaming = true, reasoning = "Check the unit file, then the journal.")
}

@Preview(name = "AssistantMessage light")
@Composable
private fun AssistantLightPreview() = AssistantPreview(dark = false)

@Preview(name = "AssistantMessage dark")
@Composable
private fun AssistantDarkPreview() = AssistantPreview(dark = true)
