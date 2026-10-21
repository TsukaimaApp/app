package labs.suchattai.tsukaima.ui.chat.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.draw.alpha
import labs.suchattai.tsukaima.ui.components.ButtonSize
import labs.suchattai.tsukaima.ui.components.GhostButton
import labs.suchattai.tsukaima.ui.components.IconName
import labs.suchattai.tsukaima.ui.components.TsukaimaIcon
import labs.suchattai.tsukaima.ui.components.longPressMenu
import labs.suchattai.tsukaima.ui.theme.Dimens
import labs.suchattai.tsukaima.ui.theme.TsukaimaType
import labs.suchattai.tsukaima.ui.theme.tsukaima

private val BubbleShape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 6.dp)

/**
 * Right-aligned user message: pale lavender fill, 6dp tail corner, at most 82 % of the width. [attachments]
 * (names of files sent with it) are listed under the text in caption style. A [failed] send gets a
 * "Not sent" line with Retry and Dismiss under the bubble; a [queued] one (sent while a turn ran, waiting for it to
 * end) a "Queued" caption. [onLongPress] opens the message menu (copy).
 */
@Composable
fun UserBubble(
    text: String,
    modifier: Modifier = Modifier,
    attachments: List<String> = emptyList(),
    failed: Boolean = false,
    queued: Boolean = false,
    onRetry: () -> Unit = {},
    onDismiss: () -> Unit = {},
    onLongPress: (() -> Unit)? = null,
) {
    val colors = MaterialTheme.tsukaima
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.gutter, vertical = 4.dp),
        contentAlignment = Alignment.CenterEnd,
    ) {
        val bubbleMax = maxWidth * 0.82f
        Column(horizontalAlignment = Alignment.End) {
            Column(
                modifier = Modifier
                    .widthIn(max = bubbleMax)
                    .then(if (onLongPress != null) Modifier.longPressMenu(onLongPress) else Modifier)
                    .alpha(if (failed) 0.7f else 1f)
                    .background(colors.accentWash, BubbleShape)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (text.isNotBlank() || attachments.isEmpty()) Text(text = text, style = TsukaimaType.body, color = colors.ink1)
                attachments.forEach { name ->
                    Text(name, style = TsukaimaType.caption, color = colors.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (queued && !failed) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp, end = 4.dp)) {
                    TsukaimaIcon(IconName.Schedule, contentDescription = null, size = 14.dp, tint = colors.ink3)
                    Text("Queued", style = TsukaimaType.caption, color = colors.ink3, modifier = Modifier.padding(start = 4.dp))
                }
            }
            if (failed) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TsukaimaIcon(IconName.Error, contentDescription = null, size = 14.dp, tint = colors.danger)
                    Text("Not sent", style = TsukaimaType.caption, color = colors.danger, modifier = Modifier.padding(start = 4.dp, end = 4.dp))
                    GhostButton("Dismiss", onDismiss, size = ButtonSize.Sm)
                    GhostButton("Retry", onRetry, size = ButtonSize.Sm, icon = IconName.Refresh)
                }
            }
        }
    }
}

@Composable
private fun UserBubblePreview(dark: Boolean) = PreviewPage(dark) {
    UserBubble("Restart the agent and tail the logs.")
    UserBubble("What is the first line?", attachments = listOf("notes.txt", "screenshot.png"))
    UserBubble("", attachments = listOf("photo.jpg"))
    UserBubble("Restart the gateway please.", failed = true)
    UserBubble("Then summarize it.", queued = true)
    UserBubble("A much longer message that should wrap well before it reaches the left edge of the screen, keeping the 82 percent cap.")
}

@Preview(name = "UserBubble light")
@Composable
private fun UserBubbleLightPreview() = UserBubblePreview(dark = false)

@Preview(name = "UserBubble dark")
@Composable
private fun UserBubbleDarkPreview() = UserBubblePreview(dark = true)
