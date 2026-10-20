package labs.suchattai.tsukaima.ui.chat

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
    private val sync = ChatReconnector(
        session, backend, CoroutineScope(viewModelScope.coroutineContext + dispatcher), storedSessionId, flair, _state,
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
            // Lets notifications open the stored session that asked, knowing only its live id.
            _state.map { it.chat.liveSessionId to it.chat.storedSessionId }.distinctUntilChanged().collect { (live, stored) ->
                if (live != null) backend.bindSession(live, stored)
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
                chat = ChatReducer.onSessionBound(it.chat, created.sessionId, created.storedSessionId),
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

    fun send() {
        val text = _state.value.input.trim()
        if (text.isEmpty() || _state.value.offline) return
        val live = chat.liveSessionId ?: return backend.toast("Still connecting to Hermes")
        val localId = "u-l${++userCounter}"
        _state.update { it.copy(input = "", chat = ChatReducer.onSubmitted(it.chat, text, localId, flair)) }
        launch {
            try {
                session.submit(live, text)
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                // Undo the optimistic bubble: the text goes back to the composer, so a retry must not duplicate it.
                setChat { c -> c.copy(items = c.items.filterNot { it.id == localId }, status = unlessOffline(c.status, AgentStatus.Idle)) }
                _state.update { if (it.input.isEmpty()) it.copy(input = text) else it }
                backend.toast("Message not sent: ${e.message ?: "gateway error"}")
            }
        }
    }

    /** Attach is not built yet (upload into the workspace is a later task). */
    fun attach() = backend.toast("Attachments aren’t supported yet")

    fun interrupt() {
        val live = chat.liveSessionId ?: return
        launch { attempt("Couldn’t stop the agent") { session.interrupt(live) } }
    }

    fun approve(id: String) = answer(id, approved = true)
    fun deny(id: String) = answer(id, approved = false)

    /**
     * Answers through the exact server request (`srq-…` id). A resume's `pending_approval` has no such id and
     * falls back to `approval.respond`, but only with its `request_id`: without one the server would resolve
     * whatever approval is pending, so such a card is inert ([ChatSync.answerable]).
     */
    private fun answer(id: String, approved: Boolean) {
        val card = chat.items.firstOrNull { it.id == id } as? ChatItem.Approval ?: return
        if (card.answered || !ChatSync.answerable(card)) return
        if (!backend.answeredRequestIds.add(id)) {
            return setChat { ChatReducer.onApprovalAnswered(it, id, flair) } // answered elsewhere (a notification)
        }
        val choice = if (approved) ApprovalChoice.once else ApprovalChoice.deny
        _state.update {
            it.copy(chat = ChatReducer.onApprovalAnswered(it.chat, id, flair), decisions = it.decisions + (id to approved))
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

    /** Undoes an answer that was not delivered: the card is answerable again and the claim is released. */
    private fun reopenAnswer(id: String) {
        backend.answeredRequestIds.remove(id)
        _state.update { s ->
            val items = s.chat.items.map { if (it.id == id && it is ChatItem.Approval) it.copy(answered = false) else it }
            s.copy(chat = s.chat.copy(items = items, status = unlessOffline(s.chat.status, AgentStatus.Approval)), decisions = s.decisions - id)
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

    fun showDialog(dialog: ChatDialog?) = _state.update { it.copy(dialog = dialog, menu = null) }

    /**
     * Hermes has no live model switch: a new, still-empty chat is re-created with the model; otherwise the
     * choice is saved for the next chat.
     */
    fun setModel(provider: String, model: String) {
        _state.update { it.copy(menu = null) }
        launch {
            backend.updateSettings { it.copy(model = model, modelProvider = provider) }
            val live = chat.liveSessionId
            if (storedSessionId != null || chat.items.isNotEmpty() || live == null) {
                return@launch backend.toast("Model applies to the next chat")
            }
            attempt("Couldn’t switch the model") {
                runCatching { session.close(live) }.onFailure { if (it is CancellationException) throw it }
                create(model, provider)
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

    /** Copies the stored session id through [copy] (the screen's clipboard) and confirms with a toast. */
    fun copySessionId(copy: (String) -> Unit) {
        _state.update { it.copy(menu = null) }
        val id = chat.storedSessionId ?: return
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
}
