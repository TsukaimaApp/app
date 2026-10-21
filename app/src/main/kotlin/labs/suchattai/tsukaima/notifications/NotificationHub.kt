package labs.suchattai.tsukaima.notifications

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import labs.suchattai.tsukaima.data.HermesState
import labs.suchattai.tsukaima.data.RecentIds
import labs.suchattai.tsukaima.data.api.CronJobDto
import labs.suchattai.tsukaima.data.api.PairingDto
import labs.suchattai.tsukaima.data.gateway.Frame
import labs.suchattai.tsukaima.data.gateway.RpcTransport
import labs.suchattai.tsukaima.data.prefs.NotificationPrefs
import labs.suchattai.tsukaima.data.gatewayStatus
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Thread-safe set of answered server-request ids that tells listeners about each id newly added, so a
 * notification can be cancelled when chat answers its approval. [add] is the atomic claim: false means
 * another surface already answered.
 */
class AnsweredRequestIds private constructor(
    private val backing: MutableSet<String>,
) : MutableSet<String> by backing {
    constructor() : this(ConcurrentHashMap.newKeySet())

    private val listeners = CopyOnWriteArrayList<(String) -> Unit>()

    fun addListener(listener: (String) -> Unit) {
        listeners += listener
    }

    override fun add(element: String): Boolean {
        val added = backing.add(element)
        if (added) listeners.forEach { it(element) }
        return added
    }

    override fun addAll(elements: Collection<String>): Boolean = elements.fold(false) { any, e -> add(e) || any }
}

/**
 * Answers an approval from a notification button: only by its server-request id through
 * [RpcTransport.respond], only with `once` or `deny`, and only if this call wins the claim in [answered].
 * If the frame was not enqueued on a live socket, the claim is released and [repost] puts the
 * notification back so the user can try again.
 */
class ApprovalAnswerer(
    private val transport: RpcTransport,
    private val answered: MutableSet<String>,
    private val connected: () -> Boolean,
    private val cancel: (Int) -> Unit,
    private val repost: (String) -> Unit = {},
) {
    enum class Outcome { Sent, NotSent, AlreadyAnswered, NotConnected, Invalid }

    fun answer(srqId: String?, choice: String?): Outcome {
        if (srqId == null || !ApprovalNotifier.isAnswerableSrqId(srqId)) return Outcome.Invalid
        if (choice != ApprovalNotifier.CHOICE_ONCE && choice != ApprovalNotifier.CHOICE_DENY) return Outcome.Invalid
        // respond() is dropped without a socket; leave the notification up so the user can try again.
        if (!connected()) return Outcome.NotConnected
        val id = ApprovalNotifier.approvalId(srqId)
        if (!answered.add(srqId)) {
            cancel(id)
            return Outcome.AlreadyAnswered
        }
        cancel(id)
        if (!transport.respond(srqId, buildJsonObject { put("choice", choice) })) {
            answered.remove(srqId) // not sent: the server still waits, so let the user (or chat) answer again
            repost(srqId)
            return Outcome.NotSent
        }
        return Outcome.Sent
    }
}

/**
 * Process-lived glue between the connection's flows and the notification shade; all decisions are in
 * [NotificationCoordinator]. [start] wires the always-on listeners (server requests, gateway events, gateway
 * status, answered ids) so the coordinator sees cancellations and transitions even in the foreground.
 * [startBackground] adds the cron/pairing poll every [pollMs] while [GatewayService] runs.
 */
class NotificationHub(
    private val scope: CoroutineScope,
    private val serverRequests: SharedFlow<Frame.ServerRequest>,
    private val events: Flow<Frame.Event>,
    private val hermes: Flow<HermesState>,
    private val answered: AnsweredRequestIds,
    private val cronJobs: suspend () -> Result<List<CronJobDto>>,
    private val pairing: suspend () -> Result<PairingDto>,
    private val poster: NotificationPoster,
    private val foreground: () -> Boolean,
    /** Live id of the chat on screen (null when none): its approvals are not notified in the foreground. */
    private val visibleSession: () -> String? = { null },
    /** Live → stored session id, so an approval notification opens the chat that asked. */
    storedFor: (String) -> String? = { null },
    /** Withdrawn request ids, shared with chat ([labs.suchattai.tsukaima.data.ConnectionManager.closedRequestIds]). */
    closed: RecentIds = RecentIds(),
    private val pollMs: Long = 60_000,
    private val logger: (String, Throwable?) -> Unit = { _, _ -> },
    /** Settings → Notifications, read per decision. */
    prefs: () -> NotificationPrefs = { NotificationPrefs() },
) {
    private val coordinator = NotificationCoordinator(answered, storedFor = storedFor, closedIds = closed, prefs = prefs)
    private val started = AtomicBoolean(false)
    private val lock = Any()
    private var backgroundJob: Job? = null

    fun start() {
        if (!started.compareAndSet(false, true)) return
        answered.addListener { apply(coordinator.onAnswered(it)) }
        scope.launch {
            serverRequests.collect { req -> coordinator.onServerRequest(req, foreground(), visibleSession())?.let(poster::post) }
        }
        scope.launch {
            events.collect { ev -> coordinator.onEvent(ev, foreground(), visibleSession()).forEach(::apply) }
        }
        scope.launch {
            hermes.map { it.gatewayStatus() }.distinctUntilChanged().collect { status ->
                coordinator.onGatewayStatus(status, foreground())?.let(::apply)
            }
        }
    }

    /** Starts polling cron jobs and pairing requests (idempotent). The first cron poll only seeds. */
    fun startBackground() {
        synchronized(lock) {
            if (backgroundJob?.isActive == true) return
            coordinator.resetCron()
            backgroundJob = scope.launch {
                while (true) {
                    poll()
                    delay(pollMs)
                }
            }
        }
    }

    fun stopBackground() {
        synchronized(lock) {
            backgroundJob?.cancel()
            backgroundJob = null
        }
    }

    /**
     * The app went to the background and kept its connection: notify approvals still open from the
     * server-request replay (suppressed while in the foreground).
     */
    fun onBackground() {
        coordinator.onBackground(serverRequests.replayCache).forEach(poster::post)
    }

    /** The background connection is gone: approval buttons cannot work without it, so clear them. */
    fun onConnectionEnded() {
        coordinator.onConnectionEnded().forEach(::apply)
    }

    /** Puts an approval notification back after its answer could not be sent. */
    fun repostApproval(srqId: String) {
        if (srqId in answered) return
        coordinator.approvalSpec(srqId)?.let(poster::post)
    }

    /** Sign-out: stops the background polls and removes every notification (their buttons and links are dead). */
    fun signedOut() {
        stopBackground()
        poster.cancelAll()
        coordinator.reset()
    }

    fun post(spec: NotificationSpec) = poster.post(spec)
    fun cancel(id: Int) = poster.cancel(id)

    private suspend fun poll() {
        try {
            cronJobs().onSuccess { jobs -> coordinator.onCronJobs(jobs).forEach(poster::post) }
            pairing().onSuccess { p -> coordinator.onPairing(p.pending, foreground()).forEach(::apply) }
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            logger("notifications: background poll failed", e)
        }
    }

    private fun apply(command: NotificationCommand) = when (command) {
        is NotificationCommand.Post -> poster.post(command.spec)
        is NotificationCommand.Cancel -> poster.cancel(command.id)
    }
}
