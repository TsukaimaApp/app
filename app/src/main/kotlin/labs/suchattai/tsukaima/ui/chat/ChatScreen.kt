package labs.suchattai.tsukaima.ui.chat

import android.Manifest
import android.content.ClipData
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import labs.suchattai.tsukaima.domain.chat.AgentStatus
import labs.suchattai.tsukaima.domain.chat.ChatItem
import labs.suchattai.tsukaima.domain.chat.ChatReducer
import labs.suchattai.tsukaima.domain.chat.SideKind
import labs.suchattai.tsukaima.ui.components.PickerSheet
import labs.suchattai.tsukaima.ui.components.PickerNode
import labs.suchattai.tsukaima.domain.chat.ModelTree
import labs.suchattai.tsukaima.domain.chat.ModelEntry
import labs.suchattai.tsukaima.domain.chat.ChatState
import labs.suchattai.tsukaima.domain.chat.ClarifyQuestion
import labs.suchattai.tsukaima.domain.chat.ConnectionAnswer
import labs.suchattai.tsukaima.domain.chat.ToolStatus
import labs.suchattai.tsukaima.domain.chat.running
import labs.suchattai.tsukaima.domain.voice.VoicePhase
import labs.suchattai.tsukaima.domain.voice.VoiceState
import labs.suchattai.tsukaima.data.gateway.ApprovalChoice
import labs.suchattai.tsukaima.ui.chat.components.ApprovalCard
import labs.suchattai.tsukaima.ui.chat.components.ArchivedBanner
import labs.suchattai.tsukaima.ui.chat.components.SubagentRow
import labs.suchattai.tsukaima.ui.chat.components.AssistantMessage
import labs.suchattai.tsukaima.ui.chat.components.ChatSkeleton
import labs.suchattai.tsukaima.ui.chat.components.ChatTopBar
import labs.suchattai.tsukaima.ui.chat.components.ClarifyCard
import labs.suchattai.tsukaima.ui.chat.components.Composer
import labs.suchattai.tsukaima.ui.chat.components.MediaHost
import labs.suchattai.tsukaima.ui.chat.components.SecretCard
import labs.suchattai.tsukaima.ui.chat.components.ConnectionCard
import labs.suchattai.tsukaima.ui.chat.components.SideNoteCard
import labs.suchattai.tsukaima.ui.chat.components.StatusLine
import labs.suchattai.tsukaima.ui.chat.components.TodoCard
import labs.suchattai.tsukaima.ui.chat.components.ToolRow
import labs.suchattai.tsukaima.ui.chat.components.UserBubble
import labs.suchattai.tsukaima.ui.voice.VoiceScreen
import labs.suchattai.tsukaima.ui.voice.voiceLanguageItems
import labs.suchattai.tsukaima.ui.voice.voiceText
import labs.suchattai.tsukaima.ui.components.ConfirmDialog
import labs.suchattai.tsukaima.ui.components.IconName
import labs.suchattai.tsukaima.ui.components.MenuItem
import labs.suchattai.tsukaima.ui.components.MenuSheet
import labs.suchattai.tsukaima.ui.components.TsukaimaTextField
import labs.suchattai.tsukaima.ui.theme.Motion
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
    val previewImage: suspend (String) -> ImageBitmap? = { null },
    val imageAllowed: (String) -> Boolean = { false },
    val onAllowImage: (String) -> Unit = {},
    val previewToolImage: suspend (ChatItem.ToolCall) -> ImageBitmap? = { null },
    val onApprove: (String) -> Unit = {},
    val onDeny: (String) -> Unit = {},
    val onApproveFor: (id: String, choice: ApprovalChoice) -> Unit = { _, _ -> },
    val onSecret: (id: String, value: String) -> Unit = { _, _ -> },
    val onSecretCancel: (String) -> Unit = {},

    /** A connection card's answer for operation `opId`: a row's Connect / Skip. */
    val onConnectionAnswer: (opId: String, answer: ConnectionAnswer) -> Unit = { _, _ -> },

    /** "Connect" on a row with a link: open it in the browser. */
    val onConnectionOpen: (opId: String, name: String) -> Unit = { _, _ -> },
    val onConnectionSkipAll: (opId: String) -> Unit = {},
    val onConnectionContinue: (opId: String) -> Unit = {},
    val onStop: () -> Unit = {},
    val onVoice: () -> Unit = {},
    val onVoiceClose: () -> Unit = {},
    val onVoiceMute: (Boolean) -> Unit = {},
    val onVoiceTap: () -> Unit = {},

    /** Leave voice mode for the keyboard; [ChatContent] then focuses the composer. */
    val onVoiceKeyboard: () -> Unit = {},

    /** Voice language picked in its sheet (null = system default). */
    val onVoiceLanguage: (String?) -> Unit = {},
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
    val onReasoning: (String) -> Unit = {},
    val onCompact: () -> Unit = {},
    val onUnarchive: () -> Unit = {},
    val onConfirmModel: () -> Unit = {},
    val onRetrySend: (String) -> Unit = {},
    val onDismissFailed: (String) -> Unit = {},
    val onMessageMenu: (String?) -> Unit = {},
    val onToggleReasoning: (String) -> Unit = {},
    val onCopyMessage: (id: String, reasoning: Boolean) -> Unit = { _, _ -> },
    val onEditMessage: (String) -> Unit = {},
    val onCancelEdit: () -> Unit = {},
    val onRegenerate: (String) -> Unit = {},
    val onSendAside: (SideKind) -> Unit = {},
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
    val context = LocalContext.current
    val mic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.openVoice() else vm.voiceDenied()
    }
    LaunchedEffect(vm) { vm.closed.collect { onDeleted() } }
    // Voice mode ends when the chat leaves the screen (app in the background, another destination), not on rotation.
    val activity = LocalActivity.current
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { if (activity?.isChangingConfigurations != true) vm.closeVoice() }
    // Back from a connection's browser leg: the backend reads the account now.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.onScreenResumed() }
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
            previewImage = vm::imagePreview,
            imageAllowed = vm::imageAllowed,
            onAllowImage = vm::allowImage,
            previewToolImage = vm::toolImage,
            onApprove = vm::approve,
            onDeny = vm::deny,
            onApproveFor = vm::answerApproval,
            onSecret = vm::answerSecret,
            onSecretCancel = vm::declineSecret,
            onConnectionAnswer = vm::answerConnection,
            onConnectionOpen = vm::openConnectUrl,
            onConnectionSkipAll = vm::skipConnection,
            onConnectionContinue = vm::continueConnection,
            onStop = vm::interrupt,
            onVoice = {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    vm.openVoice()
                } else {
                    mic.launch(Manifest.permission.RECORD_AUDIO)
                }
            },
            onVoiceClose = vm::closeVoice,
            onVoiceMute = vm::setVoiceMuted,
            onVoiceTap = vm::tapVoiceIndicator,
            onVoiceKeyboard = vm::closeVoice,
            onVoiceLanguage = vm::setVoiceLanguage,
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
            onReasoning = vm::setReasoning,
            onCompact = vm::compact,
            onUnarchive = vm::unarchive,
            onConfirmModel = vm::confirmModel,
            onRetrySend = vm::retrySend,
            onDismissFailed = vm::dismissFailed,
            onMessageMenu = vm::showMessageMenu,
            onToggleReasoning = vm::toggleReasoning,
            onCopyMessage = { id, reasoning ->
                vm.copyMessage(id, reasoning) { text ->
                    scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Message", text))) }
                }
            },
            onEditMessage = vm::editMessage,
            onCancelEdit = vm::cancelEdit,
            onRegenerate = vm::regenerate,
            onSendAside = vm::sendAside,
        ),
    )
}

