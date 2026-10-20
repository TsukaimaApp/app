package labs.suchattai.tsukaima.domain.chat

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import labs.suchattai.tsukaima.data.gateway.Frame
import labs.suchattai.tsukaima.data.gateway.TranscriptMessage

/**
 * Pure fold of gateway frames and local UI actions into [ChatState].
 *
 * The only impurity is the thinking flair, injected as `flair` so tests are deterministic.
 */
object ChatReducer {
    private const val APPROVAL = "approval"
    private const val CLARIFY = "clarify"
    private const val SUBAGENT_PREFIX = "subagent."

    /** Terminal `subagent.complete` statuses that mean the child did not finish its goal. */
    private val SUBAGENT_FAILED = setOf("failed", "error", "timeout", "interrupted")

    fun reduce(state: ChatState, frame: Frame, flair: () -> String = ThinkingFlair::next): ChatState =
        when (frame) {
            is Frame.Event -> reduceEvent(state, frame, flair)
            is Frame.ServerRequest -> reduceServerRequest(state, frame)
            is Frame.Response, is Frame.Unknown -> state
        }

    fun fromHistory(messages: List<TranscriptMessage>): List<ChatItem> =
        messages.mapIndexedNotNull { index, m ->
            val id = "h-${m.rowId ?: index}"
            when (m.role) {
                "user" -> ChatItem.UserMessage(id, m.text, rowId = m.rowId)
                "assistant" ->
                    if (m.text.isBlank()) null
                    else ChatItem.AssistantMessage(id, m.text, streaming = false, reasoning = m.reasoning, rowId = m.rowId)
                "tool" -> ChatItem.ToolCall(
                    id = id,
                    name = m.name ?: "tool",
                    context = m.context,
                    args = m.args,
                    status = ToolStatus.Done,
                    output = null,
                    exitCode = null,
                    durationS = null,
                )
                else -> null
            }
        }

    fun onSubmitted(
        state: ChatState,
        text: String,
        localId: String,
        flair: () -> String = ThinkingFlair::next,
        attachments: List<String> = emptyList(),
        promptText: String? = null,
        imagesSent: Boolean = false,
    ): ChatState = state.copy(
        items = state.items + ChatItem.UserMessage(
            localId, text, attachments, promptText = promptText?.takeIf { it != text }, imagesSent = imagesSent,
        ),
        status = AgentStatus.Thinking(flair()),
    )

    /**
     * `prompt.submit` accepted user message [localId]: stores its persisted [userRowId] (null = not proven yet, kept
     * unknown). After a truncation, [survivorUserRowIds] are the kept user turns' ids in order (the server may mint
     * new rows on a rewrite): they rebind the user messages above [localId] when the counts match, a null entry
     * dropping a stale id.
     */
    fun onSubmitAccepted(
        state: ChatState,
        localId: String,
        userRowId: Long?,
        survivorUserRowIds: List<Long?>? = null,
    ): ChatState {
        val index = state.items.indexOfFirst { it.id == localId && it is ChatItem.UserMessage }
        if (index < 0) return state
        var items = state.items
        if (userRowId != null) {
            items = items.mapIndexed { i, item -> if (i == index) (item as ChatItem.UserMessage).copy(rowId = userRowId) else item }
        }
        if (survivorUserRowIds != null) {
            val kept = items.subList(0, index).withIndex().filter { it.value is ChatItem.UserMessage && !(it.value as ChatItem.UserMessage).failed }
            if (kept.size == survivorUserRowIds.size) {
                val rebound = kept.map { it.index }.zip(survivorUserRowIds).toMap()
                items = items.mapIndexed { i, item ->
                    if (i in rebound) (item as ChatItem.UserMessage).copy(rowId = rebound[i]) else item
                }
            }
        }
        return if (items === state.items) state else state.copy(items = items)
    }

