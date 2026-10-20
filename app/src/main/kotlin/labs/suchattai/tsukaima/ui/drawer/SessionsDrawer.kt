package labs.suchattai.tsukaima.ui.drawer

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import labs.suchattai.tsukaima.R
import labs.suchattai.tsukaima.data.api.SessionDto
import labs.suchattai.tsukaima.ui.components.ConfirmDialog
import labs.suchattai.tsukaima.ui.components.IconName
import labs.suchattai.tsukaima.ui.components.MenuItem
import labs.suchattai.tsukaima.ui.components.MenuSheet
import labs.suchattai.tsukaima.ui.components.TsukaimaTextField
import labs.suchattai.tsukaima.ui.theme.NotoSerifJp
import labs.suchattai.tsukaima.ui.theme.TsukaimaTheme
import labs.suchattai.tsukaima.ui.theme.TsukaimaType
import labs.suchattai.tsukaima.ui.theme.tsukaima

/**
 * The sessions drawer (HANDOFF §4, `drawer-overlay.jsx`): header (mark, wordmark, kanji, search), destination
 * rows, sessions grouped by profile, the archived view, and a Settings footer. Stateless apart from the open
 * long-press menu and its dialogs; the session to act on is held by id, so it survives a list refresh.
 *
 * Fills its width with the raised surface and pads itself for the system bars. The right hairline is drawn by
 * the shell (a `VerticalDivider` beside it).
 */
@Composable
fun SessionsDrawer(
    state: DrawerUiState,
    onNewChat: () -> Unit,
    onStatus: () -> Unit,
    onCron: () -> Unit,
    onOpen: (storedId: String) -> Unit,
    onRename: (id: String, title: String) -> Unit,
    onArchive: (id: String, archived: Boolean) -> Unit,
    onDelete: (id: String) -> Unit,
    onSettings: () -> Unit,
    onQuery: (String) -> Unit,
    onShowArchived: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    now: Long = System.currentTimeMillis(),
) {
    val colors = MaterialTheme.tsukaima
    var menuFor by rememberSaveable { mutableStateOf<String?>(null) }
    var renameFor by rememberSaveable { mutableStateOf<String?>(null) }
    var renameText by rememberSaveable { mutableStateOf("") }
    var deleteFor by rememberSaveable { mutableStateOf<String?>(null) }
    val byId = (state.sessions + state.searchResults.orEmpty()).associateBy { it.id }

    Column(
        modifier
            .fillMaxHeight()
            .background(colors.surfaceRaised)
            .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Vertical + WindowInsetsSides.Start)),
    ) {
        DrawerHeader(state.query, onQuery)
        LazyColumn(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
        ) {
            if (state.searchActive && !state.showArchived) {
                val hits = state.searchResults
                if (hits == null) {
                    item(key = "searching") { EmptyCaption("Searching…") }
                } else {
                    items(hits, key = { "q:${it.id}" }) { s ->
                        ConversationItem(
                            title = s.searchTitle,
                            meta = if (s.archived) "archived" else ageLabel(s.startedAt, now),
                            selected = s.id == state.selectedId,
                            onClick = { onOpen(s.id) },
                            onLongClick = { menuFor = s.id },
                        )
                    }
                    if (hits.isEmpty()) item(key = "none") { EmptyCaption("No sessions match.") }
                }
            } else if (state.showArchived) {
                item(key = "back") {
                    ConversationItem("Archived", icon = IconName.ArrowBack, onClick = { onShowArchived(false) })
                }
                items(state.archived, key = { "a:${it.id}" }) { s ->
                    ConversationItem(
                        title = s.displayTitle,
                        meta = s.profileKey,
                        selected = s.id == state.selectedId,
                        onClick = { onOpen(s.id) },
                        onLongClick = { menuFor = s.id },
                    )
                }
                if (state.archived.isEmpty() && !state.loading) item(key = "none") { EmptyCaption("Nothing archived.") }
            } else {
                item(key = "new") { ConversationItem("New chat", icon = IconName.Add, onClick = onNewChat) }
                item(key = "status") { ConversationItem("Status", icon = IconName.MonitorHeart, onClick = onStatus) }
                item(key = "cron") { ConversationItem("Cron", icon = IconName.Schedule, onClick = onCron) }
                state.grouped.forEach { (profile, sessions) ->
                    item(key = "p:$profile") { DrawerLabel("${profile.uppercase()} PROFILE") }
                    items(sessions, key = { "s:${it.id}" }) { s ->
                        ConversationItem(
                            title = s.displayTitle,
                            meta = ageLabel(s.startedAt, now),
                            selected = s.id == state.selectedId,
                            onClick = { onOpen(s.id) },
                            onLongClick = { menuFor = s.id },
                        )
                    }
                }
                if (state.grouped.isEmpty() && !state.loading) item(key = "none") { EmptyCaption("No sessions match.") }
                item(key = "gap") { Spacer(Modifier.height(8.dp)) }
                item(key = "archived") {
                    ConversationItem(
                        "Archived",
                        meta = state.archivedCount.takeIf { it > 0 }?.toString(),
                        icon = IconName.Archive,
                        onClick = { onShowArchived(true) },
                    )
                }
            }
        }
        HorizontalDivider(thickness = 1.dp, color = colors.hairline)
        Box(Modifier.padding(8.dp)) {
            ConversationItem("Settings", icon = IconName.Settings, onClick = onSettings)
        }
    }

    val menuSession = menuFor?.let(byId::get)
    MenuSheet(
        visible = menuSession != null,
        onDismiss = { menuFor = null },
        items = menuSession?.let { s ->
            listOf(
                MenuItem("Rename", IconName.Edit) {
                    renameText = s.title.orEmpty()
                    renameFor = s.id
                },
                if (s.archived) {
                    MenuItem("Unarchive", IconName.Unarchive) { onArchive(s.id, false) }
                } else {
                    MenuItem("Archive", IconName.Archive) { onArchive(s.id, true) }
                },
                MenuItem("Delete", IconName.Delete, danger = true) { deleteFor = s.id },
            )
        }.orEmpty(),
    )

    renameFor?.let { id ->
        ConfirmDialog(
            title = "Rename session",
            body = null,
            confirmText = "Save",
            onConfirm = {
                val title = renameText.trim()
                if (title.isNotEmpty()) onRename(id, title)
                renameFor = null
            },
            onDismiss = { renameFor = null },
        ) {
            TsukaimaTextField(value = renameText, onValueChange = { renameText = it })
        }
    }

    deleteFor?.let { id ->
        ConfirmDialog(
            title = "Delete session?",
            body = "Messages and tool output are removed from Hermes. This can’t be undone.",
            confirmText = "Delete",
            danger = true,
            onConfirm = {
                onDelete(id)
                deleteFor = null
            },
            onDismiss = { deleteFor = null },
        )
    }
}

