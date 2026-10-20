package labs.suchattai.tsukaima.notifications

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import labs.suchattai.tsukaima.data.gateway.Frame
import labs.suchattai.tsukaima.domain.chat.AgentStatus
import labs.suchattai.tsukaima.ui.chat.ChatSync
import labs.suchattai.tsukaima.ui.chat.components.CommandText
import labs.suchattai.tsukaima.ui.settings.pairingEntry

/**
 * A button on a notification: [intentAction] is delivered to [NotificationActionReceiver] with [extras], except
 * [ApprovalNotifier.ACTION_OPEN_CHAT], which opens the app. [requiresAuth] buttons need the device unlocked
 * (API 31+); below that they open the app ([ApprovalNotifier.fallbackScreen]) instead of acting.
 */
data class NotificationAction(
    val label: String,
    val intentAction: String,
    val extras: Map<String, String> = emptyMap(),
    val requiresAuth: Boolean = false,
)

/** Platform-free description of a notification; [AndroidNotificationPoster] turns it into a real one. */
data class NotificationSpec(
    val channel: String,
    val id: Int,
    val title: String,
    val text: String,
    val actions: List<NotificationAction> = emptyList(),
    val ongoing: Boolean = false,
    /** Screen the tap opens ([ApprovalNotifier.SCREEN_CHAT] / [ApprovalNotifier.SCREEN_SETTINGS]); null = as left. */
    val openScreen: String? = null,
)

/** Pure builders for every notification the app posts. */
object ApprovalNotifier {
    const val ACTION_APPROVE = "labs.suchattai.tsukaima.action.APPROVE"
    const val ACTION_DENY = "labs.suchattai.tsukaima.action.DENY"
    const val ACTION_PAIR_APPROVE = "labs.suchattai.tsukaima.action.PAIR_APPROVE"

    /** Not a broadcast: opens the chat, like tapping the notification. */
    const val ACTION_OPEN_CHAT = "labs.suchattai.tsukaima.action.OPEN_CHAT"

    const val EXTRA_OPEN_SCREEN = "open_screen"
    const val SCREEN_CHAT = "chat"
    const val SCREEN_SETTINGS = "settings"

    /** Lock-screen stand-in for private notifications: no command, name or title leaks. */
    const val PUBLIC_TITLE = "Hermes needs your attention"

    const val EXTRA_SRQ_ID = "srq_id"
    const val EXTRA_CHOICE = "choice"
    const val EXTRA_PLATFORM = "platform"
    const val EXTRA_REQUEST_ID = "request_id"

    const val CHOICE_ONCE = "once"
    const val CHOICE_DENY = "deny"

    /** Fixed ids; per-item ids are string hashes, which cannot realistically collide with these. */
    const val SERVICE_ID = 0x75_6b_01
    const val GATEWAY_ID = 0x75_6b_02

    private const val MAX_COMMAND = 200
    private const val MAX_PAIRING_FIELD = 40
    private const val HIDDEN_SUFFIX = " (hidden characters)"
    private const val APPROVAL_METHOD = "approval"

    fun approvalId(srqId: String): Int = srqId.hashCode()
    fun pairingId(platform: String, requestId: String): Int = "pairing:$platform:$requestId".hashCode()
    fun cronId(jobId: String): Int = "cron:$jobId".hashCode()
    fun taskId(sessionId: String): Int = "task:$sessionId".hashCode()

    /**
     * Whether [id] is a real server-request id the app may answer with `transport.respond`. A resume's
     * synthetic `pending-…` id is not: answering it would need `approval.respond`, which notifications never use.
     */
    fun isAnswerableSrqId(id: String?): Boolean =
        !id.isNullOrBlank() && !id.startsWith(ChatSync.PENDING_PREFIX)

    fun isApproval(req: Frame.ServerRequest): Boolean = req.method == APPROVAL_METHOD

