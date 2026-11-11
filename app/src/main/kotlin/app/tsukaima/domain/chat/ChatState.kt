package app.tsukaima.domain.chat

import kotlinx.serialization.json.JsonObject

/**
 * A tool row's state. [Interrupted] is distinct from [Failed]: the tool was stopped (Stop, a killed terminal command:
 * exit 130 with `[Command interrupted]`) or Hermes recovered it as an orphan; it may have partially run.
 */
enum class ToolStatus { Running, Done, Failed, Interrupted }

/** One row of the chat transcript. */
sealed interface ChatItem {
    val id: String

    /**
     * [attachments]: names of the files sent with the message (local echo only; history shows the text).
     * [failed]: the local echo of a send that did not reach Hermes; the bubble offers Retry and Dismiss.
     * [rowId]: the server's persisted row (history `row_id`, or `user_row_id` once a send was accepted); null
     * until known. Edit and Regenerate rewind the transcript to it.
     * [promptText]: what a live send actually submitted when it differs from [text] (attachment `@file:` refs, the
     * image placeholder); null = [text]. Regenerate replays it. [imagesSent]: the send queued images, which the turn
     * consumed on the server, so it cannot be replayed as it was. [queued]: sent while a turn ran, the server holds it
     * until that turn ends (`prompt.submit {queued: true}`); it shows "Queued" and stays below the live turn.
     * [steer]: a correction sent into the running turn (`session.steer`; history `display_kind: "steer"`). The agent
     * reads it after its current tool call. It sits inside the turn, where it was sent, and is never a turn's first
     * message. [steerWaiting]: no tool has finished since it was sent, so the agent has not read it yet; a turn that
     * ends first leaves it to the server's queue, which runs it as the next turn.
     */
    data class UserMessage(
        override val id: String,
        val text: String,
        val attachments: List<String> = emptyList(),
        val failed: Boolean = false,
        val rowId: Long? = null,
        val promptText: String? = null,
        val imagesSent: Boolean = false,
        val queued: Boolean = false,
        val steer: Boolean = false,
        val steerWaiting: Boolean = false,
        /** When it was sent, epoch millis (the phone's clock for a live send, the row's `timestamp` from history); null when unknown. */
        val at: Long? = null,
    ) : ChatItem

    data class AssistantMessage(
        override val id: String,
        val text: String,
        val streaming: Boolean,
        val reasoning: String?,
        /** The reasoning was streamed in this session; history's was not. */
        val reasoningLive: Boolean = false,
        /** The persisted row (history `row_id`, or the turn's `final_assistant_row_id`); null until known. */
        val rowId: Long? = null,
        /**
         * The model that wrote this reply when it was not the session's own (`message.complete.usage.model`: a
         * fallback served the turn); null otherwise, and for a reply loaded from history, which carries no model.
         */
        val model: String? = null,
        /** When the first reasoning of this message arrived, epoch millis; null for history and for a clockless reducer. */
        val thoughtFrom: Long? = null,
        /** How long its reasoning streamed (first to last `reasoning.delta`), millis; null when it was not timed. */
        val thoughtMs: Long? = null,
    ) : ChatItem

    data class ToolCall(
        override val id: String,
        val name: String,
        val context: String?,
        val args: JsonObject?,
        val status: ToolStatus,
        val output: String?,
        val exitCode: Int?,
        val durationS: Double?,
        /** Set by `tool.output_risk`: the output was classified risky (prompt injection, secrets). */
        val risk: ToolRisk? = null,
        /**
         * The image a `vision_analyze` call looked at: its `args.image_url` as sent (http(s), `data:`, or a path on the
         * server). Null for every other tool.
         */
        val imageUrl: String? = null,
        /**
         * Hermes' orphan-recovery notice for this call, as the app words it ("May have partially run — check before
         * retrying" or "Did not complete"); null for every other row. Set from the history only.
         */
        val orphanNote: String? = null,
        /**
         * A history row whose result the history did not carry (Hermes forwards it for three tools only): [output] is
         * not known yet, which is not "no output". The stored messages fill it when the row is first opened
         * ([ChatReducer.onToolOutputs]).
         */
        val outputPending: Boolean = false,
        /** The latest `tool.progress` of a running call (an MCP server's progress notification); null without any. */
        val progress: ToolProgress? = null,
    ) : ChatItem

