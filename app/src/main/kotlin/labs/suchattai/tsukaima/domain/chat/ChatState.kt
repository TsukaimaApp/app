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

    /** Placeholder "agent is working" row; replaced by the assistant message on `message.start`. */
    data class Thinking(override val id: String) : ChatItem
}

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
