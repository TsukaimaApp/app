package labs.suchattai.tsukaima.domain.chat

import kotlinx.serialization.json.JsonObject

enum class ToolStatus { Running, Done, Failed }

/** One row of the chat transcript. */
sealed interface ChatItem {
    val id: String

    /**
     * [attachments]: names of the files sent with the message (local echo only; history shows the text).
     * [failed]: the local echo of a send that did not reach Hermes; the bubble offers Retry and Dismiss.
     */
    data class UserMessage(
        override val id: String,
        val text: String,
        val attachments: List<String> = emptyList(),
        val failed: Boolean = false,
    ) : ChatItem

    data class AssistantMessage(
        override val id: String,
        val text: String,
        val streaming: Boolean,
        val reasoning: String?,
        /** The reasoning was streamed in this session (shown open unless the user closed it); history loads closed. */
        val reasoningLive: Boolean = false,
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
     * A `sudo`, `secret` or `vault.unlock_prompt` server request: the agent wants a masked value. [id] is the
     * server-request id (`srq-…`) to answer; [subject] is what the value is for (the command, the env var or the
     * password manager's name; agent-controlled), [prompt] the `secret` request's own wording. The value itself is
     * never stored here: once [answered], [declined] says whether an empty value (skip) was sent.
     */
    data class SecretPrompt(
        override val id: String,
        val kind: SecretKind,
        val subject: String,
        val prompt: String?,
        val answered: Boolean,
        val declined: Boolean = false,
    ) : ChatItem

    /** Placeholder "agent is working" row; replaced by the assistant message on `message.start`. */
    data class Thinking(override val id: String) : ChatItem
}

enum class SubagentStatus { Running, Done, Failed }

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
    val totalTokens: Long? = null,
) {
    /** "42% ctx · 12.3k tok", either half alone when the other is unknown; null when neither is. */
    val meter: String?
        get() = listOfNotNull(contextPercent?.let { "$it% ctx" }, totalTokens?.takeIf { it > 0 }?.let { "${compactTokens(it)} tok" })
            .takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

/** 950, 12.3k, 1.2M. */
fun compactTokens(n: Long): String = when {
    n >= 1_000_000 -> String.format(java.util.Locale.US, "%.1fM", n / 1_000_000.0)
    n >= 1_000 -> String.format(java.util.Locale.US, "%.1fk", n / 1_000.0)
    else -> n.toString()
}

/** Which masked-value request a [ChatItem.SecretPrompt] answers; [method] is its server-request method. */
enum class SecretKind(val method: String) {
    Sudo("sudo"),
    Secret("secret"),
    VaultUnlock("vault.unlock_prompt"),
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
)

/** [ChatState.lastSeq] before any event of the live session was applied; `session.events.since` accepts it. */
const val NO_SEQ = -1L