    /**
     * A delegated child agent (`subagent.*` events), one row per child ([id] `sub-<subagent_id>`). [detail] is its
     * latest progress line (tool preview or text), [summary] what it reported when it finished.
     */
    data class Subagent(
        override val id: String,
        val goal: String,
        val status: SubagentStatus,
        val detail: String? = null,
        val summary: String? = null,
        /** "2/3" when the delegation ran several tasks, else null. */
        val position: String? = null,
        /** The child's `subagent_id`: what `subagent.tail` / `steer` / `interrupt` take; null when no event named it. */
        val subagentId: String? = null,
        /** The child's model and how many tools it has called so far, as its events report them. */
        val model: String? = null,
        val toolCount: Int? = null,
    ) : ChatItem

    /**
     * An `approval` server request; [id] is the server-request id (`srq-…`) to answer. [allowSession] and
     * [allowAlways] say whether the server offers the `session` / `always` scopes besides `once` and `deny`.
     */
    data class Approval(
        override val id: String,
        val requestId: String,
        val command: String,
        val toolName: String,
        val answered: Boolean,
        val allowSession: Boolean = false,
        val allowAlways: Boolean = false,
    ) : ChatItem

    /**
     * A `clarify` server request; [id] is the server-request id (`srq-…`) to answer. [answers] holds what was
     * sent once [answered] (qid → answer, null = skipped; empty after "Skip" = cancel all).
     */
    data class Clarify(
        override val id: String,
        val questions: List<ClarifyQuestion>,
        val answered: Boolean,
        val answers: Map<String, String?> = emptyMap(),
    ) : ChatItem

    /**
     * A `sudo`, `secret`, `vault.unlock_prompt`, `vault.code` or `vault.save_login` server request: the agent wants
     * a value typed by the user. [id] is the server-request id (`srq-…`) to answer; [subject] is what the value is
     * for (the command, the env var, the password manager's name, or the site; agent-controlled), [prompt] the
     * `secret` request's own wording or the `vault.code` hint. [origin] is the `vault.save_login` page's full origin
     * (scheme included; null for every other kind). The value itself is never stored here: once [answered],
     * [declined] says whether an empty value (skip) was sent.
     */
    data class SecretPrompt(
        override val id: String,
        val kind: SecretKind,
        val subject: String,
        val prompt: String?,
        val answered: Boolean,
        val declined: Boolean = false,
        val origin: String? = null,
    ) : ChatItem

    /**
     * A side question (`prompt.btw`) or background task (`prompt.background`) and its answer ([id] `side-<task_id>`).
     * Side agents run detached from the turn: the note never blocks or closes the streaming reply, and the chat's
     * status ignores it. [pending] until `btw.complete` / `background.complete` brings [text]; [question] is what was
     * asked when known (null for a completion this app did not start that carries none).
     */
    data class SideNote(
        override val id: String,
        val kind: SideKind,
        val question: String?,
        val text: String,
        val pending: Boolean,
    ) : ChatItem

    /**
     * A connection operation (`connection.request`, `manage_connections`): the agent wants accounts connected or
     * tools installed ([id] `conn-<op_id>`). Updated in place by `connection.update` frames of the same [opId]
     * (each carries the full [targets] snapshot; [seq] is the operation's own write counter, so an older frame moves
     * nothing). [owner] is the operation's owner as the server last named it (null until an update did); answers go
     * to `connection.respond`. [deadlineAt] is epoch seconds on the server clock; [settledBy] the server's reason
     * (`all_resolved`, `continue`, `deadline`, `interrupt`) once [settled]. [suggested]: a display operation's
     * `display.suggested` (it has no rows). [envSentAt]: per target, the card's [seq] when Connect sent its env ahead
     * of a link (names only, never values).
     */
    data class ConnectionRequest(
        override val id: String,
        val opId: String,
        val owner: JsonObject?,
        val deadlineAt: Double,
        val targets: List<ConnectionTarget>,
        val settled: Boolean,
        val settledBy: String?,
        val seq: Long = -1,
        val suggested: List<String> = emptyList(),
        val envSentAt: Map<String, Long> = emptyMap(),
    ) : ChatItem {
        /**
         * Whether [target] (which has both `required_env` and a `connect_url`) is past its env step: Connect sent the
         * env, and a newer frame than the one it was sent on still shows the row open with a link. Only then is the
         * link offered (as "Continue in browser").
         */
        fun continuesInBrowser(target: ConnectionTarget): Boolean {
            val sentAt = envSentAt[target.name] ?: return false
            return target.connectUrl != null && !target.resolved && seq > sentAt
        }
    }

    /**
     * The agent's task checklist (`todo.updated`, or the `todos` + `revision` the todo tool's `tool.complete` mirrors):
     * one per chat ([id] `todos`), the full snapshot of the server's revision-monotonic list. Appended where it is first
     * seen and replaced in place afterwards; not part of the history (a resume's `todo_state` restores it).
     */
    data class Todos(
        override val id: String = TODOS_ID,
        val revision: Int,
        val todos: List<TodoEntry>,
    ) : ChatItem

