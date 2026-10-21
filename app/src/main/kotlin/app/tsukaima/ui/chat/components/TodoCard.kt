package app.tsukaima.ui.chat.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.tsukaima.domain.chat.ChatItem
import app.tsukaima.domain.chat.TodoEntry
import app.tsukaima.domain.chat.TodoStatus
import app.tsukaima.ui.theme.Dimens
import app.tsukaima.ui.theme.TsukaimaShapes
import app.tsukaima.ui.theme.TsukaimaType
import app.tsukaima.ui.theme.tsukaima

/** Rows a checklist shows in full; a longer one tucks its completed rows behind "Show done (k)". */
internal const val TODO_COLLAPSE_ABOVE = 6

/** Indent per subtask level. */
private val TODO_INDENT = 16.dp

/** Deeper subtasks indent no further, so a deep tree keeps room for its text. */
private const val MAX_TODO_DEPTH = 4

/** One checklist row: [entry] at nesting [depth] (0 = top level). */
internal data class TodoRow(val entry: TodoEntry, val depth: Int)

/**
 * The snapshot in display order: each top-level entry followed by its subtasks (snapshot order within a level). An
 * entry whose `parent` is not in the snapshot is top level; entries caught in a parent cycle follow at depth 0.
 */
internal fun todoRows(todos: List<TodoEntry>): List<TodoRow> {
    val ids = todos.mapTo(HashSet()) { it.id }
    fun TodoEntry.nested() = parent != null && parent in ids && parent != id
    // By index, not by value: a snapshot may repeat an entry, and each one is a row.
    val children = todos.indices.filter { todos[it].nested() }.groupBy { todos[it].parent }
    val rows = ArrayList<TodoRow>(todos.size)
    val placed = BooleanArray(todos.size)
    fun place(index: Int, depth: Int) {
        if (placed[index]) return
        placed[index] = true
        rows += TodoRow(todos[index], depth)
        children[todos[index].id].orEmpty().forEach { place(it, depth + 1) }
    }
    todos.indices.filter { !todos[it].nested() }.forEach { place(it, 0) }
    todos.indices.forEach { place(it, 0) } // a parent cycle has no root: its entries still show
    return rows
}

/** "2 of 5 done": cancelled tasks count in the total, not as done. */
internal fun todoProgress(todos: List<TodoEntry>): String =
    "${todos.count { it.status == TodoStatus.Completed }} of ${todos.size} done"

/**
 * The agent's task checklist ([ChatItem.Todos]): a quiet raised card titled "Tasks" with its progress, one row per
 * task — `○` pending, `◐` in progress (accent), `●` completed (struck through), `×` cancelled — subtasks indented
 * under their parent. Past [TODO_COLLAPSE_ABOVE] rows the completed ones hide behind "Show done (k)". Task text is
 * agent-written, so it goes through [CommandText.reveal].
 */
@Composable
fun TodoCard(item: ChatItem.Todos, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.tsukaima
    val shape = TsukaimaShapes.row
    val rows = remember(item.todos) { todoRows(item.todos) }
    val collapsible = rows.size > TODO_COLLAPSE_ABOVE
    val doneCount = rows.count { it.entry.status == TodoStatus.Completed }
    var showDone by rememberSaveable { mutableStateOf(false) }
    val visible = if (collapsible && !showDone) rows.filter { it.entry.status != TodoStatus.Completed } else rows
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.gutter, vertical = 6.dp)
            .clip(shape)
            .background(colors.surfaceRaised, shape)
            .border(1.dp, colors.hairline, shape)
            .padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = if (collapsible && doneCount > 0) 2.dp else 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Tasks", style = TsukaimaType.ui, color = colors.ink1)
            Text(todoProgress(item.todos), style = TsukaimaType.caption, color = colors.ink3)
        }
        visible.forEach { TodoLine(it) }
        if (collapsible && doneCount > 0) {
            Box(
                modifier = Modifier
                    .heightIn(min = Dimens.hit)
                    .clickable(role = Role.Button) { showDone = !showDone },
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    if (showDone) "Hide done" else "Show done ($doneCount)",
                    style = TsukaimaType.caption,
                    color = colors.accent,
                )
            }
        }
    }
}

@Composable
private fun TodoLine(row: TodoRow) {
    val colors = MaterialTheme.tsukaima
    val status = row.entry.status
    val text = remember(row.entry.content) { CommandText.reveal(row.entry.content).text }
    val (glyph, glyphColor) = when (status) {
        TodoStatus.Pending -> "○" to colors.ink2
        TodoStatus.InProgress -> "◐" to colors.accent
        TodoStatus.Completed -> "●" to colors.ink3
        TodoStatus.Cancelled -> "×" to colors.ink3
    }
    val muted = status == TodoStatus.Completed || status == TodoStatus.Cancelled
    Row(
        modifier = Modifier
            .padding(start = TODO_INDENT * row.depth.coerceAtMost(MAX_TODO_DEPTH))
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            glyph,
            style = TsukaimaType.ui,
            color = glyphColor,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .width(16.dp)
                .semantics { contentDescription = status.label },
        )
        Text(
            text,
            style = TsukaimaType.ui,
            color = if (muted) colors.ink3 else colors.ink1,
            textDecoration = if (status == TodoStatus.Completed) TextDecoration.LineThrough else null,
            modifier = Modifier.weight(1f),
        )
    }
}

/** What a screen reader says for a row's glyph. */
private val TodoStatus.label: String
    get() = when (this) {
        TodoStatus.Pending -> "Pending"
        TodoStatus.InProgress -> "In progress"
        TodoStatus.Completed -> "Done"
        TodoStatus.Cancelled -> "Cancelled"
    }

@Composable
private fun TodoPreview(dark: Boolean) = PreviewPage(dark) {
    TodoCard(
        ChatItem.Todos(
            revision = 3,
            todos = listOf(
                TodoEntry("1", "Read the gateway contract", TodoStatus.Completed),
                TodoEntry("2", "Wire todo.updated into the reducer", TodoStatus.InProgress),
                TodoEntry("2a", "Mirror the tool.complete snapshot", TodoStatus.Pending, parent = "2"),
                TodoEntry("3", "Ship a migration nobody asked for", TodoStatus.Cancelled),
            ),
        ),
    )
}

@Preview(name = "TodoCard light")
@Composable
private fun TodoLightPreview() = TodoPreview(dark = false)

@Preview(name = "TodoCard dark")
@Composable
private fun TodoDarkPreview() = TodoPreview(dark = true)
