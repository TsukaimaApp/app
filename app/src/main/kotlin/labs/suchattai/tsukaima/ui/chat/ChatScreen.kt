package labs.suchattai.tsukaima.ui.chat

import android.content.ClipData
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import labs.suchattai.tsukaima.domain.chat.AgentStatus
import labs.suchattai.tsukaima.domain.chat.ChatItem
import labs.suchattai.tsukaima.domain.chat.ChatState
import labs.suchattai.tsukaima.domain.chat.ClarifyQuestion
import labs.suchattai.tsukaima.domain.chat.ToolStatus
import labs.suchattai.tsukaima.domain.chat.running
import labs.suchattai.tsukaima.data.gateway.ApprovalChoice
import labs.suchattai.tsukaima.ui.chat.components.ApprovalCard
import labs.suchattai.tsukaima.ui.chat.components.AssistantMessage
import labs.suchattai.tsukaima.ui.chat.components.ChatSkeleton
import labs.suchattai.tsukaima.ui.chat.components.ChatTopBar
import labs.suchattai.tsukaima.ui.chat.components.ClarifyCard
import labs.suchattai.tsukaima.ui.chat.components.Composer
import labs.suchattai.tsukaima.ui.chat.components.MediaHost
import labs.suchattai.tsukaima.ui.chat.components.SecretCard
import labs.suchattai.tsukaima.ui.chat.components.StatusLine
import labs.suchattai.tsukaima.ui.chat.components.ToolRow
import labs.suchattai.tsukaima.ui.chat.components.UserBubble
import labs.suchattai.tsukaima.ui.components.ConfirmDialog
import labs.suchattai.tsukaima.ui.components.IconName
import labs.suchattai.tsukaima.ui.components.MenuItem
import labs.suchattai.tsukaima.ui.components.MenuSheet
import labs.suchattai.tsukaima.ui.components.TsukaimaTextField
import labs.suchattai.tsukaima.ui.theme.TsukaimaTheme
import labs.suchattai.tsukaima.ui.theme.tsukaima

/** User actions of the chat screen; [ChatScreen] binds them to the [ChatViewModel]. */
class ChatActions(
    val onInput: (String) -> Unit = {},
    val onSend: () -> Unit = {},
    val onAttach: () -> Unit = {},
    val onRemoveAttachment: (String) -> Unit = {},
    val onRetryAttachment: (String) -> Unit = {},
    val onOpenMedia: (String) -> Unit = {},
    val previewMedia: suspend (String) -> ImageBitmap? = { null },
    val onApprove: (String) -> Unit = {},
    val onDeny: (String) -> Unit = {},
    val onApproveFor: (id: String, choice: ApprovalChoice) -> Unit = { _, _ -> },
    val onSecret: (id: String, value: String) -> Unit = { _, _ -> },
    val onSecretCancel: (String) -> Unit = {},
    val onStop: () -> Unit = {},
    val onClarify: (id: String, answers: Map<String, String?>) -> Unit = { _, _ -> },
    val onClarifySkip: (String) -> Unit = {},
    val onMenu: (ChatMenu?) -> Unit = {},
    val onDialog: (ChatDialog?) -> Unit = {},
    val onModel: (provider: String, model: String) -> Unit = { _, _ -> },
    val onWorkspace: (String) -> Unit = {},
    val onRename: (String) -> Unit = {},
    val onArchive: () -> Unit = {},
    val onCopyId: () -> Unit = {},
    val onDelete: () -> Unit = {},
)

/**
 * The chat (HANDOFF §3, `chat-screen.jsx`): top bar, tappable status line ([onStatus]), transcript, composer,
 * and the model / workspace / overflow sheets with their dialogs. [onDeleted] runs after the session was
 * archived or deleted from here.
 */
