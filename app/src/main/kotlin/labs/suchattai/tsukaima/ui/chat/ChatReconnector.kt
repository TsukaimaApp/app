package labs.suchattai.tsukaima.ui.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import labs.suchattai.tsukaima.data.gateway.ConnectionState
import labs.suchattai.tsukaima.data.gateway.Frame
import labs.suchattai.tsukaima.data.gateway.GatewaySession
import labs.suchattai.tsukaima.data.gateway.SessionResumed
import labs.suchattai.tsukaima.domain.chat.AgentStatus
import labs.suchattai.tsukaima.domain.chat.ChatItem
import labs.suchattai.tsukaima.domain.chat.ChatReducer
import labs.suchattai.tsukaima.domain.chat.ChatState

/**
 * Keeps a [ChatViewModel]'s transcript in sync with its live Hermes session: routes live events and server
 * requests to the reducer (only for the bound live id), binds resume results, and recovers after a socket loss.
 *
 * Must be driven from one thread ([scope] is the VM's single-threaded scope), so its fields need no lock.
 *
 * Reconnect: at most one run at a time. A loss cancels the run in flight, and a new run cancels any previous
 * one. Each run owns the buffer that holds live events while it resumes and replays; only the run whose buffer
 * is still current flushes it, so a stale run can neither steal nor drop a newer run's events.
 */
internal class ChatReconnector(
    private val session: GatewaySession,
    private val backend: ChatBackend,
    private val scope: CoroutineScope,
    private val routeStoredId: String?,
    private val flair: () -> String,
    private val state: MutableStateFlow<ChatUiState>,
) {
    private var job: Job? = null
    private var held: MutableList<Frame.Event>? = null
    private val recentRequests = ArrayDeque<Frame.ServerRequest>()

    /** Ids the latest resume snapshot listed as open (`open_requests` + `pending_approval`); null before one. */
    private var snapshotOpenIds: Set<String>? = null

    private val chat get() = state.value.chat
    private fun setChat(block: (ChatState) -> ChatState) = state.update { it.copy(chat = block(it.chat)) }

    fun onEvent(ev: Frame.Event) {
        held?.add(ev) ?: applyEvent(ev)
    }

    fun onRequest(req: Frame.ServerRequest) {
        recentRequests.addLast(req)
        if (recentRequests.size > 32) recentRequests.removeFirst()
        adopt(req)
    }

    /**
     * After the first bind: requests that arrived (or were replayed) before the live id was known. The replay
     * may hold requests withdrawn since, so ids the container saw closed are skipped, and once a resume
     * snapshot exists so is every id it did not list as open.
     */
    fun adoptRecent() {
        val open = snapshotOpenIds
        recentRequests.toList()
            .filter { open == null || it.id in open }
            .forEach { adopt(it) }
    }

    fun onLost() {
        job?.cancel()
        job = null
        held = null
        setChat(ChatReducer::onConnectionLost)
    }

    fun reconnect() {
        job?.cancel()
        val buffer = mutableListOf<Frame.Event>()
        held = buffer
        job = scope.launch { run(buffer) }
    }

    private suspend fun run(buffer: MutableList<Frame.Event>) {
        try {
            val stored = chat.storedSessionId ?: return setChat(ChatReducer::onConnectionRestored)
            val previousLive = chat.liveSessionId
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
            if (held === buffer) {
                held = null
                buffer.forEach(::applyEvent)
            }
        }
    }

    /** Binds a resume result; [rebuild] replaces the transcript with its history. */
    fun bindResumed(r: SessionResumed, rebuild: Boolean) {
        setChat { c ->
            val bound = ChatReducer.onSessionBound(c, r.sessionId, r.storedSessionId ?: routeStoredId)
            val items = if (rebuild) bound.copy(items = ChatReducer.fromHistory(r.messages)) else bound
            if (r.running) items.copy(status = AgentStatus.Thinking(flair())) else ChatReducer.onConnectionRestored(items)
        }
    }

    /**
     * The server may re-attach a live session that already streamed events (another client, or this chat
     * reopened within the server's grace window). Its history covers them, so start the watermark at the
     * ring's latest seq: a later reconnect to the same live id then replays only what this screen missed.
     */
    suspend fun syncWatermark(live: String) {
        try {
            val latest = ChatSync.replay(session.eventsSince(live, Long.MAX_VALUE)).latestSeq ?: return
            setChat { if (it.liveSessionId == live && latest > it.lastSeq) it.copy(lastSeq = latest) else it }
        } catch (c: CancellationException) {
            throw c
        } catch (_: Exception) {
            // Best effort: without it a same-live reconnect may replay already-shown turns.
        }
    }

    /**
     * Adopts a server snapshot of still-open requests. The snapshot is authoritative: an answer this screen
     * sent may have been lost with the socket, so a listed request is shown (and answerable) again.
     */
    fun adoptOpen(entries: List<JsonObject>, pending: JsonObject?) {
        val live = chat.liveSessionId ?: return
        val open = entries.mapNotNull { ChatSync.openRequest(it, live) }
        val pendingReq = pending?.let { ChatSync.pendingApproval(it, live) }
        snapshotOpenIds = (open + listOfNotNull(pendingReq)).map { it.id }.toSet()
        open.forEach(::reopen)
        if (pending != null && pendingReq != null) {
            val rid = (pending["request_id"] as? JsonPrimitive)?.contentOrNull
            val shown = chat.items.any { it is ChatItem.Approval && !it.answered && rid != null && it.requestId == rid }
            if (!shown) reopen(pendingReq)
        }
    }

    private fun reopen(req: Frame.ServerRequest) {
        if (req.method != "approval") return
        backend.answeredRequestIds.remove(req.id)
        state.update { s ->
            s.copy(chat = s.chat.copy(items = s.chat.items.filterNot { it.id == req.id }), decisions = s.decisions - req.id)
        }
        adopt(req, snapshot = true)
    }

    private fun applyEvent(ev: Frame.Event) {
        val live = chat.liveSessionId ?: return
        if (ev.sessionId.isNotEmpty() && ev.sessionId != live) return
        if (ev.type == "session.info") {
            val model = (ev.payload["model"] as? JsonPrimitive)?.contentOrNull
            val provider = (ev.payload["provider"] as? JsonPrimitive)?.contentOrNull
            state.update { it.copy(model = model ?: it.model, provider = provider ?: it.provider) }
        }
        setChat { ChatReducer.reduce(it, ev, flair) }
    }

    /**
     * Approvals for this live session that nobody has answered yet; replays of answered ones are skipped, and
     * so are withdrawn ones unless a resume [snapshot] (authoritative) lists them as open again.
     */
    private fun adopt(req: Frame.ServerRequest, snapshot: Boolean = false) {
        if (req.method != "approval" || req.id in backend.answeredRequestIds) return
        if (!snapshot && backend.closedRequestIds.contains(req.id)) return
        val live = chat.liveSessionId ?: return
        if ((req.params["session_id"] as? JsonPrimitive)?.contentOrNull != live) return
        setChat { ChatReducer.reduce(it, req, flair) }
    }
}