/**
 * Stateless body of [ChatScreen], for previews. While [ChatUiState.voice] is set the voice screen replaces the chat
 * (crossfade), Back leaves it, the screen stays on, and listening starts with a light haptic; the sheets serve both.
 */
@Composable
fun ChatContent(
    state: ChatUiState,
    showMenu: Boolean,
    onDrawer: () -> Unit,
    onStatus: () -> Unit,
    actions: ChatActions,
    listState: LazyListState = rememberLazyListState(),
) {
    val overflow = overflowItems(state, actions)
    val voiceOpen = state.voice != null
    val composerFocus = remember { FocusRequester() }
    var focusPending by remember { mutableStateOf(false) }
    // The voice screen fading out still needs a state to draw: the last one seen.
    val lastVoice = remember { arrayOfNulls<VoiceState>(1) }
    state.voice?.let { lastVoice[0] = it }

    BackHandler(enabled = voiceOpen) { actions.onVoiceClose() }
    if (voiceOpen) {
        val view = LocalView.current
        DisposableEffect(view) {
            view.keepScreenOn = true
            onDispose { view.keepScreenOn = false }
        }
    }
    val haptics = LocalHapticFeedback.current
    val phase = state.voice?.phase
    LaunchedEffect(phase) { if (phase == VoicePhase.Listening) haptics.performHapticFeedback(HapticFeedbackType.Confirm) }
    LaunchedEffect(focusPending, voiceOpen) {
        if (!focusPending || voiceOpen) return@LaunchedEffect
        delay(Motion.screen.toLong()) // after the crossfade, with the composer in place
        focusPending = false
        runCatching { composerFocus.requestFocus() } // not attached (e.g. still loading): nothing to focus
    }

    Crossfade(targetState = voiceOpen, animationSpec = tween(Motion.screen, easing = Motion.easing), label = "voiceMode") { open ->
        val voice = state.voice ?: lastVoice[0]
        if (open && voice != null) {
            VoiceScreen(
                state = state,
                voice = voice,
                actions = actions,
                onModel = { actions.onMenu(ChatMenu.Model) },
                onKeyboard = {
                    actions.onVoiceKeyboard()
                    focusPending = true
                },
            )
        } else {
            ChatColumn(state, showMenu, onDrawer, onStatus, actions, listState, overflow, composerFocus)
        }
    }
    ChatSheets(state, actions, overflow)
}