@Composable
fun ChatScreen(
    vm: ChatViewModel,
    onMenu: () -> Unit,
    showMenu: Boolean,
    onStatus: () -> Unit,
    onDeleted: () -> Unit,
    visibleSession: MutableStateFlow<String?>? = null,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    // While resumed, this chat's live session is "on screen": its approvals show here, not as notifications.
    val live = state.chat.liveSessionId
    if (visibleSession != null) {
        LifecycleResumeEffect(live, visibleSession) {
            if (live != null) visibleSession.value = live
            onPauseOrDispose { if (live != null) visibleSession.compareAndSet(live, null) }
        }
    }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val resolver = LocalContext.current.contentResolver
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) vm.attach(uris.map { ContentPickedFile(resolver, it) })
    }
    LaunchedEffect(vm) { vm.closed.collect { onDeleted() } }
    ChatContent(
        state = state,
        showMenu = showMenu,
        onDrawer = onMenu,
        onStatus = onStatus,
        actions = ChatActions(
            onInput = vm::setInput,
            onSend = vm::send,
            onAttach = { picker.launch(arrayOf("*/*")) },
            onRemoveAttachment = vm::removeAttachment,
            onRetryAttachment = vm::retryAttachment,
            onOpenMedia = vm::openMedia,
            previewMedia = vm::preview,
            onApprove = vm::approve,
            onDeny = vm::deny,
            onApproveFor = vm::answerApproval,
            onSecret = vm::answerSecret,
            onSecretCancel = vm::declineSecret,
            onStop = vm::interrupt,
            onClarify = vm::answerClarify,
            onClarifySkip = vm::skipClarify,
            onMenu = vm::showMenu,
            onDialog = vm::showDialog,
            onModel = vm::setModel,
            onWorkspace = vm::setWorkspace,
            onRename = vm::rename,
            onArchive = vm::archive,
            onCopyId = {
                vm.copySessionId { id ->
                    scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Session ID", id))) }
                }
            },
            onDelete = vm::delete,
        ),
    )
}

/** Stateless body of [ChatScreen], for previews. */
@Composable
fun ChatContent(
    state: ChatUiState,
    showMenu: Boolean,
    onDrawer: () -> Unit,
    onStatus: () -> Unit,
    actions: ChatActions,
    listState: LazyListState = rememberLazyListState(),
) {
    val colors = MaterialTheme.tsukaima
    val overflow = overflowItems(state, actions)
    Column(
        Modifier
            .fillMaxSize()
            .background(colors.surfacePage)
            .imePadding(),
    ) {
        ChatTopBar(
            modelLabel = state.model ?: "Model",
            onMenu = onDrawer,
            showMenu = showMenu,
            onModel = { actions.onMenu(ChatMenu.Model) },
            onMore = { if (overflow.isNotEmpty()) actions.onMenu(ChatMenu.Overflow) },
            moreEnabled = overflow.isNotEmpty(),
        )
        StatusLine(state.status, onClick = onStatus)
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            when {
                state.loading -> ChatSkeleton(Modifier.padding(top = 10.dp))
                state.empty -> EmptyChat(state.workspace?.let { ChatSync.displayPath(it, state.workspaces?.home) }, { actions.onMenu(ChatMenu.Workspace) })
                else -> Transcript(state, actions, listState)
            }
        }
        Composer(
            value = state.input,
            onValueChange = actions.onInput,
            onSend = actions.onSend,
            onAttach = actions.onAttach,
            enabled = !state.offline,
            canSend = state.canSend,
            attachments = state.attachments,
            onRemoveAttachment = actions.onRemoveAttachment,
            onRetryAttachment = actions.onRetryAttachment,
            running = state.chat.status.running,
            onStop = actions.onStop,
        )
    }
    ChatSheets(state, actions, overflow)
}

@Composable
private fun Transcript(state: ChatUiState, actions: ChatActions, listState: LazyListState) {
    val items = state.chat.items
    val media = remember(state.mediaState, actions) { MediaHost(state.mediaState, actions.onOpenMedia, actions.previewMedia) }
    // Follow the bottom while the user is there; a manual scroll up stops following until they come back.
    var follow by rememberSaveable { mutableStateOf(true) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress to listState.canScrollForward }
            .collect { (scrolling, canScrollForward) -> if (scrolling) follow = !canScrollForward }
    }
    val tail = items.lastOrNull()?.let { it.id to ((it as? ChatItem.AssistantMessage)?.text?.length ?: 0) }
    LaunchedEffect(items.size, tail) {
        if (follow && items.isNotEmpty()) listState.scrollToItem(items.size) // the end spacer: bottom of the last item
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 6.dp),
    ) {
        items(items, key = { it.id }, contentType = { it::class }) { item ->
            when (item) {
                is ChatItem.UserMessage -> UserBubble(item.text, attachments = item.attachments)
                is ChatItem.AssistantMessage -> AssistantMessage(item.text, item.streaming, item.reasoning, media = media)
                is ChatItem.ToolCall -> ToolRow(item)
                is ChatItem.Approval -> ApprovalCard(
                    item = item,
                    onApprove = { actions.onApprove(item.id) },
                    onDeny = { actions.onDeny(item.id) },
                    decision = state.decisions[item.id],
                    answerable = ChatSync.answerable(item),
                    onApproveFor = { actions.onApproveFor(item.id, it) },
                )
                is ChatItem.SecretPrompt -> SecretCard(
                    item = item,
                    onSend = { actions.onSecret(item.id, it) },
                    onCancel = { actions.onSecretCancel(item.id) },
                )
                is ChatItem.Clarify -> ClarifyCard(
                    item = item,
                    onSend = { actions.onClarify(item.id, it) },
                    onSkipAll = { actions.onClarifySkip(item.id) },
                )
                is ChatItem.Thinking -> Spacer(Modifier.height(0.dp))
            }
        }
        item(key = "end") { Spacer(Modifier.height(10.dp)) }
    }
}

