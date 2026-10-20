package labs.suchattai.tsukaima.domain.chat

import kotlinx.serialization.json.JsonObject

enum class ToolStatus { Running, Done, Failed }

/** One row of the chat transcript. */
sealed interface ChatItem {
    val id: String

    /** [attachments]: names of the files sent with the message (local echo only; history shows the text). */
    data class UserMessage(override val id: String, val text: String, val attachments: List<String> = emptyList()) : ChatItem

    data class AssistantMessage(
        override val id: String,
        val text: String,
        val streaming: Boolean,
        val reasoning: String?,
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
)

/** [ChatState.lastSeq] before any event of the live session was applied; `session.events.since` accepts it. */
const val NO_SEQ = -1L
