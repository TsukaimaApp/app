package labs.suchattai.tsukaima.ui.chat

import labs.suchattai.tsukaima.data.api.ModelOptionsDto
import labs.suchattai.tsukaima.data.api.WorkspacesDto
import labs.suchattai.tsukaima.domain.chat.AgentStatus
import labs.suchattai.tsukaima.domain.chat.ChatState

enum class ChatMenu { Model, Workspace, Overflow }
enum class ChatDialog { Rename, DeleteConfirm }

data class ChatUiState(
    val chat: ChatState = ChatState(),
    val loading: Boolean = false,
    val input: String = "",
    val models: ModelOptionsDto? = null,
    val workspaces: WorkspacesDto? = null,
    val workspace: String? = null,
    val menu: ChatMenu? = null,
    val dialog: ChatDialog? = null,
    /** Server-level status from [labs.suchattai.tsukaima.data.HermesState.gatewayStatus]; wins over the chat's. */
    val gatewayStatus: AgentStatus? = null,
    val model: String? = null,
    val provider: String? = null,
    /** Approve (true) / deny (false) per answered approval id, for the card's outcome label. */
    val decisions: Map<String, Boolean> = emptyMap(),
    /** Opened as a new chat (no stored session id in the route). */
    val isNew: Boolean = true,
) {
    /** What the status line shows. */
    val status: AgentStatus get() = gatewayStatus ?: chat.status

    /** Server unreachable or socket down: history stays visible, the composer is disabled. */
    val offline: Boolean get() = gatewayStatus == AgentStatus.Offline || chat.status == AgentStatus.Offline

    /** A new chat with nothing in it yet (shows the working-directory picker). */
    val empty: Boolean get() = isNew && chat.items.isEmpty() && !loading
}
