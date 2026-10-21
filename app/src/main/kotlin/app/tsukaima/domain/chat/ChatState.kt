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
    ) : ChatItem

    data class AssistantMessage(
        override val id: String,
        val text: String,
        val streaming: Boolean,
        val reasoning: String?,
        /** The reasoning was streamed in this session (shown open unless the user closed it); history loads closed. */
        val reasoningLive: Boolean = false,
        /** The persisted row (history `row_id`, or the turn's `final_assistant_row_id`); null until known. */
        val rowId: Long? = null,
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
     * (`all_resolved`, `continue`, `deadline`, `interrupt`) once [settled].
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
    ) : ChatItem

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

    /** Placeholder "agent is working" row; replaced by the assistant message on `message.start`. */
    data class Thinking(override val id: String) : ChatItem
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
    /** A turn ended while a [ChatItem.UserMessage.queued] echo waited: the next turn to start is that echo's. */
    val queuedTurnNext: Boolean = false,
    /**
     * The app sent Stop (`session.interrupt`, which purges the server's queue): the next turn end, of any kind, fails
     * the queued echoes.
     */
    val stopRequested: Boolean = false,
)

/** [ChatState.lastSeq] before any event of the live session was applied; `session.events.since` accepts it. */
const val NO_SEQ = -1L

/**
 * A typed send now goes behind the live turn (`prompt.submit {queued: true}`): while a turn is live, and between turns
 * while a queued message still waits (the server is about to run it). The composer then offers "Send when done".
 */
val ChatState.queuesSends: Boolean
    get() = status.turnLive || items.any { it is ChatItem.UserMessage && it.queued }