    /**
     * A slash command run from the composer and what the server printed for it ([id] `cmd-<n>`, local to this
     * screen: commands are not part of the history). [pending] until the answer; [failed]: [output] is the error.
     */
    data class CommandNote(
        override val id: String,
        val command: String,
        val output: String = "",
        val pending: Boolean = true,
        val failed: Boolean = false,
    ) : ChatItem

    /** Placeholder "agent is working" row; replaced by the assistant message on `message.start`. */
    data class Thinking(override val id: String) : ChatItem

    /**
     * A server diagnostic the user must see (`status.update`): a quiet system line in the transcript, not a bubble
     * and not a toast. [warning]: the server marked it (kind `warn`, or a lifecycle line starting with ⚠️), such as
     * "Model fallback: … unavailable (rate limit); using … via synthetic" — the only way the phone learns the reply
     * came from another model. [error]: a terminal failure line (❌: retries exhausted, credits gone). A notice that
     * is neither is quiet: a completed context compaction, the primary model restored. Never in history: it lives
     * for this screen's session (a rebuild carries it, see ChatReconnector).
     */
    data class Notice(override val id: String, val text: String, val warning: Boolean, val error: Boolean = false) : ChatItem
}

enum class SubagentStatus { Running, Done, Failed }

/** The id of the chat's single [ChatItem.Todos] card. */
const val TODOS_ID = "todos"

/** One task of a [ChatItem.Todos] list; [parent] is another entry's [id] in the same snapshot (a subtask), or null. */
data class TodoEntry(val id: String, val content: String, val status: TodoStatus, val parent: String? = null)

/** A todo's state as the server names it (`pending`, `in_progress`, `completed`, `cancelled`). */
enum class TodoStatus {
    Pending, InProgress, Completed, Cancelled;

    companion object {
        /** An unknown or missing status reads as [Pending]. */
        fun of(wire: String?): TodoStatus = when (wire) {
            "in_progress" -> InProgress
            "completed" -> Completed
            "cancelled" -> Cancelled
            else -> Pending
        }
    }
}

/** What a [ChatItem.SideNote] is: a side question (`prompt.btw`) or a background task (`prompt.background`). */
enum class SideKind { Question, Background }

/**
 * `tool.progress {progress, total?, percent?, message?}`: how far a running tool call is. [percent] is the server's,
 * else `progress / total`; null when the tool reports no total.
 */
data class ToolProgress(val percent: Int?, val message: String?) {
    /** "40% · Fetching page 2", "40%", "Fetching page 2"; null when there is nothing to show. */
    val label: String?
        get() = listOfNotNull(percent?.let { "$it%" }, message?.takeIf { it.isNotBlank() }).joinToString(" · ").ifEmpty { null }
}

/** `tool.output_risk`: [level] as the server rates it (`low`/`medium`/`high`), its [findings], and whether the output was [redacted]. */
data class ToolRisk(val level: String, val findings: List<String>, val redacted: Boolean)

/**
 * Token counters of the live session (`usage` of `session.info`, `session.usage`, `message.complete`).
 * [contextPercent] is how full the context window is; [totalTokens] every token the session has spent.
 */
data class SessionUsage(
    val contextPercent: Int? = null,
    val contextUsed: Long? = null,
    val contextMax: Long? = null,
    /** Hermes' `usage.total`: input+output summed over every call of the session — a billing figure, not a size. */
    val totalTokens: Long? = null,
) {
    /**
     * The context window as the next turn sees it: "16k / 1M ctx · 2%", or what is known of it ("2% ctx",
     * "16k ctx"); null when nothing is. The cumulative [totalTokens] is deliberately not shown — each call
     * re-sends the whole context, so it reads as millions in a long session and says nothing about room left.
     */
    val meter: String?
        get() {
            val percent = contextPercent ?: contextUsed?.let { u -> contextMax?.takeIf { it > 0 }?.let { m -> (u * 100 / m).toInt() } }
            val size = when {
                contextUsed != null && contextMax != null && contextMax > 0 -> "${compactTokens(contextUsed)} / ${compactTokens(contextMax)} ctx"
                contextUsed != null -> "${compactTokens(contextUsed)} ctx"
                else -> null
            }
            return when {
                size != null && percent != null -> "$size · $percent%"
                size != null -> size
                percent != null -> "$percent% ctx"
                else -> null
            }
        }

    /** "9% ctx" where the line has little room (beside the reasoning picker); [meter] when no percentage is known. */
    val shortMeter: String?
        get() = (contextPercent ?: contextUsed?.let { u -> contextMax?.takeIf { it > 0 }?.let { m -> (u * 100 / m).toInt() } })
            ?.let { "$it% ctx" } ?: meter
}

