package app.tsukaima.ui.chat

import app.tsukaima.data.api.ModelOptionsDto
import app.tsukaima.data.api.WorkspacesDto
import app.tsukaima.data.gateway.ApprovalChoice
import app.tsukaima.domain.chat.AgentStatus
import app.tsukaima.domain.chat.ChatState
import app.tsukaima.domain.chat.OutboxMessage
import app.tsukaima.domain.chat.SlashCatalog
import app.tsukaima.domain.chat.SlashCommand
import app.tsukaima.domain.voice.VoiceLanguage
import app.tsukaima.domain.voice.VoiceState

enum class ChatMenu { Model, Workspace, Overflow, Reasoning, VoiceLanguage, Send }
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

/**
 * The subagent watch page: row [id] of the transcript, and the child's live transcript as last read ([tail]; null
 * until the first read answered, [available] false when the server has none for it).
 */
data class SubagentWatch(val id: String, val tail: String? = null, val available: Boolean = false)

/**
 * A background command as the sheet shows it. [since]: when it started on the phone's clock (epoch millis, from
 * the server's uptime), for the running time; [tail]: the end of its output, kept up by its output events;
 * [stopping]: Stop was tapped and has not answered yet.
 */
data class ProcessRow(
    val id: String,
    val command: String,
    val running: Boolean,
    val since: Long? = null,
    val tail: String = "",
    val exitCode: Int? = null,
    val stopping: Boolean = false,
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
    /** Server-level status from [app.tsukaima.data.HermesState.gatewayStatus]; wins over the chat's. */
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
    /** The message open in the selectable view (the menu's Select text), by item id. */
    val selecting: String? = null,
    /** A branch is being made ([ChatViewModel.branch]); a second tap waits. */
    val branching: Boolean = false,
    /** The server's slash commands, once the composer first needed them ([ChatCommands]); null until loaded. */
    val commands: SlashCatalog? = null,
    /** The subagent open in its watch page ([ChatSubagents]); null when none is. */
    val subagent: SubagentWatch? = null,
    /** The row of the user message being edited: the next send replaces it and drops what followed. */
    val pendingEdit: Long? = null,
    /** The message being edited was sent with attachments, which the edit does not include. */
    val editDropsAttachments: Boolean = false,
    /** Voice mode's state; non-null while the voice screen is open. */
    val voice: VoiceState? = null,
    /** The voice language setting (BCP-47 tag), mirrored from settings; null = system default. */
    val voiceLanguage: String? = null,
    /** The voice language picker's options, loaded when its sheet opens. */
    val voiceLanguages: List<VoiceLanguage> = emptyList(),
    /** Connection operations (op ids) with an answer in flight: their card's buttons are off ("Connecting…"). */
    val answeringConnections: Set<String> = emptySet(),
    /** The commands the agent left running in the background, and the ones that ended lately ([ChatProcesses]). */
    val processes: List<ProcessRow> = emptyList(),
    /** The "Running in the background" sheet is open. */
    val processSheet: Boolean = false,
    /** Messages written offline that wait to be sent ([ChatOffline]), oldest first; drawn under the transcript. */
    val outbox: List<OutboxMessage> = emptyList(),
    /**
     * The transcript on screen is the copy saved on the phone at this time (epoch millis), not yet replaced by
     * Hermes' history; null once it is, and for a chat that had no copy.
     */
    val savedAt: Long? = null,
    /** [app.tsukaima.data.prefs.Settings.offlineSendMinutes], mirrored: 0 = offline messages are never sent by themselves. */
    val offlineSendMinutes: Int = 0,
) {
    /** What the status line shows. */
    val status: AgentStatus
        get() = gatewayStatus ?: if ((compacting || chat.compacting) && chat.status != AgentStatus.Offline) AgentStatus.Compacting else chat.status

    /** Server unreachable or socket down: history stays visible, the composer is disabled. */
    val offline: Boolean get() = gatewayStatus == AgentStatus.Offline || chat.status == AgentStatus.Offline

    /** The composer takes input: Hermes reachable and the chat not archived. */
    val writable: Boolean get() = !offline && !archived

    /**
     * Send is live with text or a ready attachment, while no upload is in flight (an image landing after the
     * submit would ride along with the next turn instead) and Hermes is reachable.
     */
    val canSend: Boolean
        get() = sendsOffline && input.isNotBlank() && attachments.isEmpty() ||
            writable && !compacting && attachments.none { it.status == AttachmentStatus.Uploading } &&
            (input.isNotBlank() || attachments.any { it.status == AttachmentStatus.Ready })

    /** The composer takes typing: always, except in an archived chat. Offline, what is typed is kept as the draft. */
    val typeable: Boolean get() = !archived

    /**
     * Send works without Hermes: the message waits on the phone and goes out when the connection is back
     * ([ChatOffline]). Text only (a file needs the server to take it), and not while a message is being edited.
     */
    val sendsOffline: Boolean get() = offline && !archived && offlineSendMinutes > 0 && pendingEdit == null

    /** The composer's caption while [pendingEdit] is set, else null. */
    val editCaption: String?
        get() = when {
            pendingEdit == null -> null
            editDropsAttachments -> "Editing — attachments not included — tap to cancel"
            else -> "Editing — tap to cancel"
        }

    /**
     * It is the user's turn in a chat whose effort can be set: the status line shows "Hermes awaits," with
     * the reasoning picker. Not while anything else is on the line (a turn, compaction, offline, gateway state).
     */
    val reasoningPickable: Boolean
        get() = status == AgentStatus.Idle && writable && chat.liveSessionId != null

    /** The slash commands to suggest above the composer for what is typed (none while a message is being edited). */
    val commandMatches: List<SlashCommand>
        get() = if (pendingEdit != null || !writable) emptyList() else commands?.matches(input).orEmpty()

    /** How many background commands are running now: the status line's "2 running". */
    val runningProcesses: Int get() = processes.count { it.running }

    /** A new chat with nothing in it yet (shows the working-directory picker). */
    val empty: Boolean get() = isNew && chat.items.isEmpty() && !loading
}