/**
 * The top bar's overflow entries: Stop while a turn runs, then the session actions once the session is stored.
 * A brand-new chat with no stored id yet offers only what works without one (Stop, and Copy session ID — the live
 * id — once a live session exists); empty disables the button rather than opening a sheet that does nothing.
 */
internal fun overflowItems(state: ChatUiState, actions: ChatActions): List<MenuItem> {
    val stop = MenuItem("Stop", IconName.Stop, onClick = actions.onStop).takeIf { state.chat.status.running && !state.offline }
    val copy = MenuItem("Copy session ID", IconName.ContentCopy, onClick = actions.onCopyId)
    if (state.chat.storedSessionId == null) {
        return listOfNotNull(stop, copy.takeIf { state.chat.liveSessionId != null })
    }
    return listOfNotNull(
        stop,
        MenuItem("Rename", IconName.Edit) { actions.onDialog(ChatDialog.Rename) },
        MenuItem("Archive", IconName.Archive, onClick = actions.onArchive),
        copy,
        MenuItem("Delete session", IconName.Delete, danger = true) { actions.onDialog(ChatDialog.DeleteConfirm) },
    )
}

@Composable
private fun ChatSheets(state: ChatUiState, actions: ChatActions, overflow: List<MenuItem>) {
    val dismiss = { actions.onMenu(null) }
    MenuSheet(state.menu == ChatMenu.Model, dismiss, modelItems(state, actions))
    MenuSheet(state.menu == ChatMenu.Workspace, dismiss, workspaceItems(state, actions))
    MenuSheet(state.menu == ChatMenu.Overflow && overflow.isNotEmpty(), dismiss, overflow)
    when (state.dialog) {
        ChatDialog.Rename -> {
            var name by rememberSaveable { mutableStateOf(state.chat.title.orEmpty()) }
            ConfirmDialog(
                title = "Rename session",
                body = null,
                confirmText = "Save",
                onConfirm = { actions.onRename(name) },
                onDismiss = { actions.onDialog(null) },
            ) { TsukaimaTextField(name, { name = it }, placeholder = "Session name") }
        }
        ChatDialog.DeleteConfirm -> ConfirmDialog(
            title = "Delete session?",
            body = "Messages and tool output are removed from Hermes. This can’t be undone.",
            confirmText = "Delete",
            onConfirm = actions.onDelete,
            onDismiss = { actions.onDialog(null) },
            danger = true,
        )
        null -> Unit
    }
}

/** Providers → models, check on the current one. Unauthenticated providers are left out. */
private fun modelItems(state: ChatUiState, actions: ChatActions): List<MenuItem> {
    val providers = state.models?.providers.orEmpty().filter { it.authenticated != false }
    val labelled = providers.size > 1
    val items = providers.flatMap { p ->
        p.models.map { m ->
            val current = m == state.model && (state.provider == null || state.provider == p.slug)
            MenuItem(if (labelled) "$m · ${p.name ?: p.slug}" else m, null, checked = current) { actions.onModel(p.slug, m) }
        }
    }
    return items.ifEmpty { listOf(MenuItem(if (state.models == null) "Loading models…" else "No models available", null) {}) }
}

