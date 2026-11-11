package app.tsukaima.ui.chat

import app.tsukaima.ui.chat.components.AudioPlayback
import app.tsukaima.ui.chat.components.RecordingBar
import app.tsukaima.domain.chat.LocationText
import java.io.File
import androidx.compose.runtime.mutableIntStateOf
import app.tsukaima.ui.chat.components.SearchMark
import app.tsukaima.ui.chat.components.LocalChatSearch
import app.tsukaima.ui.chat.components.ChatSearchBar
import app.tsukaima.diagnostics.shareExport
import app.tsukaima.domain.chat.ChatExport
import app.tsukaima.domain.chat.ChatSearch
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import app.tsukaima.ui.chat.components.LatestButton
import app.tsukaima.ui.chat.components.StepsLine
import app.tsukaima.ui.chat.components.TimeMark
import app.tsukaima.ui.chat.components.OfflineBar
import app.tsukaima.ui.chat.components.ProcessChip
import app.tsukaima.ui.chat.components.ProcessSheet
import app.tsukaima.domain.chat.TranscriptRows
import app.tsukaima.domain.chat.TranscriptRow
import java.time.ZoneId
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
import androidx.compose.runtime.SideEffect
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
import app.tsukaima.domain.chat.AgentStatus
import app.tsukaima.domain.chat.ChatItem
import app.tsukaima.domain.chat.ChatReducer
import app.tsukaima.domain.chat.SideKind
import app.tsukaima.ui.components.PickerSheet
import app.tsukaima.ui.components.PickerNode
import app.tsukaima.domain.chat.ModelTree
import app.tsukaima.domain.chat.ModelEntry
import app.tsukaima.domain.chat.ChatState
import app.tsukaima.domain.chat.ClarifyQuestion
import app.tsukaima.domain.chat.ConnectionAnswer
import app.tsukaima.domain.chat.ToolStatus
import app.tsukaima.domain.chat.running
import app.tsukaima.domain.chat.queuesSends
import app.tsukaima.domain.voice.VoicePhase
import app.tsukaima.domain.voice.VoiceState
import app.tsukaima.data.gateway.ApprovalChoice
import app.tsukaima.ui.chat.components.ApprovalCard
import app.tsukaima.ui.chat.components.ArchivedBanner
import app.tsukaima.ui.chat.components.SubagentRow
import app.tsukaima.ui.chat.components.AssistantMessage
import app.tsukaima.ui.chat.components.ChatSkeleton
import app.tsukaima.ui.chat.components.ChatTopBar
import app.tsukaima.ui.chat.components.ClarifyCard
import app.tsukaima.ui.chat.components.Composer
import app.tsukaima.ui.chat.components.MediaHost
import app.tsukaima.ui.chat.components.SecretCard
import app.tsukaima.ui.chat.components.CommandNoteCard
import app.tsukaima.ui.chat.components.CommandSuggestions
import app.tsukaima.ui.chat.components.ReasoningPanel
import app.tsukaima.ui.chat.components.ReasoningStatusLine
import app.tsukaima.ui.chat.components.SelectTextView
import app.tsukaima.ui.chat.components.SubagentView
import app.tsukaima.ui.chat.components.ConnectionCard
import app.tsukaima.ui.chat.components.NoticeLine
import app.tsukaima.ui.chat.components.SideNoteCard
import app.tsukaima.ui.chat.components.StatusLine
import app.tsukaima.ui.chat.components.TodoCard
import app.tsukaima.ui.chat.components.ToolRow
import app.tsukaima.ui.chat.components.UserBubble
import app.tsukaima.ui.voice.VoiceScreen
import app.tsukaima.ui.voice.voiceLanguageItems
import app.tsukaima.ui.voice.voiceText
import app.tsukaima.ui.components.ConfirmDialog
import app.tsukaima.ui.components.IconName
import app.tsukaima.ui.components.MenuItem
import app.tsukaima.ui.components.MenuSheet
import app.tsukaima.ui.components.TsukaimaTextField
import app.tsukaima.ui.theme.Motion
import app.tsukaima.ui.theme.TsukaimaTheme
import app.tsukaima.ui.theme.tsukaima

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
    val previewToolImage: suspend (ChatItem.ToolCall) -> ImageResult = { ImageResult.Unavailable },
    /** A delivered audio or video file, fetched for playing in its card; null when it cannot be. */
    val playFile: suspend (String) -> File? = { null },
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
    val onToggleSteps: (String) -> Unit = {},
    val onCancelOffline: (String) -> Unit = {},
    val onProcesses: () -> Unit = {},
    val onProcessesClose: () -> Unit = {},
    val onProcessStop: (String) -> Unit = {},
    /** "Export chat": share the conversation as a Markdown file. */
    val onExport: () -> Unit = {},
    /** The attach sheet's entries beside "Photos and files" ([onAttach]). */
    val onCamera: () -> Unit = {},
    val onRecordAudio: () -> Unit = {},
    val onRecordStop: () -> Unit = {},
    val onRecordCancel: () -> Unit = {},
    val onLocation: () -> Unit = {},
    val onSelectText: (String?) -> Unit = {},
    val onToolOpened: () -> Unit = {},
    val onCopyMessage: (id: String, reasoning: Boolean) -> Unit = { _, _ -> },
    val onEditMessage: (String) -> Unit = {},
    val onCancelEdit: () -> Unit = {},
    val onRegenerate: (String) -> Unit = {},
    val onSendAside: (SideKind) -> Unit = {},

    /** "Branch from here" on a message (its item id). */
    val onBranch: (String) -> Unit = {},

    /** A subagent row's "Watch" (the row id), and its page's Close, Stop and message. */
    val onSubagentOpen: (String) -> Unit = {},
    val onSubagentClose: () -> Unit = {},
    val onSubagentStop: () -> Unit = {},
    val onSubagentSteer: (text: String, done: (Boolean) -> Unit) -> Unit = { _, done -> done(false) },

    /** Overflow "Steer now": the typed text goes into the running turn. */
    val onSteer: () -> Unit = {},
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
    onOpenSession: (String) -> Unit = {},
    /** Open with voice mode starting (the Voice shortcut, the widget's mic): once, not again after a rotation. */
    startVoice: Boolean = false,
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
    var voiceStarted by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(startVoice) {
        if (startVoice && !voiceStarted) {
            voiceStarted = true
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                vm.openVoice()
            } else {
                mic.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }
    // The attach sheet's captures. A photo is taken by the camera app into a file of ours; a clip is recorded here.
    var photo by remember { mutableStateOf<File?>(null) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        val file = photo
        photo = null
        if (saved && file != null && file.length() > 0) vm.attach(listOf(FilePickedFile(file, "image/jpeg"))) else file?.delete()
    }
    val recorder = remember { AudioClipRecorder(context.applicationContext) }
    val startClip = {
        if (recorder.start()) vm.setRecording(System.currentTimeMillis()) else vm.captureFailed("Couldn’t start recording")
    }
    val clipMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startClip() else vm.captureFailed("Microphone access is needed to record")
    }
    val shareLocation = {
        scope.launch {
            val fix = currentLocation(context)
            if (fix == null) vm.captureFailed("Couldn’t find your location. Is location switched on?")
            else vm.insertText(LocationText.of(fix.latitude, fix.longitude, if (fix.hasAccuracy()) fix.accuracy else null))
        }
    }
    val locationGrant = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants.values.any { it }) shareLocation() else vm.captureFailed("Location access is needed to share where you are")
    }
    // A clip is not recorded behind the user's back: leaving the screen drops it.
    val host = LocalActivity.current
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        if (host?.isChangingConfigurations != true) AudioPlayback.stop() // nothing plays on after the chat left the screen
        if (recorder.recording && host?.isChangingConfigurations != true) {
            recorder.cancel()
            vm.setRecording(null)
        }
    }
    DisposableEffect(recorder) {
        onDispose {
            // The recorder goes with this composition (a rotation too): the clip is dropped, and the bar with it.
            if (recorder.recording) {
                recorder.cancel()
                vm.setRecording(null)
            }
        }
    }
    LaunchedEffect(vm) { vm.closed.collect { onDeleted() } }
    LaunchedEffect(vm) { vm.branched.collect { onOpenSession(it) } }
    // The phone voice ends when the chat leaves the screen (app in the background, another destination), not on
    // rotation; a live session goes on in the background (its service holds the mic) until the chat itself is left.
    val activity = LocalActivity.current
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        if (activity?.isChangingConfigurations != true && vm.state.value.voice?.live == null) vm.closeVoice()
    }
    DisposableEffect(Unit) {
        onDispose { if (activity?.isChangingConfigurations != true) vm.closeVoice() }
    }
    // Back from a connection's browser leg: the backend reads the account now.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        vm.onScreenResumed()
        vm.takePendingShare() // a share from another app meant for this chat
    }
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
            playFile = vm::mediaFile,
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
            onToggleSteps = vm::toggleSteps,
            onCancelOffline = vm::cancelOffline,
            onProcesses = vm::openProcesses,
            onProcessesClose = vm::closeProcesses,
            onProcessStop = vm::stopProcess,
            onCamera = {
                try {
                    val file = Captures.newFile(context, "photo", "jpg")
                    photo = file
                    camera.launch(Captures.uri(context, file))
                } catch (_: Exception) { // no camera app, or the provider refused the path
                    photo = null
                    vm.captureFailed("No camera app to take a photo with")
                }
            },
            onRecordAudio = {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startClip()
                else clipMic.launch(Manifest.permission.RECORD_AUDIO)
            },
            onRecordStop = {
                val clip = recorder.stop()
                vm.setRecording(null)
                if (clip != null) vm.attach(listOf(FilePickedFile(clip, "audio/mp4"))) else vm.captureFailed("Nothing was recorded")
            },
            onRecordCancel = {
                recorder.cancel()
                vm.setRecording(null)
            },
            onLocation = {
                val granted = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                    .any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
                if (granted) shareLocation() else locationGrant.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            },
            onExport = {
                val chat = vm.state.value.chat
                val text = ChatExport.markdown(chat.title, chat.items, ZoneId.systemDefault())
                scope.launch {
                    try {
                        shareExport(context, ChatExport.fileName(chat.title), text)
                    } catch (c: kotlinx.coroutines.CancellationException) {
                        throw c
                    } catch (_: Exception) {
                        vm.exportFailed()
                    }
                }
            },
            onSelectText = vm::selectText,
            onToolOpened = vm::loadToolOutputs,
            onCopyMessage = { id, reasoning ->
                vm.copyMessage(id, reasoning) { text ->
                    scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Message", text))) }
                }
            },
            onEditMessage = vm::editMessage,
            onCancelEdit = vm::cancelEdit,
            onRegenerate = vm::regenerate,
            onSendAside = vm::sendAside,
            onSteer = vm::steer,
            onBranch = vm::branch,
            onSubagentOpen = vm::openSubagent,
            onSubagentClose = vm::closeSubagent,
            onSubagentStop = vm::stopSubagent,
            onSubagentSteer = vm::steerSubagent,
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
    val search = remember { ChatSearchState() }
    val overflow = overflowItems(state, actions, onSearch = { search.open = true })
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
            ChatColumn(state, showMenu, onDrawer, onStatus, actions, listState, overflow, composerFocus, search)
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
    search: ChatSearchState,
) {
    val colors = MaterialTheme.tsukaima
    val found = remember(state.chat.items, search.query, search.open) { if (search.open) ChatSearch.matches(state.chat.items, search.query) else emptyList() }
    Column(
        Modifier
            .fillMaxSize()
            .background(colors.surfacePage)
            .imePadding(),
    ) {
        if (search.open) {
            BackHandler { search.close() }
            ChatSearchBar(
                query = search.query,
                onQuery = { search.query = it; search.index = 0 },
                position = if (found.isEmpty()) 0 else search.index.coerceIn(0, found.lastIndex) + 1,
                total = found.size,
                onPrevious = { if (found.isNotEmpty()) search.index = (search.index.coerceIn(0, found.lastIndex) - 1 + found.size) % found.size },
                onNext = { if (found.isNotEmpty()) search.index = (search.index.coerceIn(0, found.lastIndex) + 1) % found.size },
                onClose = { search.close() },
            )
        } else {
            ChatTopBar(
                modelLabel = state.model ?: "Model",
                onMenu = onDrawer,
                showMenu = showMenu,
                onModel = { actions.onMenu(ChatMenu.Model) },
                onMore = { if (overflow.isNotEmpty()) actions.onMenu(ChatMenu.Overflow) },
                moreEnabled = overflow.isNotEmpty(),
            )
        }
        // The user's turn: the status line is the reasoning picker. Any other state is the plain line, and closes it.
        var pickingReasoning by rememberSaveable { mutableStateOf(false) }
        // Commands the agent left running: the chip takes the context meter's place on the line.
        val chip: (@Composable () -> Unit)? =
            if (state.runningProcesses > 0) ({ ProcessChip(state.runningProcesses, actions.onProcesses) }) else null
        if (state.offline) {
            // No Hermes: the line says so, and how old the transcript on screen is when it is the phone's copy.
            OfflineBar(savedAt = state.savedAt, onClick = onStatus)
        } else if (state.reasoningPickable) {
            ReasoningStatusLine(
                effort = state.chat.reasoningEffort,
                open = pickingReasoning,
                onToggle = { pickingReasoning = !pickingReasoning },
                onClick = onStatus,
                meter = state.chat.usage?.shortMeter,
                trailing = chip,
            )
        } else {
            StatusLine(state.status, onClick = onStatus, meter = state.chat.usage?.meter, trailing = chip)
        }
        ReasoningPanel(
            open = pickingReasoning && state.reasoningPickable,
            effort = state.chat.reasoningEffort,
            onPick = {
                pickingReasoning = false
                actions.onReasoning(it)
            },
        )
        if (!state.reasoningPickable && pickingReasoning) SideEffect { pickingReasoning = false }
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            when {
                // A saved copy is shown at once; the skeleton is for a chat with nothing to show yet.
                state.loading && state.chat.items.isEmpty() -> ChatSkeleton(Modifier.padding(top = 10.dp), caption = state.chat.resumeProgress)
                state.empty -> EmptyChat(state.workspace?.let { ChatSync.displayPath(it, state.workspaces?.home) }, { actions.onMenu(ChatMenu.Workspace) })
                else -> Transcript(state, actions, listState, searchQuery = search.query.takeIf { search.open }, searchAt = found.getOrNull(search.index.coerceIn(0, maxOf(found.lastIndex, 0))))
            }
        }
        if (state.archived) ArchivedBanner(onUnarchive = actions.onUnarchive)
        state.recordingSince?.let { RecordingBar(it, onStop = actions.onRecordStop, onCancel = actions.onRecordCancel) }
        CommandSuggestions(state.commandMatches, onPick = { actions.onInput(it.name + " ") })
        Composer(
            value = state.input,
            onValueChange = actions.onInput,
            onSend = actions.onSend,
            onAttach = { actions.onMenu(ChatMenu.Attach) },
            enabled = state.typeable,
            disabledPlaceholder = "This chat is archived",
            canSend = state.canSend,
            attachments = state.attachments,
            onRemoveAttachment = actions.onRemoveAttachment,
            onRetryAttachment = actions.onRetryAttachment,
            running = state.chat.status.running,
            turnLive = state.chat.queuesSends,
            onStop = actions.onStop,
            onMore = { actions.onMenu(ChatMenu.Send) },
            onVoice = actions.onVoice,
            focusRequester = composerFocus,
            editCaption = state.editCaption,
            onCancelEdit = actions.onCancelEdit,
        )
    }
}