    /**
     * An edit or regenerate rewound the transcript to user row [beforeRowId]: drops the first item whose row id is
     * at or after it and everything below (later turns, their tools, and local echoes not persisted yet). Unchanged
     * when no item carries such a row.
     */
    fun onTruncated(state: ChatState, beforeRowId: Long): ChatState {
        val cut = state.items.indexOfFirst { (it.persistedRow ?: return@indexOfFirst false) >= beforeRowId }
        return if (cut < 0) state else state.copy(items = state.items.subList(0, cut).toList())
    }

    /** The persisted row of a user or assistant message; null for other rows or when unknown. */
    private val ChatItem.persistedRow: Long?
        get() = when (this) {
            is ChatItem.UserMessage -> rowId
            is ChatItem.AssistantMessage -> rowId
            else -> null
        }

    /**
     * A side question or background task the server accepted as [taskId]: appends its pending note. Unchanged when
     * the completion already arrived (it can beat the RPC result); the status is left alone (side agents are detached).
     */
    fun onSideStarted(state: ChatState, taskId: String, kind: SideKind, question: String?): ChatState {
        val id = sideId(taskId)
        if (state.items.any { it.id == id }) return state
        return state.copy(items = state.items + ChatItem.SideNote(id, kind, question, text = "", pending = true))
    }

    /** Marks approval [id] answered; idempotent so a double tap sends one answer. */
    fun onApprovalAnswered(state: ChatState, id: String, flair: () -> String = ThinkingFlair::next): ChatState {
        val card = state.items.firstOrNull { it.id == id } as? ChatItem.Approval
        if (card == null || card.answered) return state
        val items = state.items.map { if (it === card) card.copy(answered = true) else it }
        return state.copy(items = items, status = statusAfterApproval(state.status, items, flair))
    }

    /**
     * Marks masked-value request [id] answered; [declined] when an empty value (skip) was sent. Only that flag is
     * recorded: the value never enters the state. Idempotent.
     */
    fun onSecretAnswered(state: ChatState, id: String, declined: Boolean, flair: () -> String = ThinkingFlair::next): ChatState {
        val card = state.items.firstOrNull { it.id == id } as? ChatItem.SecretPrompt
        if (card == null || card.answered) return state
        val items = state.items.map { if (it === card) card.copy(answered = true, declined = declined) else it }
        return state.copy(items = items, status = statusAfterApproval(state.status, items, flair))
    }

    /** Marks clarify [id] answered with what was sent ([answers]; empty = cancel all); idempotent. */
    fun onClarifyAnswered(
        state: ChatState,
        id: String,
        answers: Map<String, String?>,
        flair: () -> String = ThinkingFlair::next,
    ): ChatState {
        val card = state.items.firstOrNull { it.id == id } as? ChatItem.Clarify
        if (card == null || card.answered) return state
        val items = state.items.map { if (it === card) card.copy(answered = true, answers = answers) else it }
        return state.copy(items = items, status = statusAfterApproval(state.status, items, flair))
    }

    /**
     * Binds the chat to a live session ([liveId]) and its stored id; called after every `session.create` and
     * `session.resume`, including the resume after a reconnect. A new live id has its own seq numbering, so the
     * replay watermark (to [NO_SEQ]: nothing seen, so even a seq of 0 applies) and the local id counter reset. When the server re-attached the
     * same live session (a reconnect inside its grace window) the seq continues, so both are kept and a
     * `session.events.since(lastSeq)` replay stays a true delta. A null [storedId] keeps the known one.
     */
    fun onSessionBound(state: ChatState, liveId: String, storedId: String?): ChatState {
        val ids = state.copy(liveSessionId = liveId, storedSessionId = storedId ?: state.storedSessionId)
        return if (state.liveSessionId == liveId) ids else ids.copy(lastSeq = NO_SEQ, localCounter = 0)
    }

    fun onConnectionLost(state: ChatState): ChatState =
        state.copy(items = finalizeStreaming(state.items), status = AgentStatus.Offline)

    fun onConnectionRestored(state: ChatState): ChatState =
        if (state.status == AgentStatus.Offline) state.copy(status = AgentStatus.Idle) else state