@Composable
private fun ChatColumn(
    state: ChatUiState,
    showMenu: Boolean,
    onDrawer: () -> Unit,
    onStatus: () -> Unit,
    actions: ChatActions,
    listState: LazyListState,
    overflow: List<MenuItem>,
    composerFocus: FocusRequester,
) {
    val colors = MaterialTheme.tsukaima
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
        StatusLine(state.status, onClick = onStatus, meter = state.chat.usage?.meter)
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            when {
                state.loading -> ChatSkeleton(Modifier.padding(top = 10.dp), caption = state.chat.resumeProgress)
                state.empty -> EmptyChat(state.workspace?.let { ChatSync.displayPath(it, state.workspaces?.home) }, { actions.onMenu(ChatMenu.Workspace) })
                else -> Transcript(state, actions, listState)
            }
        }
        if (state.archived) ArchivedBanner(onUnarchive = actions.onUnarchive)
        Composer(
            value = state.input,
            onValueChange = actions.onInput,
            onSend = actions.onSend,
            onAttach = actions.onAttach,
            enabled = state.writable,
            disabledPlaceholder = if (state.archived && !state.offline) "This chat is archived" else "Hermes is unreachable",
            canSend = state.canSend,
            attachments = state.attachments,
            onRemoveAttachment = actions.onRemoveAttachment,
            onRetryAttachment = actions.onRetryAttachment,
            running = state.chat.status.running,
            onStop = actions.onStop,
            onVoice = actions.onVoice,
            focusRequester = composerFocus,
            editCaption = state.editCaption,
            onCancelEdit = actions.onCancelEdit,
        )
    }
}

@Composable
private fun Transcript(state: ChatUiState, actions: ChatActions, listState: LazyListState) {
    val items = state.chat.items
    val media = remember(state.mediaState, actions) {
        MediaHost(
            state.mediaState, actions.onOpenMedia, actions.previewMedia,
            actions.previewImage, actions.imageAllowed, actions.onAllowImage, actions.previewToolImage,
        )
    }
    // Follow the bottom while the user is there; a manual scroll up stops following until they come back.
    var follow by rememberSaveable { mutableStateOf(true) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress to listState.canScrollForward }
            .collect { (scrolling, canScrollForward) -> if (scrolling) follow = !canScrollForward }
    }
    val tail = transcriptTailKey(items)
    LaunchedEffect(items.size, tail) {
        if (follow && items.isNotEmpty()) listState.scrollToItem(items.size) // the end spacer: bottom of the last item
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 6.dp),
    ) {
        items(items, key = { it.id }, contentType = { it::class }) { item -> TranscriptItem(item, state, actions, media) }
        item(key = "end") { Spacer(Modifier.height(10.dp)) }
    }
}

/**
 * One transcript row with the chat's own components and callbacks; shared by the chat and the voice screen. With
 * [revealed] (voice mode, [VoiceState.revealed]) a reply shows only what was spoken so far ([voiceText]).
 */
