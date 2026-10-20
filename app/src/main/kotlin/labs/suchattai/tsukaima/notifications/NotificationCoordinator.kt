package labs.suchattai.tsukaima.notifications

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import labs.suchattai.tsukaima.data.api.CronJobDto
import labs.suchattai.tsukaima.data.gateway.Frame
import labs.suchattai.tsukaima.domain.chat.AgentStatus

/** What to do with the notification shade. */
sealed interface NotificationCommand {
    data class Post(val spec: NotificationSpec) : NotificationCommand
    data class Cancel(val id: Int) : NotificationCommand
}

/** Bounded set of recently seen ids: once [capacity] is reached, the oldest is forgotten. Thread-safe. */
class RecentIds(val capacity: Int = 64) {
    private val ids = object : LinkedHashMap<String, Unit>(capacity, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>?) = size > capacity
    }

    /** True if [id] was not present (and is now recorded). */
    @Synchronized
    fun add(id: String): Boolean = ids.put(id, Unit) == null

    @get:Synchronized
    val size: Int get() = ids.size
}

/**
 * The decisions behind every notification, free of Android and coroutines. Inputs are fed by
 * [NotificationHub]; each method returns what to post or cancel. All methods are synchronized.
 *
 * - Approvals: notified at most once per id ([RecentIds] of 64), never when already in [answered], never in
 *   the foreground (chat shows them). `request.cancel` / `approval.cancelled` cancel the notification.
 * - Task done: `message.complete` in the background, titled by the last `session.title` for that session.
 * - Gateway: posted on the transition to Stopped/Offline only (and only in the background), cancelled when
 *   the gateway is back. The transition is tracked in the foreground too, so returning to a known-down
 *   gateway does not alert.
 * - Cron: a job whose `last_status` turns into a failure since the previous poll. The first poll after
 *   [resetCron] only seeds.
 * - Pairing: each pending request notified once in the background; cancelled when it leaves the list.
 */
class NotificationCoordinator(private val answered: Set<String>, capacity: Int = 64) {
    private val notified = RecentIds(capacity)

    /** Server-request id by the approval's `request_id`, for `approval.cancelled` (which names the latter). */
    private val srqByRequestId = bounded<String>(capacity)
    private val titles = bounded<String>(capacity)

    private var gatewayDown: AgentStatus? = null
    private var cronSnapshot: Map<String, CronJobDto>? = null
    private val postedPairing = mutableSetOf<String>()

    @Synchronized
    fun onServerRequest(req: Frame.ServerRequest, foreground: Boolean): NotificationSpec? {
        if (!ApprovalNotifier.isApproval(req)) return null
        if (foreground || req.id in answered) return null
        if (!notified.add(req.id)) return null
        req.params.str("request_id")?.let { srqByRequestId[it] = req.id }
        return ApprovalNotifier.forApproval(req)
    }

    /** An approval was answered somewhere (chat or a notification): its notification goes. */
    fun onAnswered(srqId: String): NotificationCommand.Cancel =
        NotificationCommand.Cancel(ApprovalNotifier.approvalId(srqId))

    @Synchronized
    fun onEvent(ev: Frame.Event, foreground: Boolean): List<NotificationCommand> = when (ev.type) {
        "session.title" -> {
            ev.payload.str("title")?.let { titles[ev.sessionId] = it }
            emptyList()
        }
        "message.complete" -> if (foreground) {
            emptyList()
        } else {
            listOf(NotificationCommand.Post(ApprovalNotifier.forTaskDone(titles[ev.sessionId], ev.sessionId)))
        }
        "request.cancel" -> listOfNotNull(ev.payload.str("id")?.let { onAnswered(it) })
        "approval.cancelled" -> (ev.payload["request_ids"] as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            .mapNotNull { srqByRequestId.remove(it) }
            .map { onAnswered(it) }
        else -> emptyList()
    }

    /** [status] is [labs.suchattai.tsukaima.data.gatewayStatus] of the latest poll. */
    @Synchronized
    fun onGatewayStatus(status: AgentStatus?, foreground: Boolean): NotificationCommand? {
        val down = status.takeIf { it == AgentStatus.Stopped || it == AgentStatus.Offline }
        val prev = gatewayDown
        gatewayDown = down
        return when {
            down != null && down != prev ->
                if (foreground) null else ApprovalNotifier.forGateway(down)?.let { NotificationCommand.Post(it) }
            down == null && prev != null -> NotificationCommand.Cancel(ApprovalNotifier.GATEWAY_ID)
            else -> null
        }
    }

    /** Forgets the cron snapshot, so the next [onCronJobs] only seeds. */
    @Synchronized
    fun resetCron() {
        cronSnapshot = null
    }

    @Synchronized
    fun onCronJobs(jobs: List<CronJobDto>): List<NotificationSpec> {
        val prev = cronSnapshot
        cronSnapshot = jobs.associateBy { it.id }
        if (prev == null) return emptyList()
        return jobs.filter { job ->
            if (!isFailure(job.lastStatus)) return@filter false
            val before = prev[job.id] ?: return@filter true // new since the last poll, already failed
            !isFailure(before.lastStatus) || before.lastRunAt != job.lastRunAt
        }.map { ApprovalNotifier.forCronFailed(it.name?.takeIf(String::isNotBlank) ?: it.id, it.id) }
    }

    @Synchronized
    fun onPairing(pending: List<JsonObject>, foreground: Boolean): List<NotificationCommand> {
        val specs = pending.mapNotNull { ApprovalNotifier.forPairing(it) }
        val current = specs.map { it.id.toString() }.toSet()
        val gone = postedPairing.filter { it !in current }
        postedPairing.removeAll(gone.toSet())
        val out = gone.map { NotificationCommand.Cancel(it.toInt()) }.toMutableList<NotificationCommand>()
        if (foreground) return out
        for (spec in specs) {
            if (spec.actions.isEmpty()) continue // no request id: nothing actionable to offer
            if (notified.add("pairing:${spec.id}")) {
                postedPairing += spec.id.toString()
                out += NotificationCommand.Post(spec)
            }
        }
        return out
    }

    private fun isFailure(status: String?) = status?.lowercase() in FAILURE

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    private companion object {
        val FAILURE = setOf("error", "failed", "failure")

        fun <V> bounded(capacity: Int): MutableMap<String, V> = object : LinkedHashMap<String, V>() {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, V>?) = size > capacity
        }
    }
}
