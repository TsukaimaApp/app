package app.tsukaima.ui.chat.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import app.tsukaima.domain.chat.ChatItem
import app.tsukaima.domain.markdown.Markdown
import app.tsukaima.ui.components.IconName
import app.tsukaima.ui.components.TsukaimaIcon
import app.tsukaima.ui.theme.Dimens
import app.tsukaima.ui.theme.TsukaimaType
import app.tsukaima.ui.theme.tsukaima

/**
 * A message opened for text selection (the long-press menu's "Select text"): the whole message on its own page inside
 * one [SelectionContainer], so a selection runs across paragraphs, list items and code, and, for a reply, from its
 * reasoning into its text. In the transcript a long press opens the message menu, so the two gestures never compete:
 * selection lives here, where a long press starts it. Back or the arrow closes the page.
 */
@Composable
fun SelectTextView(item: ChatItem, onClose: () -> Unit) {
    val text = selectableText(item) ?: return
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        (LocalView.current.parent as? DialogWindowProvider)?.window?.let { window ->
            SideEffect { window.setDimAmount(0f) }
        }
        val colors = MaterialTheme.tsukaima
        Column(
            Modifier
                .fillMaxSize()
                .background(colors.surfacePage)
                .systemBarsPadding(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Box(
                    Modifier
                        .size(Dimens.hit)
                        .clickable(role = Role.Button, onClickLabel = "Close", onClick = onClose),
                    contentAlignment = Alignment.Center,
                ) {
                    TsukaimaIcon(IconName.ArrowBack, contentDescription = "Close", tint = colors.ink2)
                }
                Text("Select text", style = TsukaimaType.sectionTitle, color = colors.ink1)
            }
            SelectionContainer(Modifier.weight(1f)) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = Dimens.gutter, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    text.reasoning?.let {
                        Text("Reasoning", style = TsukaimaType.caption, color = colors.ink3)
                        Text(it, style = TsukaimaType.caption, color = colors.ink2)
                    }
                    if (text.markdown) {
                        val blocks = remember(text.body) { Markdown.parse(text.body, false) }
                        MarkdownText(blocks)
                    } else {
                        Text(text.body, style = TsukaimaType.body, color = colors.ink1)
                    }
                }
            }
        }
    }
}

/** What [SelectTextView] shows for a message: [body] ([markdown] for a reply), and a reply's [reasoning] above it. */
internal data class SelectableText(val body: String, val markdown: Boolean, val reasoning: String?)

/** The selectable text of [item]: a user message or a reply with something to select; null for every other row. */
internal fun selectableText(item: ChatItem): SelectableText? = when (item) {
    is ChatItem.UserMessage -> SelectableText(item.text, markdown = false, reasoning = null).takeIf { item.text.isNotEmpty() }
    is ChatItem.AssistantMessage -> {
        val reasoning = item.reasoning?.takeIf { it.isNotBlank() }
        SelectableText(item.text, markdown = true, reasoning = reasoning).takeIf { item.text.isNotEmpty() || reasoning != null }
    }
    else -> null
}