@Composable
private fun Transcript(
    state: ChatUiState,
    actions: ChatActions,
    listState: LazyListState,
    /** The open search's query (null: no search) and the id of the row it stands on. */
    searchQuery: String? = null,
    searchAt: String? = null,
) {
    val items = state.chat.items
    val media = remember(state.mediaState, actions) {
        MediaHost(
            state.mediaState, actions.onOpenMedia, actions.previewMedia,
            actions.previewImage, actions.imageAllowed, actions.onAllowImage, actions.previewToolImage, actions.playFile,
        )
    }
    val zone = remember { ZoneId.systemDefault() }
    // A search reaches into folded steps: they are all shown while it is open.
    val rows = remember(items, state.chat.openSteps, zone, searchQuery != null) { TranscriptRows.build(items, state.chat.openSteps, zone, unfold = searchQuery != null) }
    // Follow the bottom while the user is there; a manual scroll up stops following until they come back.
    var follow by rememberSaveable { mutableStateOf(true) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress to listState.canScrollForward }
            .collect { (scrolling, canScrollForward) -> if (scrolling) follow = !canScrollForward }
    }
    val tail = transcriptTailKey(items)
    val count = rows.size + state.outbox.size
    LaunchedEffect(count, tail) {
        if (follow && count > 0) listState.scrollToItem(count) // the end spacer: bottom of the last item
    }
    // The search moved to another match: bring its row into view, and stop following the bottom.
    LaunchedEffect(searchAt, searchQuery) {
        val at = rows.indexOfFirst { it.key == searchAt }
        if (searchQuery != null && at >= 0) {
            follow = false
            listState.animateScrollToItem(at)
        }
    }
    // What was last when the reader left the bottom: anything after it is new to them (the Latest button's dot).
    var leftAt by remember { mutableStateOf(tail) }
    if (follow) leftAt = tail
    val scope = rememberCoroutineScope()
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 6.dp),
        ) {
            items(rows, key = { it.key }, contentType = { if (it is TranscriptRow.Item) it.item::class else it::class }) { row ->
                when (row) {
                    is TranscriptRow.Item ->
                        if (searchQuery == null) {
                            TranscriptItem(row.item, state, actions, media)
                        } else {
                            CompositionLocalProvider(LocalChatSearch provides SearchMark(searchQuery, current = row.item.id == searchAt)) {
                                TranscriptItem(row.item, state, actions, media)
                            }
                        }
                    is TranscriptRow.TimeMark -> TimeMark(row.at, zone)
                    is TranscriptRow.Steps -> StepsLine(row, onToggle = { actions.onToggleSteps(row.id) })
                }
            }
            // Written offline, not sent yet: under everything Hermes has.
            items(state.outbox, key = { it.id }, contentType = { "outbox" }) { message ->
                UserBubble(message.text, waiting = true, onCancel = { actions.onCancelOffline(message.id) })
            }
            item(key = "end") { Spacer(Modifier.height(10.dp)) }
        }
        LatestButton(
            visible = !follow && listState.canScrollForward,
            unseen = tail != leftAt,
            onClick = {
                follow = true
                scope.launch { listState.animateScrollToItem(count) }
            },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = 12.dp),
        )
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
            steer = item.steer,
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
                // A match that is only in the reasoning has to be readable: the search's row shows all of it.
                reasoningOpen = ChatReducer.reasoningOpen(state.chat, item) ||
                    LocalChatSearch.current?.let { it.current && ChatSearch.onlyInReasoning(item, it.query) } == true,
                onToggleReasoning = { actions.onToggleReasoning(item.id) },
                model = item.model,
                thoughtFrom = item.thoughtFrom,
                thoughtMs = item.thoughtMs,
            )
        }
        is ChatItem.ToolCall -> ToolRow(item, media = media, onOpened = actions.onToolOpened)
        is ChatItem.Subagent -> SubagentRow(item, onWatch = { actions.onSubagentOpen(item.id) }.takeIf { item.subagentId != null })
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
            answering = item.opId in state.answeringConnections,
        )
        is ChatItem.SideNote -> SideNoteCard(item, media = media)
        is ChatItem.Notice -> NoticeLine(item)
        is ChatItem.CommandNote -> CommandNoteCard(item)
        is ChatItem.Todos -> TodoCard(item)
        is ChatItem.Thinking -> Spacer(Modifier.height(0.dp))
    }
}

