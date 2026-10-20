package labs.suchattai.tsukaima.notifications

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import labs.suchattai.tsukaima.data.gateway.Frame
import labs.suchattai.tsukaima.domain.chat.AgentStatus
import labs.suchattai.tsukaima.ui.chat.ChatSync
import labs.suchattai.tsukaima.ui.chat.components.CommandText
import labs.suchattai.tsukaima.ui.settings.pairingEntry

/** A button on a notification: [intentAction] is delivered to [NotificationActionReceiver] with [extras]. */
data class NotificationAction(val label: String, val intentAction: String, val extras: Map<String, String>)

/** Platform-free description of a notification; [AndroidNotificationPoster] turns it into a real one. */
data class NotificationSpec(
    val channel: String,
    val id: Int,
    val title: String,
    val text: String,
    val actions: List<NotificationAction> = emptyList(),
    val ongoing: Boolean = false,
)

/** Pure builders for every notification the app posts. */
object ApprovalNotifier {
    const val ACTION_APPROVE = "labs.suchattai.tsukaima.action.APPROVE"
    const val ACTION_DENY = "labs.suchattai.tsukaima.action.DENY"
    const val ACTION_PAIR_APPROVE = "labs.suchattai.tsukaima.action.PAIR_APPROVE"

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

    fun forApproval(req: Frame.ServerRequest): NotificationSpec {
        val command = req.params.str("command") ?: req.params.str("tool_name").orEmpty()
        val revealed = CommandText.reveal(command)
        val text = truncate(revealed.text) + if (revealed.hadHiddenChars) HIDDEN_SUFFIX else ""
        val actions = if (isAnswerableSrqId(req.id)) {
            listOf(
                NotificationAction("Approve", ACTION_APPROVE, mapOf(EXTRA_SRQ_ID to req.id, EXTRA_CHOICE to CHOICE_ONCE)),
                NotificationAction("Deny", ACTION_DENY, mapOf(EXTRA_SRQ_ID to req.id, EXTRA_CHOICE to CHOICE_DENY)),
            )
        } else {
            emptyList() // informational only: nothing safe to answer it by
        }
        return NotificationSpec(Channels.APPROVALS, approvalId(req.id), "Hermes wants to run a tool", text, actions)
    }

    /** A pending pairing entry (`{platform, request_id, user_id, user_name, age_minutes}`); null if malformed. */
    fun forPairing(json: JsonObject): NotificationSpec? {
        val entry = pairingEntry(json) ?: return null
        val actions = if (entry.requestId.isNotEmpty()) {
            listOf(
                NotificationAction(
                    "Approve", ACTION_PAIR_APPROVE,
                    mapOf(EXTRA_PLATFORM to entry.platform, EXTRA_REQUEST_ID to entry.requestId),
                ),
            )
        } else {
            emptyList()
        }
        val who = entry.who.ifBlank { "Someone" }
        return NotificationSpec(
            Channels.PAIRING,
            pairingId(entry.platform, entry.requestId),
            "Pairing request",
            "$who wants to pair on ${entry.platform}",
            actions,
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

    private fun truncate(s: String): String {
        if (s.length <= MAX_COMMAND) return s
        val end = if (Character.isHighSurrogate(s[MAX_COMMAND - 1])) MAX_COMMAND - 1 else MAX_COMMAND
        return s.substring(0, end) + "…"
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
}