    fun humanName(tool: String): String = when (tool) {
        "terminal" -> "shell command"
        "execute_code" -> "code"
        "web_search" -> "web search"
        "web_extract" -> "web page"
        "delegate_task" -> "subagent"
        else -> tool.replace('_', ' ')
    }

    // ---- events -------------------------------------------------------------------------------

    private fun reduceEvent(state: ChatState, ev: Frame.Event, flair: () -> String): ChatState {
        if (!isForUs(state, ev.sessionId)) return state
        // Only events bound to the live session carry a comparable seq; replays at or below the
        // watermark are dropped. Global (empty-id) events and unbound states never touch lastSeq.
        val bound = state.liveSessionId != null && ev.sessionId == state.liveSessionId
        if (bound && ev.seq != null && ev.seq <= state.lastSeq) return state
        val s = if (bound && ev.seq != null) state.copy(lastSeq = ev.seq) else state
        val p = ev.payload
        return when (ev.type) {
            "message.start" -> onMessageStart(s, ev.seq, flair)
            "thinking.delta" -> s.copy(status = AgentStatus.Thinking(flair()))
            "reasoning.delta" -> {
                val text = p.string("text").orEmpty()
                val (withItem, current) = ensureStreamingAssistant(s, ev.seq)
                withItem.copy(
                    items = withItem.items.replace(current) { current.copy(reasoning = (current.reasoning ?: "") + text, reasoningLive = true) },
                    status = AgentStatus.Thinking(flair()),
                )
            }
            "message.interim" -> onInterim(s, p, ev.seq)
            "tool.generating" -> s.copy(status = AgentStatus.Tool(humanName(p.string("name") ?: "tool")))
            "tool.start" -> onToolStart(s, p)
            "tool.complete" -> onToolComplete(s, p, flair)
            "message.delta" -> {
                val text = p.string("text").orEmpty()
                val (withItem, current) = ensureStreamingAssistant(s, ev.seq)
                withItem.copy(
                    items = withItem.items.replace(current) { current.copy(text = current.text + text) },
                    status = AgentStatus.Replying,
                )
            }
            "message.complete" -> withUsage(
                if (p.string("status") == "error" && p.string("text").isNullOrEmpty()) {
                    // A failed turn with nothing to show: end it as an error, not as a silent empty reply.
                    turnFailed(s, ev.seq, p.string("error") ?: p.string("message"))
                } else {
                    withPersistedTurn(onMessageComplete(s, p, ev.seq), p["persisted_turn"] as? JsonObject)
                },
                p["usage"],
            )
            "session.usage" -> withUsage(s, p["usage"])
            "session.info" -> onSessionInfo(s, p)
            "tool.output_risk" -> onOutputRisk(s, p)
            "session.resume_progress" -> s.copy(resumeProgress = resumeProgressText(p) ?: s.resumeProgress)
            // `error` is what the gateway emits (server.py, methods_prompt.py); `turn.error` is kept for older ones.
            "turn.error", "error" -> turnFailed(s, ev.seq, p.string("message") ?: "unknown error")
            "session.title" -> s.copy(
                title = p.string("title") ?: s.title,
                storedSessionId = p.string("session_id")?.takeIf { it.isNotBlank() } ?: s.storedSessionId,
            )
            "request.cancel" -> {
                val id = p.string("id") ?: return s
                val card = s.items.firstOrNull { it.id == id && it.isRequest() } ?: return s
                val items = s.items - card
                s.copy(items = items, status = statusAfterApproval(s.status, items, flair))
            }
            "approval.cancelled" -> {
                val ids = (p["request_ids"] as? JsonArray).orEmpty()
                    .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.toSet()
                val items = s.items.filterNot { it is ChatItem.Approval && it.requestId in ids }
                if (items.size == s.items.size) s
                else s.copy(items = items, status = statusAfterApproval(s.status, items, flair))
            }
            "btw.complete" -> onSideComplete(s, SideKind.Question, p)
            "background.complete" -> onSideComplete(s, SideKind.Background, p)
            else -> if (ev.type.startsWith(SUBAGENT_PREFIX)) onSubagent(s, ev.type, p) else s
        }
    }

