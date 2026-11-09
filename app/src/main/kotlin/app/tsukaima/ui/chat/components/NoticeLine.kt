package app.tsukaima.ui.chat.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.tsukaima.domain.chat.ChatItem
import app.tsukaima.ui.components.IconName
import app.tsukaima.ui.components.TsukaimaIcon
import app.tsukaima.ui.theme.Dimens
import app.tsukaima.ui.theme.TsukaimaType
import app.tsukaima.ui.theme.tsukaima

/**
 * A server notice ([ChatItem.Notice]) as a system line between bubbles: a small icon and caption text, warn-tinted
 * for a warning (model fallback, degraded side path), ink for a quiet one (context compacted). Plain text, not
 * markdown: the server writes these as one line. Nothing to tap, nothing to dismiss.
 */
@Composable
fun NoticeLine(item: ChatItem.Notice, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.tsukaima
    val tint = if (item.warning) colors.warn else colors.ink3
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.gutter, vertical = 6.dp)
            .semantics(mergeDescendants = true) { contentDescription = (if (item.warning) "Warning: " else "Notice: ") + item.text },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        TsukaimaIcon(
            if (item.warning) IconName.Warning else IconName.Check,
            size = 16.dp,
            tint = tint,
            contentDescription = null,
            modifier = Modifier.padding(top = 1.dp),
        )
        Text(item.text, style = TsukaimaType.caption, color = if (item.warning) colors.ink2 else colors.ink3)
    }
}

@Preview(name = "Notice — light", showBackground = true)
@Composable
private fun NoticePreviewLight() = NoticePreview(dark = false)

@Preview(name = "Notice — dark", showBackground = true, backgroundColor = 0xFF101114)
@Composable
private fun NoticePreviewDark() = NoticePreview(dark = true)

@Composable
private fun NoticePreview(dark: Boolean) = PreviewPage(dark) {
    NoticeLine(
        ChatItem.Notice(
            "n-1",
            "Model fallback: claude-opus-5-5 via anthropic unavailable (rate limit); using hf:moonshotai/Kimi-K3.1-Pro via synthetic. Primary retry eligible in ~60 s; recovery is not guaranteed.",
            warning = true,
        ),
    )
    NoticeLine(ChatItem.Notice("n-2", "Context compression complete", warning = false))
}