/**
 * What the transcript follows to the bottom on: the last item's id and, for a reply, its text length (so streaming
 * keeps it pinned). Only a reply's length and the todo card's revision count — a [ChatItem.SideNote] finishing keeps the key, so a detached side
 * answer landing does not scroll the chat.
 */
internal fun transcriptTailKey(items: List<ChatItem>): Pair<String, Int>? =
    items.lastOrNull()?.let {
        it.id to when (it) {
            is ChatItem.AssistantMessage -> it.text.length
            is ChatItem.Todos -> it.revision // an update replaces the card in place: the revision moves the key
            else -> 0
        }
    }

/**
 * The top bar's overflow entries: Stop while a turn runs, the side entries while text is typed between turns ([ChatSides]), then the
 * session actions once the session is stored.
 * A brand-new chat with no stored id yet offers only what works without one (Stop, and Copy session ID — the live
 * id — once a live session exists); empty disables the button rather than opening a sheet that does nothing.
 */
internal fun overflowItems(state: ChatUiState, actions: ChatActions, onSearch: (() -> Unit)? = null): List<MenuItem> {
    val running = state.chat.status.running
    val stop = MenuItem("Stop", IconName.Stop, onClick = actions.onStop).takeIf { running && !state.offline }
    val copy = MenuItem("Copy session ID", IconName.ContentCopy, onClick = actions.onCopyId)
    val live = state.chat.liveSessionId != null && state.writable
    val reasoning = MenuItem(reasoningMenuLabel(state.chat.reasoningEffort), IconName.Psychology) { actions.onMenu(ChatMenu.Reasoning) }
        .takeIf { live }
    // Compaction is idle-gated on the server: offered between turns only.
    val compact = MenuItem("Compact context", IconName.Compress, onClick = actions.onCompact)
        .takeIf { live && !running && !state.compacting && state.chat.items.isNotEmpty() }
    // During the agent's turn the composer's More button holds the ways to send ([sendItems]).
    val sides = if (ChatSides.available(state) && !state.chat.queuesSends) {
        listOf(
            MenuItem("Send as side question", IconName.Forum) { actions.onSendAside(SideKind.Question) },
            MenuItem("Send as background task", IconName.Schedule) { actions.onSendAside(SideKind.Background) },
        )
    } else {
        emptyList()
    }
    // Both work on what is on screen, so they need no connection.
    val hasText = state.chat.items.any { it is ChatItem.UserMessage || it is ChatItem.AssistantMessage }
    val find = onSearch?.takeIf { hasText }?.let { MenuItem("Search in chat", IconName.Search, onClick = it) }
    val export = MenuItem("Export chat", IconName.Share, onClick = actions.onExport).takeIf { hasText }
    if (state.chat.storedSessionId == null) {
        return listOfNotNull(stop) + sides + listOfNotNull(reasoning, compact, find, export, copy.takeIf { state.chat.liveSessionId != null })
    }
    return listOfNotNull(stop) + sides + listOfNotNull(
        reasoning,
        compact,
        find,
        export,
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

/**
 * The composer's More menu, shown during the agent's turn in place of Send: the ways the typed message can go.
 * Steer now: into the running turn, read after its current tool call. Queue: a turn of its own when this one ends.
 * Side question and background task: a detached agent answers beside the turn. Steer and the side entries need
 * text ([ChatSides]); Queue also takes attachments ([ChatUiState.canSend]).
 */
internal fun sendItems(state: ChatUiState, actions: ChatActions): List<MenuItem> = listOfNotNull(
    MenuItem("Steer now", IconName.Send, onClick = actions.onSteer).takeIf { ChatSides.steerable(state) },
    MenuItem("Queue", IconName.Schedule, onClick = actions.onSend).takeIf { state.canSend },
    MenuItem("Send as side question", IconName.Forum) { actions.onSendAside(SideKind.Question) }.takeIf { ChatSides.available(state) },
    MenuItem("Send as background task", IconName.Schedule) { actions.onSendAside(SideKind.Background) }.takeIf { ChatSides.available(state) },
)

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
 * server has persisted, Regenerate on a reply whose user message it has; Branch from here on a persisted message
 * ([ChatBranch.offered]). Edit and Regenerate are left out while a
 * turn runs or Hermes is unreachable ([ChatEdits.rewindable]).
 */
internal fun messageItems(state: ChatUiState, actions: ChatActions): List<MenuItem> {
    val item = state.chat.items.firstOrNull { it.id == state.messageMenu } ?: return emptyList()
    val reasoning = (item as? ChatItem.AssistantMessage)?.reasoning?.takeIf { it.isNotEmpty() }
    val rewind = ChatEdits.rewindable(state)
    // A steer sits inside a turn: rewinding to it would cut that turn in half.
    val edit = (item as? ChatItem.UserMessage)?.takeIf { rewind && it.rowId != null && !it.failed && !it.steer }
    val regenerate = (item as? ChatItem.AssistantMessage)?.takeIf { rewind && ChatEdits.regenerateSource(state.chat.items, it.id) != null }
    return listOfNotNull(
        MenuItem("Copy text", IconName.ContentCopy) { actions.onCopyMessage(item.id, false) },
        MenuItem("Select text", IconName.SelectAll) { actions.onSelectText(item.id) },
        reasoning?.let { MenuItem("Copy reasoning", IconName.Psychology) { actions.onCopyMessage(item.id, true) } },
        edit?.let { MenuItem("Edit", IconName.Edit) { actions.onEditMessage(it.id) } },
        regenerate?.let { MenuItem("Regenerate", IconName.Refresh) { actions.onRegenerate(it.id) } },
        MenuItem("Branch from here", IconName.AccountTree) { actions.onBranch(item.id) }
            .takeIf { state.writable && !state.branching && ChatBranch.offered(state.chat.items, item) },
    )
}

@Composable
private fun ChatSheets(state: ChatUiState, actions: ChatActions, overflow: List<MenuItem>) {
    val dismiss = { actions.onMenu(null) }
    PickerSheet(state.menu == ChatMenu.Model, dismiss, "Model", modelNodes(state, actions))
    MenuSheet(state.menu == ChatMenu.Workspace, dismiss, workspaceItems(state, actions))
    MenuSheet(state.menu == ChatMenu.Overflow && overflow.isNotEmpty(), dismiss, overflow)
    MenuSheet(state.menu == ChatMenu.Reasoning, dismiss, reasoningItems(state, actions))
    MenuSheet(state.menu == ChatMenu.Attach, dismiss, attachItems(state, actions))
    val sendMenu = sendItems(state, actions)
    MenuSheet(state.menu == ChatMenu.Send && sendMenu.isNotEmpty(), dismiss, sendMenu)
    MenuSheet(state.menu == ChatMenu.VoiceLanguage, dismiss, voiceLanguageItems(state.voiceLanguages, state.voiceLanguage, actions.onVoiceLanguage))
    val messageMenu = messageItems(state, actions)
    MenuSheet(messageMenu.isNotEmpty(), { actions.onMessageMenu(null) }, messageMenu)
    state.chat.items.firstOrNull { it.id == state.selecting }?.let { SelectTextView(it, onClose = { actions.onSelectText(null) }) }
    if (state.processSheet) ProcessSheet(state.processes, canAct = !state.offline, onDismiss = actions.onProcessesClose, onStop = actions.onProcessStop)
    state.subagent?.let { watch ->
        (state.chat.items.firstOrNull { it.id == watch.id } as? ChatItem.Subagent)?.let { item ->
            SubagentView(item, watch, canAct = !state.offline, onClose = actions.onSubagentClose, onStop = actions.onSubagentStop, onSteer = actions.onSubagentSteer)
        }
    }
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

/** The open chat's search: whether its bar is shown, what is typed, and which match it stands on. */
class ChatSearchState {
    var open by mutableStateOf(false)
    var query by mutableStateOf("")
    var index by mutableIntStateOf(0)

    fun close() {
        open = false
        query = ""
        index = 0
    }
}

/**
 * The composer's + menu: files from the phone, a photo taken now, an audio clip recorded now, and where the phone
 * is (as text in the composer). Recording is not offered while a clip is already being recorded.
 */
internal fun attachItems(state: ChatUiState, actions: ChatActions): List<MenuItem> = listOfNotNull(
    MenuItem("Photos and files", IconName.Image, onClick = actions.onAttach),
    MenuItem("Take a photo", IconName.PhotoCamera, onClick = actions.onCamera),
    MenuItem("Record audio", IconName.Mic, onClick = actions.onRecordAudio).takeIf { state.recordingSince == null },
    MenuItem("Share my location", IconName.LocationOn, onClick = actions.onLocation),
)
