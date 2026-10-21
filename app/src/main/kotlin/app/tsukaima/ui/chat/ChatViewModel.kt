package app.tsukaima.ui.chat

import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import app.tsukaima.data.gateway.ApprovalChoice
import app.tsukaima.data.gateway.ConnectionState
import app.tsukaima.data.gateway.GatewayClosedException
import app.tsukaima.data.gateway.GatewaySession
import app.tsukaima.data.gatewayStatus
import app.tsukaima.domain.chat.AgentStatus
import app.tsukaima.domain.chat.ChatItem
import app.tsukaima.domain.chat.ChatReducer
import app.tsukaima.domain.chat.ChatState
import app.tsukaima.domain.chat.ConnectionAnswer
import app.tsukaima.domain.chat.ConnectionAnswers
import app.tsukaima.domain.chat.SideKind
import app.tsukaima.domain.chat.ThinkingFlair
import app.tsukaima.domain.chat.queuesSends
import app.tsukaima.domain.voice.VoiceLanguages
import app.tsukaima.domain.voice.VoiceMode

/**
 * One chat: a new one when [storedSessionId] is null, else the stored session to resume.
 *
 * Everything runs on [dispatcher] (single-threaded: Main in the app), so the mutable fields below need no lock.
 * Live events are applied only once a live session is bound, and only for that live id. On a reconnect the
 * session is resumed (re-binding the reducer), its missed events replayed from `session.events.since`, and
 * open requests re-adopted; live events arriving meanwhile are held and applied after the replay.
 */