private val SessionDto.displayTitle: String
    get() = title?.takeIf { it.isNotBlank() } ?: preview?.takeIf { it.isNotBlank() } ?: "Untitled"

/** A search hit: its title, else the matched text without the server's `>>>` / `<<<` markers. */
internal val SessionDto.searchTitle: String
    get() = title?.takeIf { it.isNotBlank() }
        ?: snippet?.replace(">>>", "")?.replace("<<<", "")?.trim()?.takeIf { it.isNotEmpty() }
        ?: displayTitle

@Composable
private fun DrawerHeader(query: String, onQuery: (String) -> Unit) {
    val colors = MaterialTheme.tsukaima
    Column(
        Modifier.padding(start = 20.dp, top = 20.dp, end = 20.dp, bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Image(
                painter = painterResource(R.drawable.mark),
                contentDescription = null,
                colorFilter = ColorFilter.tint(colors.accent),
                modifier = Modifier.height(24.dp),
            )
            Text("Tsukaima", style = TsukaimaType.wordmark.copy(fontSize = 22.sp), color = colors.ink1)
            Text("使い魔", style = TextStyle(fontFamily = NotoSerifJp, fontSize = 11.sp), color = colors.ink3)
        }
        TsukaimaTextField(value = query, onValueChange = onQuery, placeholder = "Search sessions")
    }
}

/** `DrawerLabel` from `Drawer.jsx`: label type, 16dp top / 12dp side / 6dp bottom inside the 8dp list inset. */
@Composable
private fun DrawerLabel(text: String) {
    Text(
        text,
        style = TsukaimaType.label,
        color = MaterialTheme.tsukaima.ink3,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, top = 16.dp, end = 12.dp, bottom = 6.dp),
    )
}

@Composable
private fun EmptyCaption(text: String) {
    Text(
        text,
        style = TsukaimaType.caption,
        color = MaterialTheme.tsukaima.ink3,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 16.dp),
    )
}

private const val PREVIEW_NOW = 1_800_000_000_000L

private val previewSessions = listOf(
    SessionDto("s1", "Fix the cron job", startedAt = PREVIEW_NOW / 1000.0 - 2 * 3600, profileName = "default"),
    SessionDto("s2", "Summarize server logs", startedAt = PREVIEW_NOW / 1000.0 - 86_400, profileName = "default"),
    SessionDto("s3", "Backup rotation plan", startedAt = PREVIEW_NOW / 1000.0 - 3 * 86_400, profile = "ops"),
    SessionDto("s4", "Paper triage", startedAt = PREVIEW_NOW / 1000.0 - 4 * 86_400, profile = "research"),
    SessionDto("s5", "Dataset licence check", startedAt = PREVIEW_NOW / 1000.0 - 8 * 86_400, profile = "research"),
    SessionDto("s6", "Old migration notes", profile = "ops", archived = true),
)

@Composable
private fun DrawerPreview(dark: Boolean, archived: Boolean = false) {
    TsukaimaTheme(darkTheme = dark) {
        SessionsDrawer(
            state = DrawerUiState(
                sessions = previewSessions,
                showArchived = archived,
                selectedId = "s1",
                loading = false,
            ),
            onNewChat = {}, onStatus = {}, onCron = {}, onOpen = {}, onRename = { _, _ -> },
            onArchive = { _, _ -> }, onDelete = {}, onSettings = {}, onQuery = {}, onShowArchived = {},
            modifier = Modifier.width(296.dp),
            now = PREVIEW_NOW,
        )
    }
}

@Preview(name = "Drawer light", heightDp = 760)
@Composable
private fun DrawerLightPreview() = DrawerPreview(dark = false)

@Preview(name = "Drawer dark", heightDp = 760)
@Composable
private fun DrawerDarkPreview() = DrawerPreview(dark = true)

@Preview(name = "Drawer archived light", heightDp = 480)
@Composable
private fun DrawerArchivedLightPreview() = DrawerPreview(dark = false, archived = true)

@Preview(name = "Drawer archived dark", heightDp = 480)
@Composable
private fun DrawerArchivedDarkPreview() = DrawerPreview(dark = true, archived = true)
