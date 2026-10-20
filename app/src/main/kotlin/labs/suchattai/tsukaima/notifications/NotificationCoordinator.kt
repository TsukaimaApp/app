package labs.suchattai.tsukaima.notifications

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import labs.suchattai.tsukaima.data.RecentIds
import labs.suchattai.tsukaima.data.api.CronJobDto
import labs.suchattai.tsukaima.data.gateway.Frame
import labs.suchattai.tsukaima.domain.chat.AgentStatus

/** What to do with the notification shade. */
sealed interface NotificationCommand {
    data class Post(val spec: NotificationSpec) : NotificationCommand
    data class Cancel(val id: Int) : NotificationCommand
}

/**
 * The decisions behind every notification, free of Android and coroutines. Inputs are fed by
 * [NotificationHub]; each method returns what to post or cancel. All methods are synchronized.
 *
 * - Approvals, questions (`clarify`) and masked prompts (`sudo`, `secret`, `vault.unlock_prompt`; the last two
 *   kinds have no inline answers): notified at most once per id ([RecentIds] of 64), never when already in [answered] or [closed],
 *   and in the foreground only when the chat on screen is not the session that asked (that chat shows it).
 *   `request.cancel` / `approval.cancelled` cancel the notification. The notification carries the stored
 *   session id ([storedFor], else learned from `session.title`) so a tap opens the chat that asked.
 * - Task done: `message.complete` in the background, titled by the last `session.title` for that session;
 *   not for a failed turn (`status: "error"`).
 * - Gateway: posted on the transition to Stopped/Offline only (and only in the background), cancelled when
 *   the gateway is back. The transition is tracked in the foreground too, so returning to a known-down
 *   gateway does not alert.
 * - Cron: a job whose `last_status` turns into a failure since the previous poll. The first poll after
 *   [resetCron] only seeds.
 * - Pairing: each pending request notified once in the background; cancelled when it leaves the list.
 */
class NotificationCoordinator(
    private val answered: Set<String>,
    capacity: Int = 64,
    private val storedFor: (String) -> String? = { null },
    /**
     * Requests the server withdrew (`request.cancel` / `approval.cancelled`), recorded in the foreground too:
     * the server-request replay still holds them, and they must never be notified later. Shared with chat.
     */
    private val closedIds: RecentIds = RecentIds(capacity),
) {
    private val notified = RecentIds(capacity)

    /** Stored session id by live id, from `session.title` events (their payload names the stored id). */
    private val storedByLive = bounded<String>(capacity)

    /** Server-request id by the approval's `request_id`, for `approval.cancelled` (which names the latter). */
    private val srqByRequestId = bounded<String>(capacity)
    private val titles = bounded<String>(capacity)

    /** Approval notifications currently posted, by srq id: for re-posting and for clearing dead buttons. */
    private val postedApprovals = bounded<NotificationSpec>(capacity)

    private var gatewayDown: AgentStatus? = null
    private var cronSnapshot: Map<String, CronJobDto>? = null
    private val postedPairing = mutableSetOf<String>()

    /**
     * [visibleSession] is the live id of the chat on screen, if any: in the foreground only its own approvals
     * are left to it, so one for another session (or with no chat open) is still notified.
     */
    @Synchronized
    fun onServerRequest(req: Frame.ServerRequest, foreground: Boolean, visibleSession: String? = null): NotificationSpec? {
        val clarify = ApprovalNotifier.isClarify(req)
        val secret = ApprovalNotifier.isSecret(req)
        if (!clarify && !secret && !ApprovalNotifier.isApproval(req)) return null
        // Recorded even when suppressed, so a foreground `approval.cancelled` can close it.
        req.params.str("request_id")?.let { srqByRequestId[it] = req.id }
        val live = req.params.str("session_id")
        val shownInChat = foreground && visibleSession != null && live == visibleSession
        if (shownInChat || req.id in answered || closedIds.contains(req.id)) return null
        if (!notified.add(req.id)) return null
        val stored = live?.let { storedFor(it) ?: storedByLive[it] }
        if (clarify) return ApprovalNotifier.forClarify(req, stored)
        if (secret) return ApprovalNotifier.forSecret(req, stored)
        return ApprovalNotifier.forApproval(req, stored).also { postedApprovals[req.id] = it }
    }

    /**
     * The app just went to the background with the connection kept: run the server-request replay through
     * [onServerRequest], so approvals that arrived (and were suppressed) in the foreground are notified now.
     * Answered and already-notified ids are filtered there.
     */
    fun onBackground(replay: List<Frame.ServerRequest>): List<NotificationSpec> =
        replay.mapNotNull { onServerRequest(it, foreground = false) }

    /** The posted approval for [srqId], to re-post when an answer from its button was not sent. */
    @Synchronized
    fun approvalSpec(srqId: String): NotificationSpec? = postedApprovals[srqId]

    /**
     * The background connection ended: approval buttons can no longer work, so cancel them. They are
     * forgotten too, so the next hand-off can notify the still-open ones again.
     */
    @Synchronized
    fun onConnectionEnded(): List<NotificationCommand.Cancel> {
        val ids = postedApprovals.keys.toList()
        postedApprovals.clear()
        ids.forEach(notified::remove)
        return ids.map { NotificationCommand.Cancel(ApprovalNotifier.approvalId(it)) }
    }

    /** An approval was answered somewhere (chat or a notification): its notification goes. */
    fun onAnswered(srqId: String): NotificationCommand.Cancel =
        NotificationCommand.Cancel(ApprovalNotifier.approvalId(srqId))

    @Synchronized
    fun onEvent(ev: Frame.Event, foreground: Boolean): List<NotificationCommand> = when (ev.type) {
        "session.title" -> {
            ev.payload.str("title")?.let { titles[ev.sessionId] = it }
            ev.payload.str("session_id")?.takeIf { ev.sessionId.isNotEmpty() }?.let { storedByLive[ev.sessionId] = it }
            emptyList()
        }
        "message.complete" -> if (foreground || ev.payload.str("status") == "error") {
            emptyList()
        } else {
            listOf(NotificationCommand.Post(ApprovalNotifier.forTaskDone(titles[ev.sessionId], ev.sessionId)))
        }
        "request.cancel" -> listOfNotNull(ev.payload.str("id")?.let(::close))
        "approval.cancelled" -> (ev.payload["request_ids"] as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            .mapNotNull { srqByRequestId.remove(it) }
            .map(::close)
        else -> emptyList()
    }

    /** The server withdrew [srqId]: never notify it again, and cancel it if it is showing. */
    private fun close(srqId: String): NotificationCommand.Cancel {
        closedIds.add(srqId)
        postedApprovals.remove(srqId)
        return onAnswered(srqId)
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