    /**
     * The `session.info` snapshot (event, or the `info` of `session.create` / `resume`): its token counters and
     * reasoning effort. A lazy session's info has neither yet, which keeps what is known.
     */
    fun onSessionInfo(state: ChatState, info: JsonObject): ChatState {
        val effort = if ("reasoning_effort" in info) info.string("reasoning_effort").orEmpty() else state.reasoningEffort
        return withUsage(state.copy(reasoningEffort = effort), info["usage"])
    }

    /** [state] with the counters of a `usage` object; an empty or absent one changes nothing. */
    fun withUsage(state: ChatState, usage: JsonElement?): ChatState {
        val u = usage as? JsonObject ?: return state
        fun long(key: String) = (u[key] as? JsonPrimitive)?.longOrNull
        val parsed = SessionUsage(
            contextPercent = (u["context_percent"] as? JsonPrimitive)?.intOrNull,
            contextUsed = long("context_used"),
            contextMax = long("context_max"),
            totalTokens = long("total"),
        )
        return if (parsed == SessionUsage()) state else state.copy(usage = parsed)
    }

    /** `tool.output_risk {tool_id, risk, findings, redacted}`: flags the tool row it names. */
    private fun onOutputRisk(s: ChatState, p: JsonObject): ChatState {
        val toolId = p.string("tool_id") ?: return s
        val row = s.items.firstOrNull { it.id == toolId } as? ChatItem.ToolCall ?: return s
        val findings = (p["findings"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        val risk = ToolRisk(p.string("risk") ?: "low", findings, p.bool("redacted") == true)
        return s.copy(items = s.items.replace(row) { row.copy(risk = risk) })
    }

    /**
     * `subagent.*`: one row per delegated child, keyed by `subagent_id` (else delegation id + task index), updated
     * in place. Children may run in the background after the turn ended, so the agent's status is left alone.
     */
    private fun onSubagent(s: ChatState, type: String, p: JsonObject): ChatState {
        val event = type.removePrefix(SUBAGENT_PREFIX)
        if (event == "text") return s // the child's per-token reply: only for a watch window
        val index = (p["task_index"] as? JsonPrimitive)?.intOrNull ?: 0
        val count = (p["task_count"] as? JsonPrimitive)?.intOrNull ?: 1
        val key = p.string("subagent_id") ?: p.string("delegation_id")?.let { "$it-$index" } ?: return s
        val id = "sub-$key"
        val existing = s.items.firstOrNull { it.id == id } as? ChatItem.Subagent
        val status = when {
            event != "complete" -> existing?.status?.takeIf { it != SubagentStatus.Running } ?: SubagentStatus.Running
            p.string("status")?.lowercase() in SUBAGENT_FAILED -> SubagentStatus.Failed
            else -> SubagentStatus.Done
        }
        val text = (p.string("tool_preview") ?: p.string("text"))?.takeIf { it.isNotBlank() && event != "complete" }
        val row = ChatItem.Subagent(
            id = id,
            goal = p.string("goal") ?: existing?.goal.orEmpty(),
            status = status,
            detail = text ?: existing?.detail,
            summary = p.string("summary") ?: existing?.summary,
            position = if (count > 1) "${index + 1}/$count" else null,
        )
        val items = if (existing != null) s.items.replace(existing) { row } else s.items + row
        return s.copy(items = items)
    }

    /**
     * `btw.complete` / `background.complete {task_id, text, question?}`: completes the note of `task_id`, or appends a
     * completed one for a task this app did not start (another client). An empty answer reads "No answer". The status
     * is unchanged.
     */
    private fun onSideComplete(s: ChatState, kind: SideKind, p: JsonObject): ChatState {
        val id = sideId(p.string("task_id")?.takeIf { it.isNotBlank() } ?: return s)
        val existing = s.items.firstOrNull { it.id == id } as? ChatItem.SideNote
        val question = p.string("question")?.takeIf { it.isNotBlank() } ?: existing?.question
        val text = p.string("text")?.takeIf { it.isNotBlank() } ?: "No answer"
        val done = ChatItem.SideNote(id, existing?.kind ?: kind, question, text, pending = false)
        return s.copy(items = if (existing != null) s.items.replace(existing) { done } else s.items + done)
    }

    private fun sideId(taskId: String) = "side-$taskId"

    /** "Loading history…", "Loaded 42 messages", or the failure, from `session.resume_progress`. */
    private fun resumeProgressText(p: JsonObject): String? = when (p.string("status")) {
        "loading" -> "Loading history…"
        "complete" -> (p["message_count"] as? JsonPrimitive)?.intOrNull?.let { "Loaded $it ${if (it == 1) "message" else "messages"}" }
        "failed" -> p.string("message") ?: "Couldn’t load the history"
        else -> null
    }

    /** Abnormal turn end: [abortTurn], then "Something went wrong[: reason]" as the reply. */
    private fun turnFailed(s: ChatState, seq: Long?, reason: String?): ChatState {
        val (counted, id) = nextId(s, seq, "e")
        val text = if (reason.isNullOrBlank()) "Something went wrong" else "Something went wrong: $reason"
        return counted.copy(
            items = abortTurn(counted.items) + ChatItem.AssistantMessage(id, text, streaming = false, reasoning = null),
            status = AgentStatus.Idle,
        )
    }

    /**
     * `message.interim {text, already_streamed}`: commentary the agent wrote beside its tool calls. When it was
     * not streamed as `message.delta`s it becomes a completed reply of its own, closing the streaming bubble, so
     * the next tool or reply lands below it. Already-streamed text is in the bubble already: ignored.
     */
    private fun onInterim(s: ChatState, p: JsonObject, seq: Long?): ChatState {
        if ((p["already_streamed"] as? JsonPrimitive)?.booleanOrNull == true) return s
        val text = p.string("text")?.takeIf { it.isNotBlank() } ?: return s
        val live = liveAssistant(s.items)
        if (live != null && live.text.isEmpty()) {
            // A reasoning-only bubble: the interim text is what that reasoning led to.
            return s.copy(items = s.items.replace(live) { live.copy(text = text, streaming = false) })
        }
        val (counted, id) = nextId(s, seq, "i")
        return counted.copy(items = finalizeStreaming(counted.items) + ChatItem.AssistantMessage(id, text, streaming = false, reasoning = null))
    }

    private fun onMessageStart(s: ChatState, seq: Long?, flair: () -> String): ChatState {
        val (counted, id) = nextId(s, seq, "a")
        val status = s.status as? AgentStatus.Thinking ?: AgentStatus.Thinking(flair())
        return counted.copy(
            items = finalizeStreaming(counted.items).filterNot { it is ChatItem.Thinking } +
                ChatItem.AssistantMessage(id, text = "", streaming = true, reasoning = null),
            status = status,
        )
    }

    private fun onMessageComplete(s: ChatState, p: JsonObject, seq: Long?): ChatState {
        val final = p.string("text").orEmpty()
        val current = liveAssistant(s.items)
        if (current != null) {
            val items = s.items.replace(current) { current.copy(text = final.ifEmpty { current.text }, streaming = false) }
            return s.copy(items = items, status = AgentStatus.Idle)
        }
        // The streaming bubble (if any) sits above a tool/approval: close it and put the reply below.
        if (final.isEmpty()) return s.copy(items = finalizeStreaming(s.items), status = AgentStatus.Idle)
        val (opened, fresh) = ensureStreamingAssistant(s, seq)
        return opened.copy(
            items = opened.items.replace(fresh) { fresh.copy(text = final, streaming = false) },
            status = AgentStatus.Idle,
        )
    }

    /**
     * `message.complete.persisted_turn`: the turn's committed rows. `user_row_id` goes to the last user message when
     * it has none yet; `final_assistant_row_id` to the last assistant message only when it sits below that user
     * message (the reply of this turn, not an older one when the turn ended without text). They let a live turn
     * be edited or regenerated without reloading the history.
     */
    private fun withPersistedTurn(s: ChatState, turn: JsonObject?): ChatState {
        if (turn == null) return s
        fun long(key: String) = (turn[key] as? JsonPrimitive)?.longOrNull
        val items = s.items.toMutableList()
        val userIndex = items.indexOfLast { it is ChatItem.UserMessage }
        val user = items.getOrNull(userIndex) as? ChatItem.UserMessage
        val userRow = long("user_row_id")
        if (user != null && userRow != null && user.rowId == null && !user.failed) items[userIndex] = user.copy(rowId = userRow)
        val replyIndex = items.indexOfLast { it is ChatItem.AssistantMessage }
        val reply = items.getOrNull(replyIndex) as? ChatItem.AssistantMessage
        val replyRow = long("final_assistant_row_id")
        if (reply != null && replyRow != null && replyIndex > userIndex && !reply.streaming) items[replyIndex] = reply.copy(rowId = replyRow)
        return if (items == s.items) s else s.copy(items = items)
    }

    private fun onToolStart(s: ChatState, p: JsonObject): ChatState {
        val name = p.string("name") ?: "tool"
        val toolId = p.string("tool_id") ?: return s.copy(status = AgentStatus.Tool(humanName(name)))
        val running = ChatItem.ToolCall(
            id = toolId,
            name = name,
            context = p.string("context"),
            args = p["args"] as? JsonObject,
            status = ToolStatus.Running,
            output = null,
            exitCode = null,
            durationS = null,
        )
        val existing = s.items.firstOrNull { it.id == toolId }
        val items = if (existing != null) s.items.replace(existing) { running } else s.items + running
        return s.copy(items = items, status = AgentStatus.Tool(humanName(name)))
    }

    private fun onToolComplete(s: ChatState, p: JsonObject, flair: () -> String): ChatState {
        val toolId = p.string("tool_id") ?: return s
        val result = p["result"] as? JsonObject
        val exitCode = result?.get("exit_code")?.primitive()?.intOrNull
        val error = result?.get("error")?.takeIf { it !is JsonNull }
        val failed = (exitCode != null && exitCode != 0) || error != null
        val existing = s.items.firstOrNull { it.id == toolId } as? ChatItem.ToolCall
        val base = existing ?: ChatItem.ToolCall(
            id = toolId,
            name = p.string("name") ?: "tool",
            context = p.string("context"),
            args = p["args"] as? JsonObject,
            status = ToolStatus.Running,
            output = null,
            exitCode = null,
            durationS = null,
        )
        val done = base.copy(
            status = if (failed) ToolStatus.Failed else ToolStatus.Done,
            output = result?.get("output")?.asText(),
            exitCode = exitCode,
            durationS = p["duration_s"]?.primitive()?.doubleOrNull,
        )
        val items = if (existing != null) s.items.replace(existing) { done } else s.items + done
        return s.copy(items = items, status = AgentStatus.Thinking(flair()))
    }

    // ---- server requests ----------------------------------------------------------------------

    private fun reduceServerRequest(state: ChatState, req: Frame.ServerRequest): ChatState {
        val secret = SecretKind.of(req.method)
        if (req.method != APPROVAL && req.method != CLARIFY && secret == null) return state
        if (!isForUs(state, req.params.string("session_id").orEmpty())) return state
        if (state.items.any { it.id == req.id }) return state
        if (secret != null) {
            val subject = when (secret) {
                SecretKind.Sudo -> req.params.string("command")
                SecretKind.Secret -> req.params.string("env_var")
                SecretKind.VaultUnlock -> req.params.string("display_name") ?: req.params.string("backend")
                SecretKind.VaultCode -> req.params.string("site")
                SecretKind.VaultSaveLogin -> req.params.string("site")?.takeIf { it.isNotBlank() } ?: req.params.string("origin")
            }
            val prompt = when (secret) {
                SecretKind.VaultCode -> req.params.string("hint")
                SecretKind.VaultSaveLogin -> null
                else -> req.params.string("prompt")
            }
            val card = ChatItem.SecretPrompt(
                id = req.id,
                kind = secret,
                subject = subject.orEmpty(),
                prompt = prompt?.takeIf { it.isNotBlank() },
                answered = false,
                origin = if (secret == SecretKind.VaultSaveLogin) req.params.string("origin")?.takeIf { it.isNotBlank() } else null,
            )
            return state.copy(items = state.items + card, status = statusFor(state.items + card) ?: AgentStatus.Secret(secret))
        }
        if (req.method == CLARIFY) {
            val questions = ClarifyAnswers.questions(req.params).takeIf { it.isNotEmpty() } ?: return state
            val card = ChatItem.Clarify(id = req.id, questions = questions, answered = false)
            return state.copy(items = state.items + card, status = statusFor(state.items + card) ?: AgentStatus.Question)
        }
        // The server lists the offered scopes in `choices`; without it, derive them as it does
        // (tui_gateway/server.py `_approval_request_payload`).
        val choices = (req.params["choices"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        val smartDenied = req.params.bool("smart_denied") == true
        val allowSession = choices?.contains("session") ?: (!smartDenied && req.params.bool("allow_session") != false)
        val allowAlways = choices?.contains("always") ?: (allowSession && req.params.bool("allow_permanent") != false)
        val card = ChatItem.Approval(
            id = req.id,
            requestId = req.params.string("request_id").orEmpty(),
            command = req.params.string("command").orEmpty(),
            toolName = req.params.string("tool_name").orEmpty(),
            answered = false,
            allowSession = allowSession,
            allowAlways = allowAlways,
        )
        return state.copy(items = state.items + card, status = statusFor(state.items + card) ?: AgentStatus.Approval)
    }

    // ---- helpers ------------------------------------------------------------------------------

    private fun isForUs(state: ChatState, sessionId: String): Boolean =
        state.liveSessionId == null || sessionId.isEmpty() || sessionId == state.liveSessionId

    /** Approval while an approval waits, else Secret while a masked prompt waits, else Question for a clarify, else null. */
    private fun statusFor(items: List<ChatItem>): AgentStatus? {
        if (items.any { it is ChatItem.Approval && !it.answered }) return AgentStatus.Approval
        items.lastOrNull { it is ChatItem.SecretPrompt && !it.answered }?.let {
            return AgentStatus.Secret((it as ChatItem.SecretPrompt).kind)
        }
        if (items.any { it is ChatItem.Clarify && !it.answered }) return AgentStatus.Question
        return null
    }

    /** After an approval, clarify or masked prompt is answered or withdrawn: keep waiting while others are pending. */
    private fun statusAfterApproval(current: AgentStatus, items: List<ChatItem>, flair: () -> String): AgentStatus =
        statusFor(items) ?: if (current.waiting) AgentStatus.Thinking(flair()) else current

    private val AgentStatus.waiting: Boolean
        get() = this == AgentStatus.Approval || this == AgentStatus.Question || this is AgentStatus.Secret

    /** A card answering a server request: approval, clarify or masked prompt. */
    private fun ChatItem.isRequest(): Boolean =
        this is ChatItem.Approval || this is ChatItem.Clarify || this is ChatItem.SecretPrompt

    /**
     * The streaming assistant item that new text may still flow into: the last streaming one,
     * provided no tool call or request card (approval, clarify, masked prompt) was appended after it (that would reorder the turn).
     * Side notes and subagent rows run detached from the turn and never block it.
     */
    private fun liveAssistant(items: List<ChatItem>): ChatItem.AssistantMessage? {
        val index = items.indexOfLast { it is ChatItem.AssistantMessage && it.streaming }
        if (index < 0) return null
        val blocked = items.subList(index + 1, items.size).any { it is ChatItem.ToolCall || it.isRequest() }
        return if (blocked) null else items[index] as ChatItem.AssistantMessage
    }

    /**
     * The item new assistant output goes into. When the streaming bubble is blocked by a later tool
     * or approval, it is closed and a fresh one is appended below. A blocked bubble with no text
     * (reasoning only) is removed and its reasoning carried into the fresh one, so the live order
     * matches history (User, Tool, Assistant) without losing the reasoning.
     */
    private fun ensureStreamingAssistant(s: ChatState, seq: Long?): Pair<ChatState, ChatItem.AssistantMessage> {
        liveAssistant(s.items)?.let { return s to it }
        val stale = s.items.lastOrNull { it is ChatItem.AssistantMessage && it.streaming } as ChatItem.AssistantMessage?
        val carried = stale?.takeIf { it.text.isEmpty() }?.reasoning
        val remaining = if (carried != null) s.items - stale else s.items
        val (counted, id) = nextId(s.copy(items = finalizeStreaming(remaining)), seq, "a")
        val item = ChatItem.AssistantMessage(id, text = "", streaming = true, reasoning = carried, reasoningLive = carried != null && stale!!.reasoningLive)
        // The reasoning moved to a new id: the user's open/closed choice for it moves along.
        val choice = if (carried != null) s.reasoningOpen[stale!!.id] else null
        val choices = if (choice != null) counted.reasoningOpen - stale!!.id + (id to choice) else counted.reasoningOpen
        return counted.copy(items = counted.items + item, reasoningOpen = choices) to item
    }

    /** Whether [item]'s reasoning shows open: the user's choice, else open while it streamed in this session. */
    fun reasoningOpen(state: ChatState, item: ChatItem.AssistantMessage): Boolean =
        state.reasoningOpen[item.id] ?: item.reasoningLive

    /** The user tapped the reasoning header of [id]: flips it from whatever it currently shows. Never auto-closes. */
    fun toggleReasoning(state: ChatState, id: String): ChatState {
        val item = state.items.firstOrNull { it.id == id } as? ChatItem.AssistantMessage ?: return state
        return state.copy(reasoningOpen = state.reasoningOpen + (id to !reasoningOpen(state, item)))
    }

    /** Abnormal turn end: close streams, fail running tools, drop approvals, questions and prompts nobody answered. */
    private fun abortTurn(items: List<ChatItem>): List<ChatItem> =
        finalizeStreaming(items).mapNotNull { item ->
            when {
                item is ChatItem.ToolCall && item.status == ToolStatus.Running -> item.copy(status = ToolStatus.Failed)
                item is ChatItem.Approval && !item.answered -> null
                item is ChatItem.Clarify && !item.answered -> null
                item is ChatItem.SecretPrompt && !item.answered -> null
                else -> item
            }
        }

    /** Ends every in-flight assistant item, dropping ones that never received any content. */
    private fun finalizeStreaming(items: List<ChatItem>): List<ChatItem> =
        items.mapNotNull { item ->
            when {
                item !is ChatItem.AssistantMessage || !item.streaming -> item
                item.text.isEmpty() && item.reasoning.isNullOrEmpty() -> null
                else -> item.copy(streaming = false)
            }
        }

    /** `"<prefix>-<seq>"` when the event has a seq, else `"<prefix>-l<n>"` from the local counter. */
    private fun nextId(s: ChatState, seq: Long?, prefix: String): Pair<ChatState, String> {
        if (seq != null && s.items.none { it.id == "$prefix-$seq" }) return s to "$prefix-$seq"
        val n = s.localCounter + 1
        return s.copy(localCounter = n) to "$prefix-l$n"
    }

    private inline fun <T : ChatItem> List<ChatItem>.replace(target: T, transform: () -> ChatItem): List<ChatItem> =
        map { if (it === target) transform() else it }

    private fun JsonElement.primitive(): JsonPrimitive? = this as? JsonPrimitive

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull

    private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

    private fun JsonElement.asText(): String? = when (this) {
        is JsonNull -> null
        is JsonPrimitive -> contentOrNull
        else -> toString()
    }
}