@Composable
internal fun TranscriptItem(item: ChatItem, state: ChatUiState, actions: ChatActions, media: MediaHost, revealed: Map<String, Int>? = null) {
    when (item) {
        is ChatItem.UserMessage -> UserBubble(
            item.text,
            attachments = item.attachments,
            failed = item.failed,
            queued = item.queued,
            onRetry = { actions.onRetrySend(item.id) },
            onDismiss = { actions.onDismissFailed(item.id) },
            onLongPress = { actions.onMessageMenu(item.id) },
        )
        is ChatItem.AssistantMessage -> {
            // Voice mode reveals the reply as it is spoken, without its reasoning (the transcript is the spoken conversation).
            val text = if (revealed == null) item.text else voiceText(item, revealed)
            val streaming = item.streaming || text.length < item.text.length
            AssistantMessage(
                text,
                streaming,
                item.reasoning.takeIf { revealed == null },
                media = media,
                onLongPress = { actions.onMessageMenu(item.id) }.takeIf { !streaming },
                reasoningOpen = ChatReducer.reasoningOpen(state.chat, item),
                onToggleReasoning = { actions.onToggleReasoning(item.id) },
            )
        }
        is ChatItem.ToolCall -> ToolRow(item, media = media)
        is ChatItem.Subagent -> SubagentRow(item)
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
        is ChatItem.ConnectionRequest -> ConnectionCard(
            item = item,
            onAnswer = { actions.onConnectionAnswer(item.opId, it) },
            onOpen = { actions.onConnectionOpen(item.opId, it) },
            onSkipAll = { actions.onConnectionSkipAll(item.opId) },
            onContinue = { actions.onConnectionContinue(item.opId) },
        )
        is ChatItem.SideNote -> SideNoteCard(item, media = media)
        is ChatItem.Todos -> TodoCard(item)
        is ChatItem.Thinking -> Spacer(Modifier.height(0.dp))
    }
}

/**
 * What the transcript follows to the bottom on: the last item's id and, for a reply, its text length (so streaming
 * keeps it pinned). Only a reply's length counts — a [ChatItem.SideNote] finishing keeps the key, so a detached side
 * answer landing does not scroll the chat.
 */
internal fun transcriptTailKey(items: List<ChatItem>): Pair<String, Int>? =
    items.lastOrNull()?.let { it.id to ((it as? ChatItem.AssistantMessage)?.text?.length ?: 0) }

/**
 * The top bar's overflow entries: Stop while a turn runs, the side entries while text is typed ([ChatSides]), then the
 * session actions once the session is stored.
 * A brand-new chat with no stored id yet offers only what works without one (Stop, and Copy session ID — the live
 * id — once a live session exists); empty disables the button rather than opening a sheet that does nothing.
 */
internal fun overflowItems(state: ChatUiState, actions: ChatActions): List<MenuItem> {
    val running = state.chat.status.running
    val stop = MenuItem("Stop", IconName.Stop, onClick = actions.onStop).takeIf { running && !state.offline }
    val copy = MenuItem("Copy session ID", IconName.ContentCopy, onClick = actions.onCopyId)
    val live = state.chat.liveSessionId != null && state.writable
    val reasoning = MenuItem(reasoningMenuLabel(state.chat.reasoningEffort), IconName.Psychology) { actions.onMenu(ChatMenu.Reasoning) }
        .takeIf { live }
    // Compaction is idle-gated on the server: offered between turns only.
    val compact = MenuItem("Compact context", IconName.Compress, onClick = actions.onCompact)
        .takeIf { live && !running && !state.compacting && state.chat.items.isNotEmpty() }
    val sides = if (ChatSides.available(state)) {
        listOf(
            MenuItem("Send as side question", IconName.Forum) { actions.onSendAside(SideKind.Question) },
            MenuItem("Send as background task", IconName.Schedule) { actions.onSendAside(SideKind.Background) },
        )
    } else {
        emptyList()
    }
    if (state.chat.storedSessionId == null) {
        return listOfNotNull(stop) + sides + listOfNotNull(reasoning, compact, copy.takeIf { state.chat.liveSessionId != null })
    }
    return listOfNotNull(stop) + sides + listOfNotNull(
        reasoning,
        compact,
        MenuItem("Rename", IconName.Edit) { actions.onDialog(ChatDialog.Rename) },
        if (state.archived) {
            MenuItem("Unarchive", IconName.Unarchive, onClick = actions.onUnarchive)
        } else {
            MenuItem("Archive", IconName.Archive, onClick = actions.onArchive)
        },
        copy,
        MenuItem("Delete session", IconName.Delete, danger = true) { actions.onDialog(ChatDialog.DeleteConfirm) },
    )
}

/** "Reasoning · Low"; just "Reasoning" while the effort is unknown or the provider's default. */
internal fun reasoningMenuLabel(effort: String?): String {
    val label = REASONING_LEVELS.firstOrNull { it.first == effort }?.second ?: return "Reasoning"
    return "Reasoning · $label"
}

