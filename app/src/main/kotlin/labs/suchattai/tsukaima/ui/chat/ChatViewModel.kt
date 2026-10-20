package labs.suchattai.tsukaima.ui.chat

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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import labs.suchattai.tsukaima.data.gateway.ApprovalChoice
import labs.suchattai.tsukaima.data.gateway.ConnectionState
import labs.suchattai.tsukaima.data.gateway.GatewayClosedException
import labs.suchattai.tsukaima.data.gateway.GatewaySession
import labs.suchattai.tsukaima.data.gatewayStatus
import labs.suchattai.tsukaima.domain.chat.AgentStatus
import labs.suchattai.tsukaima.domain.chat.ChatItem
import labs.suchattai.tsukaima.domain.chat.ChatReducer
import labs.suchattai.tsukaima.domain.chat.ChatState
import labs.suchattai.tsukaima.domain.chat.SecretKind
import labs.suchattai.tsukaima.domain.chat.ThinkingFlair

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
    private data class FailedSend(val text: String, val attachments: List<PendingAttachment>)

    private val failedSends = mutableMapOf<String, FailedSend>()
    private val sync = ChatReconnector(
        session, backend, CoroutineScope(viewModelScope.coroutineContext + dispatcher), storedSessionId, flair, _state,
        onRebuilt = ::restoreFailedSends,
    )

    private val attachments = ChatAttachments(
        session, backend, CoroutineScope(viewModelScope.coroutineContext + dispatcher), io, _state, ::liveSession,
    )

    private val media = ChatMedia(backend, CoroutineScope(viewModelScope.coroutineContext + dispatcher), io, _state)

    private val chat get() = _state.value.chat
    private fun setChat(block: (ChatState) -> ChatState) = _state.update { it.copy(chat = block(it.chat)) }
    private fun launch(block: suspend () -> Unit) = viewModelScope.launch(dispatcher) { block() }

    init {
        launch { backend.hermes.collect { h -> _state.update { it.copy(gatewayStatus = h.gatewayStatus()) } } }
        launch { backend.events.collect(sync::onEvent) }
        launch { backend.serverRequests.collect(sync::onRequest) }
        launch { backend.connection.collect(::onConnection) }
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
                    sync.syncWatermark(r.sessionId)
                }
                opened = true
                sync.adoptRecent()
                if (storedSessionId != null) checkArchived(storedSessionId)
            } catch (c: CancellationException) {
                throw c
            } catch (_: GatewayClosedException) {
                notConnected = true // not connected yet, or dropped: retried on Connected
            } catch (e: Exception) {
                backend.toast("Couldn’t open the chat: ${e.message ?: "gateway error"}")
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

    /** Sends the typed text with the ready attachments ([Attachments.promptText]); see [ChatUiState.canSend]. */
    fun send() {
        if (!_state.value.canSend) return
        val text = _state.value.input.trim()
        val live = chat.liveSessionId ?: return backend.toast("Still connecting to Hermes")
        val sent = attachments.takeReady()
        val localId = "u-l${++userCounter}"
        _state.update {
            it.copy(input = "", chat = ChatReducer.onSubmitted(it.chat, text, localId, flair, sent.map(PendingAttachment::name)))
        }
        launch { submit(live, localId, text, sent) }
    }

    /**
     * Submits user message [localId]. A failure keeps the bubble, marked failed with Retry and Dismiss; its text
     * and attachments wait in [failedSends] (not back in the composer, which would show them twice).
     */
    private suspend fun submit(live: String, localId: String, text: String, sent: List<PendingAttachment>) {
        try {
            session.submit(live, Attachments.promptText(text, sent))
            attachments.sent(sent)
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            failedSends[localId] = FailedSend(text, sent)
            setChat { c -> c.copy(items = c.items.markFailed(localId, true), status = unlessOffline(c.status, AgentStatus.Idle)) }
            backend.toast("Message not sent: ${e.message ?: "gateway error"}")
        }
    }

    /**
     * Retry on a failed bubble: submits the same text and attachments again. Images queued in a live session that
     * has since been replaced are gone from the server, so such a send is put back in the composer instead (the
     * images show as failed there, ready for their own retry).
     */
    fun retrySend(localId: String) {
        val failed = failedSends[localId] ?: return
        if (chat.items.none { it.id == localId }) {
            failedSends.remove(localId) // the transcript was rebuilt from history meanwhile
            return
        }
        if (!_state.value.writable) return backend.toast("Not connected to Hermes")
        val live = chat.liveSessionId ?: return backend.toast("Still connecting to Hermes")
        if (failed.attachments.any { it.kind == AttachmentKind.Image && it.liveSessionId != live }) {
            dismissFailed(localId)
            return backend.toast("Attach the images again, then send")
        }
        failedSends.remove(localId)
        setChat { c -> c.copy(items = c.items.markFailed(localId, false), status = AgentStatus.Thinking(flair())) }
        launch { submit(live, localId, failed.text, failed.attachments) }
    }

    /** The reasoning header of message [id] was tapped. */
    fun toggleReasoning(id: String) = setChat { ChatReducer.toggleReasoning(it, id) }

    /** A rebuilt transcript holds only what the server stored: the failed bubbles (never sent) are put back at the end. */
    private fun restoreFailedSends() {
        if (failedSends.isEmpty()) return
        setChat { c ->
            val missing = failedSends.filterKeys { id -> c.items.none { it.id == id } }
            if (missing.isEmpty()) c
            else c.copy(items = c.items + missing.map { (id, f) -> ChatItem.UserMessage(id, f.text, f.attachments.map { it.name }, failed = true) })
        }
    }

    /** Dismiss on a failed bubble: removes it; its text (if the composer is empty) and attachments go back to the composer. */
    fun dismissFailed(localId: String) {
        val failed = failedSends.remove(localId)
        setChat { c -> c.copy(items = c.items.filterNot { it.id == localId && it is ChatItem.UserMessage && it.failed }) }
        if (failed == null) return
        _state.update { if (it.input.isEmpty()) it.copy(input = failed.text) else it }
        attachments.restore(failed.attachments)
        chat.liveSessionId?.let(attachments::onLiveSession)
    }

    private fun List<ChatItem>.markFailed(localId: String, failed: Boolean) =
        map { if (it.id == localId && it is ChatItem.UserMessage) it.copy(failed = failed) else it }

    /** Uploads picked files at once (creating the session first if needed); see [ChatAttachments]. */
    fun attach(files: List<PickedFile>) = attachments.attach(files)

    fun removeAttachment(localId: String) = attachments.remove(localId)

    fun retryAttachment(localId: String) = attachments.retry(localId)

    /** A tap on a `MEDIA:` card: download into Downloads and open it; see [ChatMedia]. */
    fun openMedia(path: String) = media.open(path)

    /** The inline preview of image [path] (cached), or null. */
    suspend fun preview(path: String): ImageBitmap? = withContext(dispatcher) { media.preview(path) }

    /** The live session id; opens (creates or resumes) the session first and waits for it when there is none. */
    private suspend fun liveSession(): String? {
        chat.liveSessionId?.let { return it }
        open()
        return withTimeoutOrNull(OPEN_WAIT_MS) { _state.map { it.chat.liveSessionId }.filterNotNull().first() }
    }

    /**
     * Stop: interrupts the running turn (`session.interrupt`). The turn then ends with the server's own
     * `message.complete` / `error`, which returns the status line (and the send button) to idle.
     */
    fun interrupt() {
        _state.update { it.copy(menu = null) }
        val live = chat.liveSessionId ?: return
        launch {
            attempt("Couldn’t stop the agent") {
                if (session.interrupt(live) == NOT_INTERRUPTED) backend.toast("Nothing to stop")
            }
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
     * Sends [value] for masked prompt [id] (`sudo`, `secret`, `vault.unlock_prompt`); `""` declines. The value
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
                        .also { waiting = AgentStatus.Secret(password = it.kind != SecretKind.Secret) }
                    else -> it
                }
            }
            s.copy(chat = s.chat.copy(items = items, status = unlessOffline(s.chat.status, waiting)), decisions = s.decisions - id)
        }
    }

    // ---- menus and session actions ------------------------------------------------------------------

    fun showMenu(menu: ChatMenu?) {
        _state.update { it.copy(menu = menu) }
        if (menu == ChatMenu.Model && _state.value.models == null) {
            launch { backend.modelOptions().onSuccess { m -> _state.update { it.copy(models = m) } } }
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
        /** How long an attachment waits for the session to open before failing (retry is offered). */
        const val OPEN_WAIT_MS = 30_000L

        /** `session.interrupt` status when no turn was running. */
        const val NOT_INTERRUPTED = "not_interrupted"
    }
}
