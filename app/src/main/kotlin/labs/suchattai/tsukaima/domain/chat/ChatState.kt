package labs.suchattai.tsukaima.domain.chat

import kotlinx.serialization.json.JsonObject

enum class ToolStatus { Running, Done, Failed }

/** One row of the chat transcript. */
sealed interface ChatItem {
    val id: String

    data class UserMessage(override val id: String, val text: String) : ChatItem

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

    /** An `approval` server request; [id] is the server-request id (`srq-…`) to answer. */
    data class Approval(
        override val id: String,
        val requestId: String,
        val command: String,
        val toolName: String,
        val answered: Boolean,
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

    /** Placeholder "agent is working" row; replaced by the assistant message on `message.start`. */
    data class Thinking(override val id: String) : ChatItem
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
    val lastSeq: Long = 0,
    /** Monotonic counter for reducer-generated ids when an event carries no `seq`. */
    val localCounter: Long = 0,
)
