package labs.suchattai.tsukaima.notifications

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import labs.suchattai.tsukaima.data.HermesState
import labs.suchattai.tsukaima.data.api.CronJobDto
import labs.suchattai.tsukaima.data.api.PairingDto
import labs.suchattai.tsukaima.data.gateway.Frame
import labs.suchattai.tsukaima.data.gateway.RpcTransport
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
 */
class ApprovalAnswerer(
    private val transport: RpcTransport,
    private val answered: MutableSet<String>,
    private val connected: () -> Boolean,
    private val cancel: (Int) -> Unit,
) {
    enum class Outcome { Sent, AlreadyAnswered, NotConnected, Invalid }

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
        transport.respond(srqId, buildJsonObject { put("choice", choice) })
        cancel(id)
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
    private val serverRequests: Flow<Frame.ServerRequest>,
    private val events: Flow<Frame.Event>,
    private val hermes: Flow<HermesState>,
    private val answered: AnsweredRequestIds,
    private val cronJobs: suspend () -> Result<List<CronJobDto>>,
    private val pairing: suspend () -> Result<PairingDto>,
    private val poster: NotificationPoster,
    private val foreground: () -> Boolean,
    private val pollMs: Long = 60_000,
    private val logger: (String, Throwable?) -> Unit = { _, _ -> },
) {
    private val coordinator = NotificationCoordinator(answered)
    private val started = AtomicBoolean(false)
    private val lock = Any()
    private var backgroundJob: Job? = null

    fun start() {
        if (!started.compareAndSet(false, true)) return
        answered.addListener { apply(coordinator.onAnswered(it)) }
        scope.launch {
            serverRequests.collect { req -> coordinator.onServerRequest(req, foreground())?.let(poster::post) }
        }
        scope.launch {
            events.collect { ev -> coordinator.onEvent(ev, foreground()).forEach(::apply) }
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