/** 950, 12.3k, 1.2M, 1M (no trailing .0). */
fun compactTokens(n: Long): String = when {
    n >= 1_000_000 -> String.format(java.util.Locale.US, "%.1fM", n / 1_000_000.0).removeSuffix(".0M") + if (n % 1_000_000 < 50_000) "M" else ""
    n >= 1_000 -> String.format(java.util.Locale.US, "%.1fk", n / 1_000.0).removeSuffix(".0k") + if (n % 1_000 < 50) "k" else ""
    else -> n.toString()
}

/** Which masked-value request a [ChatItem.SecretPrompt] answers; [method] is its server-request method. */
enum class SecretKind(val method: String) {
    Sudo("sudo"),
    Secret("secret"),
    VaultUnlock("vault.unlock_prompt"),

    /** A one-time / 2FA code for a site (`{site?, hint?}`); answered in clear text. */
    VaultCode("vault.code"),

    /** A login to save for a site (`{origin, site}`); answered with [VaultAnswers.saveLogin]. */
    VaultSaveLogin("vault.save_login"),
    ;

    companion object {
        fun of(method: String): SecretKind? = entries.firstOrNull { it.method == method }
    }
}

/** One question of a `clarify` request; [choices] null = free text. */
data class ClarifyQuestion(
    val qid: String,
    val question: String,
    val choices: List<String>?,
    val multiSelect: Boolean,
)

data class ChatState(
    val items: List<ChatItem> = emptyList(),
    val status: AgentStatus = AgentStatus.Idle,
    val liveSessionId: String? = null,
    val storedSessionId: String? = null,
    val title: String? = null,
    /** Highest seq applied for the live session; [NO_SEQ] before any (a seq of 0 must not be dropped). */
    val lastSeq: Long = NO_SEQ,
    /** Monotonic counter for reducer-generated ids when an event carries no `seq`. */
    val localCounter: Long = 0,
    /** Latest token counters; null until the server reported any. */
    val usage: SessionUsage? = null,
    /** `session.info.reasoning_effort`: `""` = the provider's default, `none` = off; null until reported. */
    val reasoningEffort: String? = null,
    /** `session.resume_progress` text while a deferred resume loads its history. */
    val resumeProgress: String? = null,
    /** The user's explicit open/closed choice for a message's reasoning, by item id; absent = the item's default. */
    val reasoningOpen: Map<String, Boolean> = emptyMap(),
    /** The folded step groups the user opened ([TranscriptRows]), by group id. */
    val openSteps: Set<String> = emptySet(),
    /** A turn ended while a [ChatItem.UserMessage.queued] echo waited: the next turn to start is that echo's. */
    val queuedTurnNext: Boolean = false,
    /**
     * The app sent Stop (`session.interrupt`, which purges the server's queue): the next turn end, of any kind, fails
     * the queued echoes.
     */
    val stopRequested: Boolean = false,
    /**
     * The highest todo revision applied for the live session, kept after a clear so a lower-revision replay (or a
     * stale resume seed) is ignored; -1 before any. A new live session has its own counter and resets it.
     */
    val todoRevision: Int = -1,
    /**
     * The server is compressing this session's context (`status.update {kind: compacting|compressing}`: an automatic
     * compaction before an API call, or a `session.compress` from any client), until its `compacted` line, the
     * `ready` status, the turn's next output, the turn end or a lost connection. Shown as [AgentStatus.Compacting] so
     * an idle-looking wait is not taken for a hung turn.
     */
    val compacting: Boolean = false,
    /** The session's own model (`session.info.model`); not the fallback's while [fallback] is set. Null until reported. */
    val model: String? = null,
    /**
     * A fallback model is serving the session ("Model fallback: …" until "Primary model restored: …"). While set,
     * `session.info` names the fallback model, which is not the session's.
     */
    val fallback: Boolean = false,
)

/** [ChatState.lastSeq] before any event of the live session was applied; `session.events.since` accepts it. */
const val NO_SEQ = -1L

/**
 * A typed send now goes behind the live turn (`prompt.submit {queued: true}`): while a turn is live, and between turns
 * while a queued message still waits (the server is about to run it). The composer then offers More (steer, queue, side question, background task) in place of Send.
 */
val ChatState.queuesSends: Boolean
    get() = status.turnLive || items.any { it is ChatItem.UserMessage && it.queued }
