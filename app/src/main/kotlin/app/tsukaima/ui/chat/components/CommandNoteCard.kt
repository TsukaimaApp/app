package app.tsukaima.ui.chat.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.tsukaima.domain.chat.ChatItem
import app.tsukaima.domain.chat.SlashCommand
import app.tsukaima.ui.components.ButtonSize
import app.tsukaima.ui.components.GhostButton
import app.tsukaima.ui.theme.Dimens
import app.tsukaima.ui.theme.TsukaimaShapes
import app.tsukaima.ui.theme.TsukaimaType
import app.tsukaima.ui.theme.tsukaima

/** Lines of a command's output shown before "Show all". */
internal const val COMMAND_PREVIEW_LINES = 12

/** The first [COMMAND_PREVIEW_LINES] lines of [output] and how many lines follow them. */
internal fun commandPreview(output: String): Pair<String, Int> {
    val lines = output.lines()
    return if (lines.size <= COMMAND_PREVIEW_LINES + 2) output to 0
    else lines.take(COMMAND_PREVIEW_LINES).joinToString("\n") to lines.size - COMMAND_PREVIEW_LINES
}

/**
 * A slash command and what the server printed for it ([ChatItem.CommandNote]): the command in mono as the card's
 * label, the output in mono under it. The output is terminal text (boxes, aligned columns), so its lines are not
 * wrapped; a wide one scrolls sideways. Long output shows its first lines and "Show all". Selectable.
 */
@Composable
fun CommandNoteCard(item: ChatItem.CommandNote, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.tsukaima
    val shape = TsukaimaShapes.row
    val mono = TsukaimaType.caption.copy(fontFamily = TsukaimaType.mono.fontFamily)
    var all by rememberSaveable(item.id) { mutableStateOf(false) }
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
        Text(item.command, style = mono, color = colors.ink3, maxLines = 2, overflow = TextOverflow.Ellipsis)
        when {
            item.pending -> Text("Running…", style = TsukaimaType.caption, color = colors.ink3)
            item.failed -> Text(item.output, style = TsukaimaType.caption, color = colors.danger)
            else -> {
                val (preview, more) = commandPreview(item.output)
                SelectionContainer {
                    Text(
                        if (all) item.output else preview,
                        style = mono,
                        color = colors.ink2,
                        softWrap = false,
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                    )
                }
                if (more > 0) GhostButton(if (all) "Show less" else "Show all ($more more lines)", { all = !all }, size = ButtonSize.Sm)
            }
        }
    }
}

/**
 * The slash commands matching what is typed, above the composer: name in mono, description beside it. A tap puts the
 * command (and a space, for its argument) into the composer; nothing runs until Send.
 */
@Composable
fun CommandSuggestions(matches: List<SlashCommand>, onPick: (SlashCommand) -> Unit, modifier: Modifier = Modifier) {
    if (matches.isEmpty()) return
    val colors = MaterialTheme.tsukaima
    Column(
        modifier
            .fillMaxWidth()
            .heightIn(max = 232.dp)
            .background(colors.surfaceRaised)
            .verticalScroll(rememberScrollState()),
    ) {
        matches.forEach { command ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = Dimens.hit)
                    .clickable(role = Role.Button) { onPick(command) }
                    .padding(horizontal = Dimens.gutter, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(command.name, style = TsukaimaType.ui.copy(fontFamily = TsukaimaType.mono.fontFamily), color = if (command.skill) colors.accent else colors.ink1, maxLines = 1)
                Text(command.description, style = TsukaimaType.caption, color = colors.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun CommandNotePreview(dark: Boolean) = PreviewPage(dark) {
    CommandNoteCard(ChatItem.CommandNote("cmd-1", "/goal", "No active goal. Set one with /goal <text>.", pending = false))
    CommandNoteCard(ChatItem.CommandNote("cmd-2", "/undo", "no user messages to undo", pending = false, failed = true))
    CommandNoteCard(ChatItem.CommandNote("cmd-3", "/context"))
    CommandSuggestions(
        listOf(
            SlashCommand("/goal", "Set a standing goal Hermes works on across turns until achieved", skill = false),
            SlashCommand("/airtable", "Airtable REST API via curl", skill = true),
        ),
        onPick = {},
    )
}

@Preview(name = "CommandNote light")
@Composable
private fun CommandNoteLightPreview() = CommandNotePreview(dark = false)

@Preview(name = "CommandNote dark")
@Composable
private fun CommandNoteDarkPreview() = CommandNotePreview(dark = true)