private fun workspaceItems(state: ChatUiState, actions: ChatActions): List<MenuItem> {
    val paths = (state.workspaces?.let(ChatSync::workspacePaths).orEmpty() + listOfNotNull(state.workspace)).distinct()
    return paths.map { path ->
        MenuItem(ChatSync.displayPath(path, state.workspaces?.home), IconName.Folder, checked = path == state.workspace) {
            actions.onWorkspace(path)
        }
    }.ifEmpty { listOf(MenuItem("Loading…", null) {}) }
}

// ---- previews -------------------------------------------------------------------------------------

private val previewStreaming = ChatState(
    items = listOf(
        ChatItem.UserMessage("u1", "What kernel is this box running?"),
        ChatItem.ToolCall("t1", "terminal", "uname -a", null, ToolStatus.Done, "Linux claude 6.17.2-1-pve", 0, 0.4),
        ChatItem.AssistantMessage("a1", "The kernel is **6.17.2-1-pve**, a Proxmox", streaming = true, reasoning = null),
    ),
    status = AgentStatus.Replying,
    storedSessionId = "20261020_010916_48c967",
)

private val previewOffline = previewStreaming.copy(
    items = previewStreaming.items.map { if (it is ChatItem.AssistantMessage) it.copy(streaming = false) else it },
    status = AgentStatus.Offline,
)

private val previewApproval = ChatState(
    items = listOf(
        ChatItem.UserMessage("u1", "Clean up the temp directory."),
        ChatItem.Approval("srq-1", "req-1", "rm -rf /tmp/build-cache", "terminal", answered = false),
    ),
    status = AgentStatus.Approval,
    storedSessionId = "20261020_010916_48c967",
)

private val previewClarify = ChatState(
    items = listOf(
        ChatItem.UserMessage("u1", "Pick a colour scheme for the dashboard."),
        ChatItem.Clarify(
            "srq-2",
            listOf(ClarifyQuestion("q1", "Which colour do you prefer?", listOf("red", "green", "blue"), multiSelect = false)),
            answered = false,
        ),
    ),
    status = AgentStatus.Question,
    storedSessionId = "20261020_010916_48c967",
)

@Composable
private fun ChatPreview(dark: Boolean, state: ChatUiState) {
    TsukaimaTheme(darkTheme = dark) {
        ChatContent(state, showMenu = true, onDrawer = {}, onStatus = {}, actions = ChatActions())
    }
}

@Preview(name = "Chat empty light", heightDp = 640)
@Composable
private fun ChatEmptyLight() = ChatPreview(false, ChatUiState(workspace = "/home/hermes/projects/hermes", model = "Hermes 4 · 405B"))

@Preview(name = "Chat empty dark", heightDp = 640)
@Composable
private fun ChatEmptyDark() = ChatPreview(true, ChatUiState(workspace = "/home/hermes/projects/hermes", model = "Hermes 4 · 405B"))

@Preview(name = "Chat streaming light", heightDp = 640)
@Composable
private fun ChatStreamingLight() = ChatPreview(false, ChatUiState(chat = previewStreaming, model = "Hermes 4 · 405B"))

@Preview(name = "Chat streaming dark", heightDp = 640)
@Composable
private fun ChatStreamingDark() = ChatPreview(true, ChatUiState(chat = previewStreaming, model = "Hermes 4 · 405B"))

@Preview(name = "Chat approval light", heightDp = 640)
@Composable
private fun ChatApprovalLight() = ChatPreview(false, ChatUiState(chat = previewApproval, model = "Hermes 4 · 405B"))

@Preview(name = "Chat approval dark", heightDp = 640)
@Composable
private fun ChatApprovalDark() = ChatPreview(true, ChatUiState(chat = previewApproval, model = "Hermes 4 · 405B"))

@Preview(name = "Chat question light", heightDp = 640)
@Composable
private fun ChatQuestionLight() = ChatPreview(false, ChatUiState(chat = previewClarify, model = "Hermes 4 · 405B"))

@Preview(name = "Chat question dark", heightDp = 640)
@Composable
private fun ChatQuestionDark() = ChatPreview(true, ChatUiState(chat = previewClarify, model = "Hermes 4 · 405B"))

@Preview(name = "Chat offline light", heightDp = 640)
@Composable
private fun ChatOfflineLight() =
    ChatPreview(false, ChatUiState(chat = previewOffline, model = "Hermes 4 · 405B"))

@Preview(name = "Chat offline dark", heightDp = 640)
@Composable
private fun ChatOfflineDark() =
    ChatPreview(true, ChatUiState(chat = previewOffline, model = "Hermes 4 · 405B"))
