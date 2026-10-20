package labs.suchattai.tsukaima.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import labs.suchattai.tsukaima.data.gateway.ApprovalChoice
import labs.suchattai.tsukaima.data.gateway.ConnectionState
import labs.suchattai.tsukaima.data.gateway.Frame
import labs.suchattai.tsukaima.data.gateway.GatewayClosedException
import labs.suchattai.tsukaima.data.gateway.GatewaySession
import labs.suchattai.tsukaima.data.gateway.SessionResumed
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
    private val session = GatewaySession(backend.transport)
    private val _state = MutableStateFlow(ChatUiState(loading = storedSessionId != null, isNew = storedSessionId == null))
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    private val _closed = Channel<Unit>(Channel.CONFLATED)

    /** Emits once the session was archived or deleted from this screen. */
    val closed: Flow<Unit> = _closed.receiveAsFlow()

    private var opened = false
    private var opening = false
    private var lost = false
    private var userCounter = 0L
    private var held: MutableList<Frame.Event>? = null
    private val recentRequests = ArrayDeque<Frame.ServerRequest>()

    private val chat get() = _state.value.chat
    private fun setChat(block: (ChatState) -> ChatState) = _state.update { it.copy(chat = block(it.chat)) }
    private fun launch(block: suspend () -> Unit) = viewModelScope.launch(dispatcher) { block() }

    init {
        launch { backend.hermes.collect { h -> _state.update { it.copy(gatewayStatus = h.gatewayStatus()) } } }
        launch { backend.events.collect { ev -> held?.add(ev) ?: applyEvent(ev) } }
        launch {
            backend.serverRequests.collect { req ->
                recentRequests.addLast(req)
                if (recentRequests.size > 32) recentRequests.removeFirst()
                adopt(req)
            }
        }
        launch { backend.connection.collect(::onConnection) }
        open()
    }

    // ---- lifecycle --------------------------------------------------------------------------------

    /** Creates or resumes the session (idempotent; retried on the next `Connected` if it failed). */
    fun open() {
        if (opened || opening) return
        opening = true
        launch {
            try {
                val settings = backend.settings.filterNotNull().first()
                _state.update { it.copy(workspace = it.workspace ?: settings.workspace) }
                if (storedSessionId == null) {
                    create(settings.model, settings.modelProvider)
                } else {
                    _state.update { it.copy(loading = true) }
                    val r = session.resume(storedSessionId)
                    bindResumed(r, rebuild = true)
                    adoptOpen(r.openRequests, r.pendingApproval)
                    syncWatermark(r.sessionId)
                }
                opened = true
                recentRequests.toList().forEach(::adopt)
            } catch (c: CancellationException) {
                throw c
            } catch (_: GatewayClosedException) {
                // Not connected yet (or dropped): opened again on the next Connected.
            } catch (e: Exception) {
                backend.toast("Couldn’t open the chat: ${e.message ?: "gateway error"}")
            } finally {
                opening = false
                _state.update { it.copy(loading = false) }
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

    /**
     * The server may re-attach a live session that already streamed events (another client, or this chat
     * reopened within the server's grace window). Its history covers them, so start the watermark at the
     * ring's latest seq: a later reconnect to the same live id then replays only what this screen missed.
     */
    private suspend fun syncWatermark(live: String) {
        attempt(null) {
            val latest = ChatSync.replay(session.eventsSince(live, Long.MAX_VALUE)).latestSeq ?: return@attempt
            setChat { if (it.liveSessionId == live && latest > it.lastSeq) it.copy(lastSeq = latest) else it }
        }
    }

    /** Binds a resume result; [rebuild] replaces the transcript with its history. Adopts its open requests. */
    private fun bindResumed(r: SessionResumed, rebuild: Boolean) {
        setChat { c ->
            val bound = ChatReducer.onSessionBound(c, r.sessionId, r.storedSessionId ?: storedSessionId)
            val items = if (rebuild) bound.copy(items = ChatReducer.fromHistory(r.messages)) else bound
            if (r.running) items.copy(status = AgentStatus.Thinking(flair())) else ChatReducer.onConnectionRestored(items)
        }
    }

    /**
     * Adopts a server snapshot of still-open requests. The snapshot is authoritative: an answer this screen
     * sent may have been lost with the socket, so a listed request is shown (and answerable) again.
     */
    private fun adoptOpen(entries: List<JsonObject>, pending: JsonObject?) {
        val live = chat.liveSessionId ?: return
        entries.mapNotNull { ChatSync.openRequest(it, live) }.forEach(::reopen)
        if (pending != null) {
            val rid = (pending["request_id"] as? JsonPrimitive)?.contentOrNull
            val shown = chat.items.any { it is ChatItem.Approval && !it.answered && rid != null && it.requestId == rid }
            if (!shown) reopen(ChatSync.pendingApproval(pending, live))
        }
    }

    private fun reopen(req: Frame.ServerRequest) {
        if (req.method != "approval") return
        backend.answeredRequestIds.remove(req.id)
        _state.update { s ->
            s.copy(chat = s.chat.copy(items = s.chat.items.filterNot { it.id == req.id }), decisions = s.decisions - req.id)
        }
        adopt(req)
    }

    private fun onConnection(s: ConnectionState) {
        if (s == ConnectionState.Connected) {
            when {
                lost -> {
                    lost = false
                    launch { reconnect() }
                }
                !opened -> open()
            }
        } else if (opened && !lost && s != ConnectionState.Connecting) {
            lost = true
            setChat(ChatReducer::onConnectionLost)
        }
    }

    private suspend fun reconnect() {
        val stored = chat.storedSessionId ?: return setChat(ChatReducer::onConnectionRestored)
        val previousLive = chat.liveSessionId
        held = mutableListOf()
        try {
            val r = session.resume(stored)
            val sameLive = r.sessionId == previousLive
            bindResumed(r, rebuild = !sameLive)
            val replay = ChatSync.replay(session.eventsSince(r.sessionId, if (sameLive) chat.lastSeq else 0))
            if (replay.truncated) {
                // The ring lost events we never saw: trust the stored history instead of a replay with holes.
                setChat { it.copy(items = ChatReducer.fromHistory(r.messages), lastSeq = replay.latestSeq ?: it.lastSeq) }
            } else {
                replay.events.forEach(::applyEvent)
            }
            adoptOpen(r.openRequests + replay.openRequests, r.pendingApproval)
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            // Dropped again: stay offline, the next Connected retries. Otherwise show the chat as usable.
            if (backend.connection.value == ConnectionState.Connected) {
                setChat(ChatReducer::onConnectionRestored)
                backend.toast("Couldn’t resume the chat: ${e.message ?: "gateway error"}")
            }
        } finally {
            val pending = held.orEmpty()
            held = null
            pending.forEach(::applyEvent)
        }
    }

    private fun applyEvent(ev: Frame.Event) {
        val live = chat.liveSessionId ?: return
        if (ev.sessionId.isNotEmpty() && ev.sessionId != live) return
        if (ev.type == "session.info") {
            val model = (ev.payload["model"] as? JsonPrimitive)?.contentOrNull
            val provider = (ev.payload["provider"] as? JsonPrimitive)?.contentOrNull
            _state.update { it.copy(model = model ?: it.model, provider = provider ?: it.provider) }
        }
        setChat { ChatReducer.reduce(it, ev, flair) }
    }

    /** Approvals for this live session that nobody has answered yet; replays of answered ones are skipped. */
    private fun adopt(req: Frame.ServerRequest) {
        if (req.method != "approval" || req.id in backend.answeredRequestIds) return
        val live = chat.liveSessionId ?: return
        if ((req.params["session_id"] as? JsonPrimitive)?.contentOrNull != live) return
        setChat { ChatReducer.reduce(it, req, flair) }
    }

    // ---- composer ---------------------------------------------------------------------------------

    fun setInput(text: String) = _state.update { it.copy(input = text) }

    fun send() {
        val text = _state.value.input.trim()
        if (text.isEmpty() || _state.value.offline) return
        val live = chat.liveSessionId ?: return backend.toast("Still connecting to Hermes")
        _state.update {
            it.copy(input = "", chat = ChatReducer.onSubmitted(it.chat, text, "u-l${++userCounter}", flair))
        }
        launch {
            try {
                session.submit(live, text)
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                setChat { it.copy(status = AgentStatus.Idle) }
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

    private fun answer(id: String, approved: Boolean) {
        val card = chat.items.firstOrNull { it.id == id } as? ChatItem.Approval ?: return
        if (card.answered) return
        if (!backend.answeredRequestIds.add(id)) {
            return setChat { ChatReducer.onApprovalAnswered(it, id, flair) } // answered elsewhere (a notification)
        }
        val choice = if (approved) ApprovalChoice.once else ApprovalChoice.deny
        _state.update {
            it.copy(chat = ChatReducer.onApprovalAnswered(it.chat, id, flair), decisions = it.decisions + (id to approved))
        }
        if (id.startsWith(ChatSync.PENDING_PREFIX)) {
            val live = chat.liveSessionId ?: return
            launch { attempt("Couldn’t send the answer") { session.approvalRespond(live, card.requestId.ifEmpty { null }, choice) } }
        } else {
            session.respondApproval(id, choice)
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