/** The reasoning sheet: every level, check on the session's current one. */
internal fun reasoningItems(state: ChatUiState, actions: ChatActions): List<MenuItem> =
    REASONING_LEVELS.map { (value, label) ->
        MenuItem(label, null, checked = state.chat.reasoningEffort == value) { actions.onReasoning(value) }
    }

/**
 * A message's long-press menu: Copy text, plus Copy reasoning for a reply that has some; Edit on a user message the
 * server has persisted, Regenerate on a reply whose user message it has. Edit and Regenerate are left out while a
 * turn runs or Hermes is unreachable ([ChatEdits.rewindable]).
 */
internal fun messageItems(state: ChatUiState, actions: ChatActions): List<MenuItem> {
    val item = state.chat.items.firstOrNull { it.id == state.messageMenu } ?: return emptyList()
    val reasoning = (item as? ChatItem.AssistantMessage)?.reasoning?.takeIf { it.isNotEmpty() }
    val rewind = ChatEdits.rewindable(state)
    val edit = (item as? ChatItem.UserMessage)?.takeIf { rewind && it.rowId != null && !it.failed }
    val regenerate = (item as? ChatItem.AssistantMessage)?.takeIf { rewind && ChatEdits.regenerateSource(state.chat.items, it.id) != null }
    return listOfNotNull(
        MenuItem("Copy text", IconName.ContentCopy) { actions.onCopyMessage(item.id, false) },
        reasoning?.let { MenuItem("Copy reasoning", IconName.Psychology) { actions.onCopyMessage(item.id, true) } },
        edit?.let { MenuItem("Edit", IconName.Edit) { actions.onEditMessage(it.id) } },
        regenerate?.let { MenuItem("Regenerate", IconName.Refresh) { actions.onRegenerate(it.id) } },
    )
}

@Composable
private fun ChatSheets(state: ChatUiState, actions: ChatActions, overflow: List<MenuItem>) {
    val dismiss = { actions.onMenu(null) }
    PickerSheet(state.menu == ChatMenu.Model, dismiss, "Model", modelNodes(state, actions))
    MenuSheet(state.menu == ChatMenu.Workspace, dismiss, workspaceItems(state, actions))
    MenuSheet(state.menu == ChatMenu.Overflow && overflow.isNotEmpty(), dismiss, overflow)
    MenuSheet(state.menu == ChatMenu.Reasoning, dismiss, reasoningItems(state, actions))
    MenuSheet(state.menu == ChatMenu.VoiceLanguage, dismiss, voiceLanguageItems(state.voiceLanguages, state.voiceLanguage, actions.onVoiceLanguage))
    val messageMenu = messageItems(state, actions)
    MenuSheet(messageMenu.isNotEmpty(), { actions.onMessageMenu(null) }, messageMenu)
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
        ChatDialog.ModelConfirm -> ConfirmDialog(
            title = "Switch to ${state.pendingModel?.model ?: "this model"}?",
            body = state.pendingModel?.message,
            confirmText = "Switch",
            onConfirm = actions.onConfirmModel,
            onDismiss = { actions.onDialog(null) },
        )
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

/**
 * Provider → model → flavor ([ModelTree]), check on the current one at every level. A lone provider skips its
 * level; a model without flavors picks directly.
 */
private fun modelNodes(state: ChatUiState, actions: ChatActions): List<PickerNode> {
    val providers = ModelTree.build(state.models?.providers.orEmpty())
    fun current(slug: String, model: String) = model == state.model && (state.provider == null || state.provider == slug)
    fun modelNode(slug: String, entry: ModelEntry): PickerNode =
        if (entry.flavors.isEmpty()) {
            PickerNode.Leaf(entry.base, checked = current(slug, entry.base)) { actions.onModel(slug, entry.base) }
        } else {
            PickerNode.Group(entry.base, entry.flavors.map { f -> PickerNode.Leaf(f.label, checked = current(slug, f.model)) { actions.onModel(slug, f.model) } })
        }
    val nodes = when (providers.size) {
        0 -> emptyList()
        1 -> providers.single().let { p -> p.models.map { modelNode(p.slug, it) } }
        else -> providers.map { p -> PickerNode.Group(p.name, p.models.map { modelNode(p.slug, it) }) }
    }
    return nodes.ifEmpty { listOf(PickerNode.Leaf(if (state.models == null) "Loading models…" else "No models available") {}) }
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