class ChatViewModel(
    private val backend: ChatBackend,
    private val storedSessionId: String?,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val flair: () -> String = ThinkingFlair::next,
) : ViewModel() {
    private val session = GatewaySession(backend.transport) { backend.settings.value?.profile }
    private val _state = MutableStateFlow(ChatUiState(loading = storedSessionId != null, isNew = storedSessionId == null))
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    private val _closed = Channel<Unit>(Channel.CONFLATED)

    /** Emits once the session was archived or deleted from this screen. */
    val closed: Flow<Unit> = _closed.receiveAsFlow()

    private var opened = false
    private var opening = false
    private var lost = false

    /** One automatic open() retry per connection-state transition; reset whenever the state changes. */
    private var openRetried = false
    private var userCounter = 0L

    /** What a failed send carried, by its bubble's local id, for Retry / Dismiss. */
    private data class FailedSend(
        val text: String,
        val attachments: List<PendingAttachment>,
        val voice: Boolean = false,
        val interrupted: Boolean = false,
    )

    private val failedSends = mutableMapOf<String, FailedSend>()

    /**
     * What each send the server queued carried, by bubble id, while it waits: a turn end that purges the queue turns
     * its bubble failed ([ChatReducer]), and Retry / Dismiss then read it from here ([failedSend]).
     */
    private val queuedSends = mutableMapOf<String, FailedSend>()
    private val sync = ChatReconnector(
        session, backend, CoroutineScope(viewModelScope.coroutineContext + dispatcher), storedSessionId, flair, _state,
        onRebuilt = { before, liveChanged ->
            restorePurgedSends(before, rowsKept = false) // a resume retires never-drained queue rows
            restoreFailedSends()
            voice.rebased(before)
            if (liveChanged) edits.onLiveChanged()
        },
    )

    private val attachments = ChatAttachments(
        session, backend, CoroutineScope(viewModelScope.coroutineContext + dispatcher), io, _state, ::liveSession,
    )

    private val edits = ChatEdits(
        session, backend, CoroutineScope(viewModelScope.coroutineContext + dispatcher), _state, attachments, flair,
        forgetFailed = { ids -> ids.forEach(failedSends::remove) },
        reload = ::reloadHistory,
    ) { "u-l${++userCounter}" }

    private val sides = ChatSides(session, backend, CoroutineScope(viewModelScope.coroutineContext + dispatcher), _state)

    private val media = ChatMedia(backend, CoroutineScope(viewModelScope.coroutineContext + dispatcher), io, _state)

    /** The chat holds its history: a new chat at once, a stored one once `session.resume` has bound it. */
    private val history = MutableStateFlow(if (storedSessionId == null) ChatHistory.Loaded else ChatHistory.Loading)

    private val voice = ChatVoice(
        backend,
        CoroutineScope(viewModelScope.coroutineContext + dispatcher),
        _state,
        { text, interrupted -> sendText(text, voice = true, interrupted = interrupted) },
        history,
    )

    private val chat get() = _state.value.chat
    private fun setChat(block: (ChatState) -> ChatState) = _state.update { it.copy(chat = block(it.chat)) }
    private fun launch(block: suspend () -> Unit) = viewModelScope.launch(dispatcher) { block() }

    init {
        launch { backend.hermes.collect { h -> _state.update { it.copy(gatewayStatus = h.gatewayStatus()) } } }
        launch { backend.events.collect(sync::onEvent) }
        launch { backend.serverRequests.collect(sync::onRequest) }
        launch { backend.connection.collect(::onConnection) }
        launch {
            backend.settings.filterNotNull().map { it.voiceLanguage }.distinctUntilChanged().collect { tag ->
                _state.update { it.copy(voiceLanguage = tag) }
            }
        }
        launch {
            // Lets notifications open the stored session that asked, knowing only its live id.
            _state.map { it.chat.liveSessionId to it.chat.storedSessionId }.distinctUntilChanged().collect { (live, stored) ->
                if (live != null) {
                    backend.bindSession(live, stored)
                    attachments.onLiveSession(live)
                }
            }
        }
        open()
    }

    // ---- lifecycle --------------------------------------------------------------------------------

    /** Creates or resumes the session (idempotent; retried on the next `Connected` if it failed). */
    fun open() {
        if (opened || opening) return
        opening = true
        launch {
            var notConnected = false
            try {
                val settings = backend.settings.filterNotNull().first()
                _state.update { it.copy(workspace = it.workspace ?: settings.workspace) }
                if (storedSessionId == null) {
                    create(settings.model, settings.modelProvider)
                } else {
                    _state.update { it.copy(loading = true) }
                    val r = session.resume(storedSessionId)
                    sync.bindResumed(r, rebuild = true)
                    sync.adoptOpen(r.openRequests, r.pendingApproval)
                    sync.adoptConnection(r.pendingConnection)
                    sync.syncWatermark(r.sessionId)
                }
                opened = true
                history.value = ChatHistory.Loaded
                sync.adoptRecent()
                if (storedSessionId != null) checkArchived(storedSessionId)
            } catch (c: CancellationException) {
                throw c
            } catch (_: GatewayClosedException) {
                notConnected = true // not connected yet, or dropped: retried on Connected
            } catch (e: Exception) {
                val message = "Couldn’t open the chat: ${e.message ?: "gateway error"}"
                backend.toast(message)
                // Voice stops waiting for the history and shows why (a later Connected may still retry the open).
                if (history.value == ChatHistory.Loading) history.value = ChatHistory.Failed(message)
            } finally {
                opening = false
                _state.update { it.copy(loading = false) }
            }
            // A Connected that arrived while this attempt was in flight found `opening` set and did nothing.
            if (notConnected && backend.connection.value == ConnectionState.Connected && !openRetried) {
                openRetried = true
                open()
            }
        }
        if (_state.value.models == null) {
            launch { backend.modelOptions().onSuccess { m -> _state.update { it.copy(models = m, model = it.model ?: m.model, provider = it.provider ?: m.provider) } } }
        }
        if (storedSessionId == null && _state.value.workspaces == null) {
            launch { backend.workspaces().onSuccess { w -> _state.update { it.copy(workspaces = w, workspace = it.workspace ?: w.defaultCwd) } } }
        }
    }

    private suspend fun create(model: String?, provider: String?) {
        val created = session.create(cwd = _state.value.workspace, model = model, provider = provider)
        val infoModel = (created.info["model"] as? JsonPrimitive)?.contentOrNull
        _state.update {
            it.copy(
                chat = ChatReducer.onSessionInfo(ChatReducer.onSessionBound(it.chat, created.sessionId, created.storedSessionId), created.info),
                model = infoModel ?: model ?: it.model,
                provider = provider ?: it.provider,
            )
        }
    }

    private fun onConnection(s: ConnectionState) {
        openRetried = false
        if (s == ConnectionState.Connected) {
            when {
                lost -> {
                    lost = false
                    sync.reconnect()
                }
                !opened -> open()
            }
        } else if (opened && !lost && s != ConnectionState.Connecting) {
            lost = true
            sync.onLost()
        }
    }

    // ---- composer ---------------------------------------------------------------------------------

    fun setInput(text: String) = _state.update { it.copy(input = text) }

    /**
     * Sends the typed text with the ready attachments ([Attachments.promptText]); see [ChatUiState.canSend]. While
     * [ChatUiState.pendingEdit] is set the text replaces that message instead ([ChatEdits]).
     */
    fun send() {
        if (!_state.value.canSend) return
        val text = _state.value.input.trim()
        val live = chat.liveSessionId ?: return backend.toast("Still connecting to Hermes")
        _state.value.pendingEdit?.let { row -> return edits.send(live, text, row) }
        startTurn(live, text, attachments.takeReady(), fromComposer = true)
    }

    /** Overflow "Send as side question" / "Send as background task": the composer's text, without attachments; see [ChatSides]. */
    fun sendAside(kind: SideKind) = sides.send(kind)

    /** "Edit" on user message [id]: its text goes into the composer and the next [send] replaces it; see [ChatEdits]. */
    fun editMessage(id: String) = edits.edit(id)

    /** A tap on the composer's "Editing" caption: leaves edit mode and clears the text. */
    fun cancelEdit() = edits.cancel()

    /** "Regenerate" on reply [assistantId]: its user message is sent again in its place; see [ChatEdits]. */
    fun regenerate(assistantId: String) = edits.regenerate(assistantId)

    /**
     * Sends [text] (trimmed) without attachments, leaving the composer alone (voice mode). False, with a toast,
     * when Hermes is unreachable, the chat archived, the context is being compacted, or no live session is open
     * yet. The status turns Thinking before this returns. [voice] marks a spoken turn and [interrupted] a barge-in on
     * the previous spoken reply; both go to Hermes (`voice_turn`, `interrupted`).
     */
    fun sendText(text: String, voice: Boolean = false, interrupted: Boolean = false): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false
        if (!_state.value.writable) return false.also { backend.toast("Not connected to Hermes") }
        if (_state.value.compacting) return false.also { backend.toast("Compacting the context, try again in a moment") }
        val live = chat.liveSessionId ?: return false.also { backend.toast("Still connecting to Hermes") }
        startTurn(live, trimmed, emptyList(), fromComposer = false, voice = voice, interrupted = interrupted)
        return true
    }

    /**
     * Adds the user bubble (status Thinking) and submits it; [fromComposer] also clears the typed text. While a turn
     * runs a typed send is queued behind it (never a redirect that would end it); a voice turn is not.
     */
    private fun startTurn(
        live: String, text: String, sent: List<PendingAttachment>, fromComposer: Boolean,
        voice: Boolean = false, interrupted: Boolean = false,
    ) {
        val localId = "u-l${++userCounter}"
        pruneQueuedSends()
        val queued = mustQueue(voice)
        _state.update {
            val chat = ChatReducer.onSubmitted(
                it.chat, text, localId, flair, sent.map(PendingAttachment::name),
                promptText = Attachments.promptText(text, sent), imagesSent = sent.any { a -> a.queued }, queued = queued,
            )
            if (fromComposer) it.copy(input = "", chat = chat) else it.copy(chat = chat)
        }
        launch { submit(live, localId, text, sent, voice, interrupted, queued) }
    }

    /**
     * Submits user message [localId]. A failure keeps the bubble, marked failed with Retry and Dismiss; its text
     * and attachments wait in [failedSends] (not back in the composer, which would show them twice).
     */
    private suspend fun submit(
        live: String, localId: String, text: String, sent: List<PendingAttachment>,
        voice: Boolean = false, interrupted: Boolean = false, queued: Boolean = false,
    ) {
        try {
            val accepted = session.submit(live, Attachments.promptText(text, sent), voice, interrupted, queued = queued)
            attachments.sent(sent)
            if (queued && accepted.status == QUEUED) queuedSends[localId] = FailedSend(text, sent, voice, interrupted)
            setChat { ChatReducer.onSubmitAccepted(it, localId, accepted.userRowId, status = accepted.status) }
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            failedSends[localId] = FailedSend(text, sent, voice, interrupted)
            // A queued send that failed leaves the live turn running.
            setChat { c -> c.copy(items = c.items.markFailed(localId, true), status = if (queued) c.status else unlessOffline(c.status, AgentStatus.Idle)) }
            backend.toast("Message not sent: ${e.message ?: "gateway error"}")
        }
    }

    /**
     * Retry on a failed bubble: submits the same text and attachments again. Images queued in a live session that
     * has since been replaced are gone from the server, so such a send is put back in the composer instead (the
     * images show as failed there, ready for their own retry).
     */
    fun retrySend(localId: String) {
        val failed = failedSend(localId) ?: return
        if (chat.items.none { it.id == localId }) {
            failedSends.remove(localId) // the transcript was rebuilt from history meanwhile
            queuedSends.remove(localId)
            return
        }
        if (!_state.value.writable) return backend.toast("Not connected to Hermes")
        val live = chat.liveSessionId ?: return backend.toast("Still connecting to Hermes")
        // A purged queued send's images went with it on the server: they cannot be sent again as they were.
        val imagesGone = localId in queuedSends && failed.attachments.any { it.queued }
        if (imagesGone || failed.attachments.any { it.queued && it.liveSessionId != live }) {
            dismissFailed(localId)
            return backend.toast("Attach the images again, then send")
        }
        failedSends.remove(localId)
        queuedSends.remove(localId)
        val queued = mustQueue(failed.voice)
        setChat { c ->
            val items = c.items.markFailed(localId, false, queued)
            c.copy(items = items, status = if (queued) c.status else AgentStatus.Thinking(flair()))
        }
        launch { submit(live, localId, failed.text, failed.attachments, failed.voice, failed.interrupted, queued) }
    }

    /** The reasoning header of message [id] was tapped. */
    fun toggleReasoning(id: String) = setChat { ChatReducer.toggleReasoning(it, id) }

    /** Replaces the transcript with live session [live]'s stored history (failed sends kept); a failure toasts. */
    private suspend fun reloadHistory(live: String) {
        attempt("Couldn’t reload the chat") {
            val messages = session.history(live)
            val before = chat.items
            if (chat.liveSessionId != live) return@attempt
            setChat { it.copy(items = ChatReducer.fromHistory(messages)) }
            restorePurgedSends(before, rowsKept = true)
            restoreFailedSends()
        }
    }

    /**
     * A rebuilt transcript keeps the sends Stop purged as Not sent ([ChatReducer.withPurged]): on their own history
     * row when the history read keeps it ([rowsKept]: a same-live reload; a resume retires it), else at the end. Retry /
     * Dismiss data follows an echo to the row's id. [before] is the transcript the rebuild replaced.
     */
    private fun restorePurgedSends(before: List<ChatItem>, rowsKept: Boolean) {
        if (before.none { it is ChatItem.UserMessage && it.failed && it.id in queuedSends }) return
        val (items, ids) = ChatReducer.withPurged(chat.items, before, queuedSends.keys, rowsKept)
        for ((old, new) in ids) queuedSends.remove(old)?.let { queuedSends[new] = it }
        setChat { it.copy(items = items) }
    }

    /** A rebuilt transcript holds only what the server stored: the failed bubbles (never sent) are put back at the end. */
    private fun restoreFailedSends() {
        if (failedSends.isEmpty()) return
        setChat { c ->
            val missing = failedSends.filterKeys { id -> c.items.none { it.id == id } }
            if (missing.isEmpty()) c
            else c.copy(items = c.items + missing.map { (id, f) -> ChatItem.UserMessage(id, f.text, f.attachments.map { it.name }, failed = true) })
        }
    }

    /**
     * Dismiss on a failed bubble: removes it; its text (if the composer is empty) and attachments go back to the
     * composer — except a purged queued send's images and PDFs, which the server consumed with it.
     */
    fun dismissFailed(localId: String) {
        val purged = localId in queuedSends
        val failed = failedSend(localId).also { failedSends.remove(localId); queuedSends.remove(localId) }
        setChat { c -> c.copy(items = c.items.filterNot { it.id == localId && it is ChatItem.UserMessage && it.failed }) }
        if (failed == null) return
        _state.update { if (it.input.isEmpty()) it.copy(input = failed.text) else it }
        attachments.restore(if (purged) failed.attachments.filterNot { it.queued } else failed.attachments)
        chat.liveSessionId?.let(attachments::onLiveSession)
    }

    /**
     * A typed send goes behind the running turn (`queued: true`), and behind earlier queued sends still waiting (the
     * server may be between turns, about to run them): [queuesSends], which the composer shows too. A voice turn never
     * queues.
     */
    private fun mustQueue(voice: Boolean) = !voice && chat.queuesSends

    /** A failed bubble's send: one that never reached Hermes, or a queued one whose turn end purged it. */
    private fun failedSend(localId: String): FailedSend? =
        failedSends[localId] ?: queuedSends[localId]?.takeIf {
            chat.items.any { item -> item.id == localId && item is ChatItem.UserMessage && item.failed }
        }

    /** Forgets queued sends that ran (their bubble is neither waiting nor failed) or left the transcript. */
    private fun pruneQueuedSends() {
        val items = chat.items.associateBy { it.id }
        queuedSends.keys.retainAll { id -> (items[id] as? ChatItem.UserMessage)?.let { it.queued || it.failed } == true }
    }

    /** A failed echo is never [ChatItem.UserMessage.queued]; a retry is queued again while a turn runs. */
    private fun List<ChatItem>.markFailed(localId: String, failed: Boolean, queued: Boolean = false) =
        map { if (it.id == localId && it is ChatItem.UserMessage) it.copy(failed = failed, queued = queued) else it }

    /** Uploads picked files at once (creating the session first if needed); see [ChatAttachments]. */
    fun attach(files: List<PickedFile>) = attachments.attach(files)

    fun removeAttachment(localId: String) = attachments.remove(localId)

    fun retryAttachment(localId: String) = attachments.retry(localId)

    /** A tap on a `MEDIA:` card: download into Downloads and open it; see [ChatMedia]. */
    fun openMedia(path: String) = media.open(path)

    /** The inline preview of image [path] (cached), or null. */
    suspend fun preview(path: String): ImageBitmap? = withContext(dispatcher) { media.preview(path) }

    /** A reply's `![alt](url)` image, decoded (null when it failed; cached either way). */
    suspend fun imagePreview(url: String): ImageBitmap? = withContext(dispatcher) { media.imagePreview(url) }

    /** Whether a reply's image at [url] loads by itself (server origin, or tapped to load); see [ChatMedia.imageAllowed]. */
    fun imageAllowed(url: String): Boolean = media.imageAllowed(url)

    /** The image a `vision_analyze` row looked at, decoded, or why there is none; see [ChatMedia.toolImage]. */
    suspend fun toolImage(tool: ChatItem.ToolCall): ImageResult = withContext(dispatcher) { media.toolImage(tool) }

    /** A tap on a foreign image's "tap to load" card. */
    fun allowImage(url: String) = media.allowImage(url)

    /** The live session id; opens (creates or resumes) the session first and waits for it when there is none. */
    private suspend fun liveSession(): String? {
        chat.liveSessionId?.let { return it }
        open()
        return withTimeoutOrNull(OPEN_WAIT_MS) { _state.map { it.chat.liveSessionId }.filterNotNull().first() }
    }

    /**
     * Stop: interrupts the running turn (`session.interrupt`). The turn then ends with the server's own
     * `message.complete` / `error`, which returns the status line (and the send button) to idle. Stop also purges the
     * server's queue, so that turn end fails the queued sends ([ChatReducer.onStopRequested]). The mark is set before
     * the call (the turn end may beat its answer); the answer only ever takes it back ([ChatReducer.onStopAck]).
     */
    fun interrupt() {
        _state.update { it.copy(menu = null) }
        val live = chat.liveSessionId ?: return
        setChat { ChatReducer.onStopRequested(it, true) }
        launch {
            var stopped = false
            attempt("Couldn’t stop the agent") {
                if (session.interrupt(live) == NOT_INTERRUPTED) backend.toast("Nothing to stop") else stopped = true
            }
            setChat { ChatReducer.onStopAck(it, stopped, live) }
        }
    }

    fun approve(id: String) = answerApproval(id, ApprovalChoice.once)
    fun deny(id: String) = answerApproval(id, ApprovalChoice.deny)

    /**
     * Answers approval [id] with [choice] (`once`, `session`, `always` or `deny`) through the exact server request
     * (`srq-…` id). A resume's `pending_approval` has no such id and falls back to `approval.respond`, but only with
     * its `request_id`: without one the server would resolve whatever approval is pending, so such a card is inert
     * ([ChatSync.answerable]). A scope the card does not offer is ignored.
     */
    fun answerApproval(id: String, choice: ApprovalChoice) {
        val card = chat.items.firstOrNull { it.id == id } as? ChatItem.Approval ?: return
        if (card.answered || !ChatSync.answerable(card)) return
        if (choice == ApprovalChoice.session && !card.allowSession || choice == ApprovalChoice.always && !card.allowAlways) return
        if (!backend.answeredRequestIds.add(id)) {
            return setChat { ChatReducer.onApprovalAnswered(it, id, flair) } // answered elsewhere (a notification)
        }
        _state.update {
            it.copy(chat = ChatReducer.onApprovalAnswered(it.chat, id, flair), decisions = it.decisions + (id to choice))
        }
        if (!id.startsWith(ChatSync.PENDING_PREFIX)) {
            if (!session.respondApproval(id, choice)) {
                // Not enqueued on a live socket: the server still waits, so let the user try again.
                backend.toast("Couldn’t send the answer: not connected to Hermes")
                reopenAnswer(id)
            }
            return
        }
        val live = chat.liveSessionId ?: return
        launch {
            if (!attempt("Couldn’t send the answer") { session.approvalRespond(live, card.requestId, choice) }) {
                reopenAnswer(id) // the RPC failed, so the server still waits for an answer
            }
        }
    }

    /** Sends the answers to clarify [id] (qid → answer, null = skipped); see [ChatItem.Clarify]. */
    fun answerClarify(id: String, answers: Map<String, String?>) = answerQuestion(id, answers)

    /** "Skip": cancels every question of clarify [id] (a reply without `answers`). */
    fun skipClarify(id: String) = answerQuestion(id, null)

    /** Claims [id] (shared with notifications), marks the card answered, then answers through its srq id. */
    private fun answerQuestion(id: String, answers: Map<String, String?>?) {
        val card = chat.items.firstOrNull { it.id == id } as? ChatItem.Clarify ?: return
        if (card.answered) return
        val shown = answers.orEmpty()
        if (!backend.answeredRequestIds.add(id)) {
            return setChat { ChatReducer.onClarifyAnswered(it, id, shown, flair) } // answered elsewhere
        }
        setChat { ChatReducer.onClarifyAnswered(it, id, shown, flair) }
        if (!session.respondClarify(id, answers)) {
            backend.toast("Couldn’t send the answer: not connected to Hermes")
            reopenAnswer(id)
        }
    }

    /**
     * Sends [value] for masked prompt [id] (`sudo`, `secret`, `vault.*`; a save-login's value is
     * [app.tsukaima.domain.chat.VaultAnswers.saveLogin]); `""` declines. The value
     * goes to the socket only: the card records just whether it was declined, and nothing logs or toasts it.
     */
    fun answerSecret(id: String, value: String) {
        val card = chat.items.firstOrNull { it.id == id } as? ChatItem.SecretPrompt ?: return
        if (card.answered) return
        val declined = value.isEmpty()
        if (!backend.answeredRequestIds.add(id)) {
            return setChat { ChatReducer.onSecretAnswered(it, id, declined, flair) } // answered elsewhere
        }
        setChat { ChatReducer.onSecretAnswered(it, id, declined, flair) }
        if (!session.respondValue(id, value)) {
            backend.toast("Couldn’t send the answer: not connected to Hermes")
            reopenAnswer(id)
        }
    }

    /** Cancel on a masked prompt: answers `""` (skipped / declined). */
    fun declineSecret(id: String) = answerSecret(id, "")

    // ---- connection operations --------------------------------------------------------------------

    /** Operations whose browser leg was opened from this screen: the next [onScreenResumed] wakes them. */
    private val awaitingBrowser = mutableSetOf<String>()

    /**
     * Sends [answer] for open connection operation [opId] (`connection.respond`). The card moves only with the
     * server's `connection.update` frames, except that a `settled: true` reply settles it at once. Env values in
     * the answer go to the socket only: never into the state, a log or a toast. A settled card answers nothing.
     */
    fun answerConnection(opId: String, answer: ConnectionAnswer) {
        val card = connectionCard(opId) ?: return
        val owner = connectionOwner(card) ?: return
        launch {
            session.respondConnection(opId, owner, answer)
                .onSuccess { settled -> if (settled) setChat { ChatReducer.onConnectionSettled(it, opId, flair) } }
                .onFailure { backend.toast("Couldn’t send the answer: ${it.message ?: "gateway error"}") }
        }
    }

    /** "Skip all": every target of [opId] `skipped`. */
    fun skipConnection(opId: String) {
        val card = connectionCard(opId) ?: return
        answerConnection(opId, ConnectionAnswers.skipAll(card.targets))
    }

    /** "Continue": ends operation [opId] now with whatever is unresolved. */
    fun continueConnection(opId: String) = answerConnection(opId, ConnectionAnswers.continueNow())

    /**
     * "Connect" on a row with a link: opens target [name]'s `connect_url` (http(s) only: it is agent-reachable data)
     * in the browser. The backend notices the account on its own; coming back to the chat ([onScreenResumed]) also
     * wakes it (`connectors.operation.wake`) so it reads the account at once.
     */
    fun openConnectUrl(opId: String, name: String) {
        val card = connectionCard(opId) ?: return
        val url = card.targets.firstOrNull { it.name == name }?.connectUrl ?: return
        val scheme = url.substringBefore(':', "").lowercase()
        if (scheme != "https" && scheme != "http") return backend.toast("Can’t open this link")
        if (!backend.openUrl(url)) return backend.toast("No browser to open the link")
        awaitingBrowser += opId
    }

    /** The chat screen is in front again (ON_RESUME): wakes every open operation whose browser leg was opened. */
    fun onScreenResumed() {
        if (awaitingBrowser.isEmpty()) return
        val ops = awaitingBrowser.toList()
        awaitingBrowser.clear()
        for (opId in ops) {
            val card = connectionCard(opId) ?: continue
            val owner = connectionOwner(card) ?: continue
            // Best effort: the wake only shortens the wait, and the operation may have settled meanwhile.
            launch { session.wakeConnection(owner, opId) }
        }
    }

    /** The open (unsettled) card of [opId], or null. */
    private fun connectionCard(opId: String): ChatItem.ConnectionRequest? =
        chat.items.firstOrNull { it is ChatItem.ConnectionRequest && it.opId == opId && !it.settled } as? ChatItem.ConnectionRequest

    /** The owner the server named, else this live session (the request arrived on it). */
    private fun connectionOwner(card: ChatItem.ConnectionRequest): JsonObject? =
        card.owner ?: chat.liveSessionId?.let(ConnectionAnswers::sessionOwner)

    /** Undoes an answer that was not delivered: the card is answerable again and the claim is released. */
    private fun reopenAnswer(id: String) {
        backend.answeredRequestIds.remove(id)
        _state.update { s ->
            var waiting: AgentStatus = AgentStatus.Approval
            val items = s.chat.items.map {
                when {
                    it.id != id -> it
                    it is ChatItem.Approval -> it.copy(answered = false)
                    it is ChatItem.Clarify -> it.copy(answered = false, answers = emptyMap()).also { waiting = AgentStatus.Question }
                    it is ChatItem.SecretPrompt -> it.copy(answered = false, declined = false)
                        .also { waiting = AgentStatus.Secret(it.kind) }
                    else -> it
                }
            }
            s.copy(chat = s.chat.copy(items = items, status = unlessOffline(s.chat.status, waiting)), decisions = s.decisions - id)
        }
    }

    // ---- voice ------------------------------------------------------------------------------------

    /**
     * Opens voice mode on this chat (no-op while open); see [ChatVoice]. Needs `RECORD_AUDIO`, asked by the screen.
     * A resumed chat starts voice only once its history is in, so nothing said before is spoken; [listen] false
     * waits for a tap on the indicator instead of listening at once.
     */
    fun openVoice(mode: VoiceMode = VoiceMode.InApp, onApprovalNeeded: () -> Unit = {}, listen: Boolean = true) =
        voice.open(mode, onApprovalNeeded, listen)

    /** Leaves voice mode: listening and speech stop, the running turn goes on. */
    fun closeVoice() = voice.close()

    fun setVoiceMuted(muted: Boolean) = voice.setMuted(muted)

    /** A tap on the voice indicator: barge-in while speaking, listen again when idle. */
    fun tapVoiceIndicator() = voice.tapIndicator()

    /**
     * Saves the voice language ([tag] null = system default) and closes its sheet. The recogniser and TTS read the
     * setting lazily, so the next run and the next sentence use it; once the saved value shows in the settings, a
     * listening run in progress starts over in it. If it does not show within [SETTINGS_SETTLE_MS] (the write
     * failed), the sheet's check goes back to the stored value and a toast says so.
     */
    fun setVoiceLanguage(tag: String?) {
        _state.update { it.copy(menu = null, voiceLanguage = tag) }
        launch {
            backend.updateSettings { it.copy(voiceLanguage = tag) }
            val saved = withTimeoutOrNull(SETTINGS_SETTLE_MS) { backend.settings.first { it?.voiceLanguage == tag } }
            if (saved == null) {
                _state.update { it.copy(voiceLanguage = backend.settings.value?.voiceLanguage) }
                backend.toast("Couldn’t save the voice language")
                return@launch
            }
            voice.restartListening()
            val options = _state.value.voiceLanguages.ifEmpty { withContext(io) { backend.voiceLanguages() } }
            backend.toast("Voice language: ${VoiceLanguages.label(tag, options)}")
        }
    }

    /** The microphone permission was refused. */
    fun voiceDenied() = backend.toast("Microphone access is needed for voice mode")

    override fun onCleared() {
        closeVoice()
        super.onCleared()
    }

    // ---- menus and session actions ------------------------------------------------------------------

    fun showMenu(menu: ChatMenu?) {
        _state.update { it.copy(menu = menu) }
        if (menu == ChatMenu.Model && _state.value.models == null) {
            launch { backend.modelOptions().onSuccess { m -> _state.update { it.copy(models = m) } } }
        }
        if (menu == ChatMenu.VoiceLanguage) { // reloaded each time: the TTS engine's list may have come in
            launch {
                val options = withContext(io) { backend.voiceLanguages() }
                _state.update { it.copy(voiceLanguages = options) }
            }
        }
        if (menu == ChatMenu.Workspace && _state.value.workspaces == null) {
            launch { backend.workspaces().onSuccess { w -> _state.update { it.copy(workspaces = w) } } }
        }
    }

    /** Opens [dialog], or with null closes it (a dismissed model confirmation drops its pick). */
    fun showDialog(dialog: ChatDialog?) =
        _state.update { it.copy(dialog = dialog, menu = null, pendingModel = it.pendingModel.takeIf { dialog != null }) }

    /**
     * Switches the live session to [model] of [provider] (`config.set key=model`, session-scoped) and saves the pair
     * for new chats once the server took it. Without a live session the pair is only saved, for the next chat. A
     * model the server's guards flag needs a confirmation first ([ChatDialog.ModelConfirm]); a switch asked for
     * mid-turn applies when the next turn starts; any advice the server returns is toasted.
     */
    fun setModel(provider: String, model: String) {
        _state.update { it.copy(menu = null) }
        val live = chat.liveSessionId
        if (live == null) {
            launch {
                backend.updateSettings { it.copy(model = model, modelProvider = provider) }
                backend.toast("Model applies to the next chat")
            }
            return
        }
        launch { switchModel(live, provider, model, confirmed = false) }
    }

    /** Confirm on [ChatDialog.ModelConfirm]: sends the pick again with `confirm_expensive_model`. */
    fun confirmModel() {
        val pick = _state.value.pendingModel
        _state.update { it.copy(dialog = null, pendingModel = null) }
        val live = chat.liveSessionId ?: return
        if (pick != null) launch { switchModel(live, pick.provider, pick.model, confirmed = true) }
    }

    private suspend fun switchModel(live: String, provider: String, model: String, confirmed: Boolean) {
        attempt("Couldn’t switch the model") {
            val r = session.switchModel(live, model, provider, confirmExpensive = confirmed)
            if (r.confirmRequired) {
                val why = r.confirmMessage.ifBlank { r.warning }.trim().ifEmpty { "Switch to $model?" }
                _state.update { it.copy(dialog = ChatDialog.ModelConfirm, pendingModel = PendingModel(provider, model, why)) }
                return@attempt
            }
            backend.updateSettings { it.copy(model = model, modelProvider = provider) }
            _state.update { it.copy(model = r.value.ifBlank { model }, provider = provider) }
            when {
                r.deferred -> backend.toast("Model switches when this turn ends")
                r.warning.isNotBlank() -> backend.toast(ChatSync.toastLine(r.warning))
            }
        }
    }

    /**
     * Sets the live session's reasoning effort ([REASONING_LEVELS]); session-scoped on the server, nothing saved
     * here. The server's `session.info` confirms it; the reported value is applied at once meanwhile.
     */
    fun setReasoning(level: String) {
        _state.update { it.copy(menu = null) }
        val live = chat.liveSessionId ?: return backend.toast("Still connecting to Hermes")
        launch {
            attempt("Couldn’t set the reasoning effort") {
                val value = session.setReasoning(live, level) ?: level
                setChat { it.copy(reasoningEffort = value) }
            }
        }
    }

    /**
     * Compact context (`session.compress`): summarizes the conversation so far to free the context window. The
     * status line says "Compacting context…" meanwhile; the outcome is toasted ([ChatSync.compacted]). Compaction
     * can rotate the session onto a new stored id, which the chat then follows.
     */
    fun compact() {
        _state.update { it.copy(menu = null) }
        val live = chat.liveSessionId ?: return
        if (_state.value.compacting) return
        _state.update { it.copy(compacting = true) }
        launch {
            try {
                attempt("Couldn’t compact the context") {
                    val done = ChatSync.compacted(session.compress(live))
                    setChat { c ->
                        val info = done.info?.let { ChatReducer.onSessionInfo(c, it) } ?: c
                        val fresh = ChatReducer.withUsage(info, done.usage) // the result's own usage is the post-compaction one
                        if (done.storedId != null && c.liveSessionId == live) fresh.copy(storedSessionId = done.storedId) else fresh
                    }
                    backend.toast(done.message)
                }
            } finally {
                _state.update { it.copy(compacting = false) }
            }
        }
    }

    fun setWorkspace(path: String) {
        _state.update { it.copy(workspace = path, menu = null) }
        launch {
            backend.updateSettings { it.copy(workspace = path) }
            chat.liveSessionId?.let { live -> attempt("Couldn’t change the working directory") { session.setCwd(live, path) } }
        }
    }

    fun rename(title: String) {
        val name = title.trim()
        _state.update { it.copy(dialog = null) }
        if (name.isEmpty()) return
        val previous = chat.title
        setChat { it.copy(title = name) }
        launch {
            val ok = attempt(null) { chat.liveSessionId?.let { session.setTitle(it, name) } }
            val stored = chat.storedSessionId
            val rest = if (stored != null) backend.renameSession(stored, name).isSuccess else ok
            if (!ok && !rest) {
                setChat { it.copy(title = previous) }
                backend.toast("Couldn’t rename the chat")
            }
        }
    }

    fun archive() = finish("Archived", "Couldn’t archive the chat") { backend.archiveSession(it) }

    /** Reads whether the opened session is archived; an archived chat is read-only until [unarchive]. */
    private fun checkArchived(stored: String) {
        launch { backend.sessionArchived(stored).onSuccess { a -> _state.update { it.copy(archived = a) } } }
    }

    /** "Unarchive" on the archived banner (PATCH `{archived:false}`): the chat becomes writable again. */
    fun unarchive() {
        _state.update { it.copy(menu = null) }
        val stored = chat.storedSessionId ?: return
        launch {
            backend.unarchiveSession(stored)
                .onSuccess {
                    _state.update { it.copy(archived = false) }
                    backend.toast("Unarchived")
                }
                .onFailure { backend.toast("Couldn’t unarchive the chat") }
        }
    }

    /** Long-press on a message: opens (or with null closes) its copy menu. */
    fun showMessageMenu(id: String?) = _state.update { it.copy(messageMenu = id) }

    /**
     * Copies message [id]'s text, or with [reasoning] its reasoning, through [copy] (the screen's clipboard) and
     * confirms with a toast. Nothing is copied for an empty one.
     */
    fun copyMessage(id: String, reasoning: Boolean, copy: (String) -> Unit) {
        _state.update { it.copy(messageMenu = null) }
        val text = when (val item = chat.items.firstOrNull { it.id == id }) {
            is ChatItem.UserMessage -> item.text.takeIf { !reasoning }
            is ChatItem.AssistantMessage -> if (reasoning) item.reasoning else item.text
            else -> null
        }?.takeIf { it.isNotEmpty() } ?: return
        copy(text)
        backend.toast(if (reasoning) "Reasoning copied" else "Message copied")
    }

    fun delete() = finish("Deleted", "Couldn’t delete the chat") { backend.deleteSession(it) }

    private fun finish(done: String, failed: String, action: suspend (String) -> Result<Unit>) {
        _state.update { it.copy(dialog = null, menu = null) }
        val stored = chat.storedSessionId ?: return
        launch {
            if (action(stored).isSuccess) {
                backend.toast(done)
                _closed.trySend(Unit)
            } else {
                backend.toast(failed)
            }
        }
    }

    /**
     * Copies the stored session id (the live id while there is no stored one yet) through [copy] (the screen's
     * clipboard) and confirms with a toast.
     */
    fun copySessionId(copy: (String) -> Unit) {
        _state.update { it.copy(menu = null) }
        val id = chat.storedSessionId ?: chat.liveSessionId ?: return
        copy(id)
        backend.toast("Session ID copied")
    }

    /** A failure is usually a dropped socket: keep Offline (set by the loss) rather than re-enable the composer. */
    private fun unlessOffline(current: AgentStatus, next: AgentStatus) =
        if (current == AgentStatus.Offline) current else next

    /** Runs [block]; on failure shows "[failure]: reason" (if given) and returns false. */
    private suspend fun attempt(failure: String?, block: suspend () -> Unit): Boolean =
        try {
            block()
            true
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            failure?.let { backend.toast("$it: ${e.message ?: "gateway error"}") }
            false
        }

    private companion object {
        /** `prompt.submit` status of a send the server holds until the live turn ends. */
        const val QUEUED = "queued"

        /** How long an attachment waits for the session to open before failing (retry is offered). */
        const val OPEN_WAIT_MS = 30_000L

        /** `session.interrupt` status when no turn was running. */
        const val NOT_INTERRUPTED = "not_interrupted"

        /** How long a voice language pick waits for the saved setting to show before restarting the recogniser. */
        const val SETTINGS_SETTLE_MS = 2_000L
    }
}