    /**
     * Approve is offered inline only when the notification shows the whole command and it had no hidden
     * characters; otherwise the user gets Deny and "Open to review" (the full command in chat). Approve needs
     * the device unlocked. No usable srq id: informational, no buttons.
     */
    fun forApproval(req: Frame.ServerRequest): NotificationSpec {
        val command = req.params.str("command") ?: req.params.str("tool_name").orEmpty()
        val revealed = CommandText.reveal(command)
        val truncated = revealed.text.length > MAX_COMMAND
        val text = cap(revealed.text, MAX_COMMAND) + if (revealed.hadHiddenChars) HIDDEN_SUFFIX else ""
        val deny = NotificationAction("Deny", ACTION_DENY, mapOf(EXTRA_SRQ_ID to req.id, EXTRA_CHOICE to CHOICE_DENY))
        val actions = when {
            !isAnswerableSrqId(req.id) -> emptyList() // informational only: nothing safe to answer it by
            truncated || revealed.hadHiddenChars -> listOf(deny, NotificationAction("Open to review", ACTION_OPEN_CHAT))
            else -> listOf(
                NotificationAction(
                    "Approve", ACTION_APPROVE, mapOf(EXTRA_SRQ_ID to req.id, EXTRA_CHOICE to CHOICE_ONCE),
                    requiresAuth = true,
                ),
                deny,
            )
        }
        return NotificationSpec(
            Channels.APPROVALS, approvalId(req.id), "Hermes wants to run a tool", text, actions, openScreen = SCREEN_CHAT,
        )
    }

    /** Where a [NotificationAction.requiresAuth] button leads on API 29–30, where it cannot require unlock. */
    fun fallbackScreen(intentAction: String): String =
        if (intentAction == ACTION_PAIR_APPROVE) SCREEN_SETTINGS else SCREEN_CHAT

    /** A pending pairing entry (`{platform, request_id, user_id, user_name, age_minutes}`); null if malformed. */
    fun forPairing(json: JsonObject): NotificationSpec? {
        val entry = pairingEntry(json) ?: return null
        val actions = if (entry.requestId.isNotEmpty()) {
            listOf(
                NotificationAction(
                    "Approve", ACTION_PAIR_APPROVE,
                    mapOf(EXTRA_PLATFORM to entry.platform, EXTRA_REQUEST_ID to entry.requestId),
                    requiresAuth = true,
                ),
            )
        } else {
            emptyList()
        }
        // Names come from a third-party chat platform: reveal hidden characters, cap, and always show the id.
        val who = safe(entry.who).ifBlank { "Someone" }
        val userId = safe(entry.userId).ifBlank { "no id" }
        return NotificationSpec(
            Channels.PAIRING,
            pairingId(entry.platform, entry.requestId),
            "Pairing request",
            "$who ($userId) wants to pair on ${safe(entry.platform)}",
            actions,
            openScreen = SCREEN_SETTINGS,
        )
    }

    fun forTaskDone(title: String?, sessionId: String = ""): NotificationSpec = NotificationSpec(
        Channels.AGENT,
        taskId(sessionId),
        title?.takeIf { it.isNotBlank() } ?: TASK_DONE,
        TASK_DONE,
    )

    /** Only for [AgentStatus.Stopped] and [AgentStatus.Offline]; null for any other state. */
    fun forGateway(state: AgentStatus?): NotificationSpec? = when (state) {
        AgentStatus.Stopped -> NotificationSpec(Channels.GATEWAY, GATEWAY_ID, state.label, "The Hermes gateway is not running")
        AgentStatus.Offline -> NotificationSpec(Channels.GATEWAY, GATEWAY_ID, state.label, "The server is not answering")
        else -> null
    }

    fun forCronFailed(name: String, jobId: String = name): NotificationSpec =
        NotificationSpec(Channels.AGENT, cronId(jobId), "Scheduled job failed", "“$name” failed on its last run")

    fun forService(): NotificationSpec =
        NotificationSpec(Channels.SERVICE, SERVICE_ID, "Tsukaima", "Listening for Hermes", ongoing = true)

    private const val TASK_DONE = "Hermes finished the task"

    private fun safe(raw: String): String = cap(CommandText.reveal(raw).text, MAX_PAIRING_FIELD)

    /** At most [max] chars (never splitting a surrogate pair), plus "…" when cut. */
    private fun cap(s: String, max: Int): String {
        if (s.length <= max) return s
        val end = if (Character.isHighSurrogate(s[max - 1])) max - 1 else max
        return s.substring(0, end) + "…"
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
}
