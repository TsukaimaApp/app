package labs.suchattai.tsukaima.ui.chat.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import labs.suchattai.tsukaima.domain.chat.ChatItem
import labs.suchattai.tsukaima.domain.chat.SideKind
import labs.suchattai.tsukaima.domain.markdown.Markdown
import labs.suchattai.tsukaima.ui.components.SkeletonBlock
import labs.suchattai.tsukaima.ui.theme.Dimens
import labs.suchattai.tsukaima.ui.theme.TsukaimaShapes
import labs.suchattai.tsukaima.ui.theme.TsukaimaType
import labs.suchattai.tsukaima.ui.theme.tsukaima

/**
 * A side question or background task ([ChatItem.SideNote]): a quiet raised card (hairline, no shadow) with its kind
 * as a label, the question in caption, and the answer as markdown; skeleton lines pulse while it is pending.
 */
@Composable
fun SideNoteCard(item: ChatItem.SideNote, modifier: Modifier = Modifier, media: MediaHost = MediaHost()) {
    val colors = MaterialTheme.tsukaima
    val shape = TsukaimaShapes.row
    val blocks = remember(item.text) { Markdown.parse(item.text, streaming = false) }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.gutter, vertical = 6.dp)
            .clip(shape)
            .background(colors.surfaceRaised, shape)
            .border(1.dp, colors.hairline, shape)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = (if (item.kind == SideKind.Question) "Side question" else "Background task").uppercase(),
            style = TsukaimaType.label,
            color = colors.ink3,
        )
        item.question?.takeIf { it.isNotBlank() }?.let { Text(it, style = TsukaimaType.caption, color = colors.ink2) }
        if (item.pending) {
            Column(
                Modifier.padding(top = 2.dp).semantics { contentDescription = "Waiting for the answer" },
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SkeletonBlock(Modifier.fillMaxWidth(0.88f).height(12.dp))
                SkeletonBlock(Modifier.fillMaxWidth(0.52f).height(12.dp))
            }
        } else {
            MarkdownText(blocks, media = media)
        }
    }
}

@Composable
private fun SideNotePreview(dark: Boolean) = PreviewPage(dark) {
    SideNoteCard(ChatItem.SideNote("side-1", SideKind.Question, "Which port does the gateway use?", "", pending = true))
    SideNoteCard(ChatItem.SideNote("side-2", SideKind.Question, "Which port does the gateway use?", "Port **9119**, on all interfaces.", pending = false))
    SideNoteCard(ChatItem.SideNote("side-3", SideKind.Background, "Summarize today’s logs", "No errors since 09:00.", pending = false))
}

@Preview(name = "SideNoteCard light")
@Composable
private fun SideNoteLightPreview() = SideNotePreview(dark = false)

@Preview(name = "SideNoteCard dark")
@Composable
private fun SideNoteDarkPreview() = SideNotePreview(dark = true)
