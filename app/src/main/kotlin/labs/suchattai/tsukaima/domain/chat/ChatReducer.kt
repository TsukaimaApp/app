package labs.suchattai.tsukaima.domain.chat

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import labs.suchattai.tsukaima.data.gateway.Frame
import labs.suchattai.tsukaima.data.gateway.TranscriptMessage

/**
 * Pure fold of gateway frames and local UI actions into [ChatState].
 *
 * The only impurity is the thinking flair, injected as `flair` so tests are deterministic.
 */
object ChatReducer {
    private const val APPROVAL = "approval"

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
                "user" -> ChatItem.UserMessage(id, m.text)
                "assistant" ->
                    if (m.text.isBlank()) null
                    else ChatItem.AssistantMessage(id, m.text, streaming = false, reasoning = m.reasoning)
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
    ): ChatState = state.copy(
        items = state.items + ChatItem.UserMessage(localId, text),
        status = AgentStatus.Thinking(flair()),
    )

    /** Marks approval [id] answered; idempotent so a double tap sends one answer. */
    fun onApprovalAnswered(state: ChatState, id: String, flair: () -> String = ThinkingFlair::next): ChatState {
        val card = state.items.firstOrNull { it.id == id } as? ChatItem.Approval
        if (card == null || card.answered) return state
        val items = state.items.map { if (it === card) card.copy(answered = true) else it }
        return state.copy(items = items, status = statusAfterApproval(state.status, items, flair))
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
                    items = withItem.items.replace(current) { current.copy(reasoning = (current.reasoning ?: "") + text) },
                    status = AgentStatus.Thinking(flair()),
                )
            }
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
            "message.complete" -> onMessageComplete(s, p, ev.seq)
            "turn.error" -> {
                val message = p.string("message") ?: "unknown error"
                val (counted, id) = nextId(s, ev.seq, "e")
                counted.copy(
                    items = abortTurn(counted.items) +
                        ChatItem.AssistantMessage(id, "Something went wrong: $message", streaming = false, reasoning = null),
                    status = AgentStatus.Idle,
                )
            }
            "session.title" -> s.copy(
                title = p.string("title") ?: s.title,
                storedSessionId = p.string("session_id")?.takeIf { it.isNotBlank() } ?: s.storedSessionId,
            )
            "request.cancel" -> {
                val id = p.string("id") ?: return s
                val card = s.items.firstOrNull { it.id == id } as? ChatItem.Approval ?: return s
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
            else -> s
        }
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
        if (req.method != APPROVAL) return state
        if (!isForUs(state, req.params.string("session_id").orEmpty())) return state
        if (state.items.any { it.id == req.id }) return state
        val card = ChatItem.Approval(
            id = req.id,
            requestId = req.params.string("request_id").orEmpty(),
            command = req.params.string("command").orEmpty(),
            toolName = req.params.string("tool_name").orEmpty(),
            answered = false,
        )
        return state.copy(items = state.items + card, status = AgentStatus.Approval)
    }

    // ---- helpers ------------------------------------------------------------------------------

    private fun isForUs(state: ChatState, sessionId: String): Boolean =
        state.liveSessionId == null || sessionId.isEmpty() || sessionId == state.liveSessionId

    /** After an approval is answered or withdrawn: stay on Approval while others are pending. */
    private fun statusAfterApproval(current: AgentStatus, items: List<ChatItem>, flair: () -> String): AgentStatus =
        when {
            items.any { it is ChatItem.Approval && !it.answered } -> AgentStatus.Approval
            current == AgentStatus.Approval -> AgentStatus.Thinking(flair())
            else -> current
        }

    /**
     * The streaming assistant item that new text may still flow into: the last streaming one,
     * provided no tool call or approval card was appended after it (that would reorder the turn).
     */
    private fun liveAssistant(items: List<ChatItem>): ChatItem.AssistantMessage? {
        val index = items.indexOfLast { it is ChatItem.AssistantMessage && it.streaming }
        if (index < 0) return null
        val blocked = items.subList(index + 1, items.size).any { it is ChatItem.ToolCall || it is ChatItem.Approval }
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
        val item = ChatItem.AssistantMessage(id, text = "", streaming = true, reasoning = carried)
        return counted.copy(items = counted.items + item) to item
    }

    /** Abnormal turn end: close streams, fail running tools, drop approvals nobody answered. */
    private fun abortTurn(items: List<ChatItem>): List<ChatItem> =
        finalizeStreaming(items).mapNotNull { item ->
            when {
                item is ChatItem.ToolCall && item.status == ToolStatus.Running -> item.copy(status = ToolStatus.Failed)
                item is ChatItem.Approval && !item.answered -> null
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

    private fun JsonElement.asText(): String? = when (this) {
        is JsonNull -> null
        is JsonPrimitive -> contentOrNull
        else -> toString()
    }
}
