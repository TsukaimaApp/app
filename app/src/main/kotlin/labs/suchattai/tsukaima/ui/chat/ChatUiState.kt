package labs.suchattai.tsukaima.ui.chat

import labs.suchattai.tsukaima.data.api.ModelOptionsDto
import labs.suchattai.tsukaima.data.api.WorkspacesDto
import labs.suchattai.tsukaima.data.gateway.ApprovalChoice
import labs.suchattai.tsukaima.domain.chat.AgentStatus
import labs.suchattai.tsukaima.domain.chat.ChatState

enum class ChatMenu { Model, Workspace, Overflow, Reasoning }
enum class ChatDialog { Rename, DeleteConfirm, ModelConfirm }

/** A model pick the server wants confirmed (`confirm_required`) before it switches; [message] says why. */
data class PendingModel(val provider: String, val model: String, val message: String)

/**
 * Reasoning efforts `config.set key=reasoning` accepts (Hermes `VALID_REASONING_EFFORTS`, plus `none` = off), with
 * their sheet labels. The provider's default (`""`) cannot be set back explicitly.
 */
val REASONING_LEVELS: List<Pair<String, String>> = listOf(
    "none" to "Off",
    "minimal" to "Minimal",
    "low" to "Low",
    "medium" to "Medium",
    "high" to "High",
    "xhigh" to "Extra high",
    "max" to "Max",
    "ultra" to "Ultra",
)

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
    /** The choice sent per answered approval id, for the card's outcome label. */
    val decisions: Map<String, ApprovalChoice> = emptyMap(),
    /** Opened as a new chat (no stored session id in the route). */
    val isNew: Boolean = true,
    /** Composer attachments; kept across rotation (VM), lost with the process. */
    val attachments: List<PendingAttachment> = emptyList(),
    /** Download state per `MEDIA:` path; a path with no entry is idle. */
    val mediaState: Map<String, MediaState> = emptyMap(),
    /** The open session is archived (REST `archived`): read-only, with an Unarchive banner. */
    val archived: Boolean = false,
    /** `session.compress` is in flight. */
    val compacting: Boolean = false,
    /** The model pick waiting for the user's confirmation ([ChatDialog.ModelConfirm]). */
    val pendingModel: PendingModel? = null,
    /** The message whose long-press menu (copy) is open. */
    val messageMenu: String? = null,
) {
    /** What the status line shows. */
    val status: AgentStatus get() = gatewayStatus ?: if (compacting && chat.status != AgentStatus.Offline) AgentStatus.Compacting else chat.status

    /** Server unreachable or socket down: history stays visible, the composer is disabled. */
    val offline: Boolean get() = gatewayStatus == AgentStatus.Offline || chat.status == AgentStatus.Offline

    /** The composer takes input: Hermes reachable and the chat not archived. */
    val writable: Boolean get() = !offline && !archived

    /**
     * Send is live with text or a ready attachment, while no upload is in flight (an image landing after the
     * submit would ride along with the next turn instead) and Hermes is reachable.
     */
    val canSend: Boolean
        get() = writable && !compacting && attachments.none { it.status == AttachmentStatus.Uploading } &&
            (input.isNotBlank() || attachments.any { it.status == AttachmentStatus.Ready })

    /** A new chat with nothing in it yet (shows the working-directory picker). */
    val empty: Boolean get() = isNew && chat.items.isEmpty() && !loading
}
