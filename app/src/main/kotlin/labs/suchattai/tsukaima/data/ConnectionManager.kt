package labs.suchattai.tsukaima.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import labs.suchattai.tsukaima.data.api.AuthException
import labs.suchattai.tsukaima.data.api.HermesApi
import labs.suchattai.tsukaima.data.api.StatusDto
import labs.suchattai.tsukaima.data.gateway.ConnectionState
import labs.suchattai.tsukaima.data.gateway.Frame
import labs.suchattai.tsukaima.data.gateway.GatewayClient
import labs.suchattai.tsukaima.data.prefs.CredentialStore
import labs.suchattai.tsukaima.data.prefs.Settings
import labs.suchattai.tsukaima.domain.chat.AgentStatus
import java.util.concurrent.atomic.AtomicBoolean

/**
 * What the app knows about the Hermes server.
 *
 * [reachable] starts true with [status] null ("not polled yet"), so the UI does not flash "offline" at launch.
 * [status] and [lastSeenAt] keep the last successful poll when the server becomes unreachable.
 */
data class HermesState(
    val reachable: Boolean = true,
    val status: StatusDto? = null,
    val lastSeenAt: Long? = null,
    val connection: ConnectionState = ConnectionState.Disconnected,
)

/**
 * Server-level status for the chat status line: Offline when unreachable, Stopped when the polled gateway
 * is not running, else null so the chat's own status (idle, thinking, ...) wins. Null before the first poll.
 */
fun HermesState.gatewayStatus(): AgentStatus? = when {
    !reachable -> AgentStatus.Offline
    status == null -> null
    status.gatewayState != "running" -> AgentStatus.Stopped
    else -> null
}

/**
 * Owns the app's connection to Hermes: polls `/api/status`, keeps the chat [GatewayClient] connected while
 * credentials are configured, fans out server→client requests, and tracks whether the user must sign in again.
 *
 * The gateway is connected only when configured: [settings] has a server URL and [creds] holds a password,
 * and no auth failure is pending. A server URL change reconnects the gateway to the new server.
 *
 * [settings] is the same synchronous snapshot the REST stack reads, so when it emits a new URL the API
 * already targets that URL. It is null until DataStore has loaded; polling waits for it, so a cold start
 * never polls (or reports offline) against default settings.
 */
class ConnectionManager(
    private val api: HermesApi,
    private val gateway: GatewayClient,
    private val settings: StateFlow<Settings?>,
    private val creds: CredentialStore,
    private val scope: CoroutineScope,
    private val pollMs: Long = 20_000,
    private val backgroundPollMs: Long = 60_000,
    private val now: () -> Long = System::currentTimeMillis,
    private val logger: (String, Throwable?) -> Unit = { _, _ -> },
) {
    private data class Poll(val reachable: Boolean = true, val status: StatusDto? = null, val lastSeenAt: Long? = null)

    /** Guards [poll], [pollJob], [generation] and the derive-and-set in [publish]. */
    private val lock = Any()
    private var poll = Poll()
    private var pollJob: Job? = null

    /** Wait between polls: [pollMs] after [start], [backgroundPollMs] after [startBackground]. */
    @Volatile private var intervalMs = pollMs

    /**
     * Bumped by every [start] that launches a loop and by every [stop]. A loop iteration only touches the
     * gateway while its own generation is current, so an iteration in flight when [stop] runs cannot
     * reconnect afterwards.
     */
    private var generation = 0L

    /** Server URL the poll loop last worked against; a different URL in [settings] wakes the loop. */
    @Volatile private var lastUsedUrl: String? = null

    private val _hermes = MutableStateFlow(HermesState(connection = gateway.state.value))
    val hermes: StateFlow<HermesState> = _hermes.asStateFlow()

    /** Debug/demo switch (Task 14): while true, [hermes] reports `reachable = false` whatever polling says. */
    val simulateOffline = MutableStateFlow(false)

    private val _authRequired = MutableStateFlow(false)

    /** True after an auth failure (re-login failed, or no stored credentials); the UI routes to Settings. */
    val authRequired: StateFlow<Boolean> = _authRequired.asStateFlow()

    private val _serverRequests = MutableSharedFlow<Frame.ServerRequest>(replay = 16, extraBufferCapacity = 64)

    /**
     * Server→client requests (approval, clarify), hot and multicast. The last 16 are replayed so a screen or
     * notification that subscribes late still sees them; subscribers must ignore requests already answered.
     */
    val serverRequests: SharedFlow<Frame.ServerRequest> = _serverRequests.asSharedFlow()

    private val forwarding = AtomicBoolean(false)
    private val refreshMutex = Mutex()

    /** Wakes the poll loop early (URL change, auth cleared). */
    private val wake = Channel<Unit>(Channel.CONFLATED)

    /** URL the gateway was last asked to connect to; null when we last asked it to disconnect. Guarded by [lock]. */
    private var connectedUrl: String? = null

    init {
        scope.launch { gateway.state.collect { publish() } }
        scope.launch { simulateOffline.collect { publish() } }
    }

    /**
     * Starts polling and gateway management (idempotent). The first call also starts the one long-lived
     * collector of [GatewayClient.serverRequests], which [stop] does not cancel. If a background loop is
     * running it is kept and switched to the foreground interval, polling at once.
     */
    fun start() = run(pollMs)

    /**
     * Like [start], for the foreground service while the app is in the background: keeps the gateway
     * connected but polls status every [backgroundPollMs] instead of [pollMs].
     */
    fun startBackground() = run(backgroundPollMs)

    private fun run(interval: Long) {
        if (forwarding.compareAndSet(false, true)) {
            scope.launch { gateway.serverRequests.collect { _serverRequests.emit(it) } }
        }
        synchronized(lock) {
            val faster = interval < intervalMs
            intervalMs = interval
            if (pollJob?.isActive == true) {
                if (faster) wake.trySend(Unit) // back in the foreground: refresh now, not in up to a minute
                return
            }
            val gen = ++generation
            pollJob = scope.launch {
                settings.filterNotNull().first() // cold start: wait for DataStore, never poll with defaults
                launch {
                    settings.filterNotNull().map { it.serverUrl }.distinctUntilChanged().collect { url ->
                        if (url != lastUsedUrl) wake.trySend(Unit)
                    }
                }
                while (true) {
                    try {
                        lastUsedUrl = settings.value?.serverUrl
                        refreshNow()
                        syncGateway(gen)
                    } catch (c: CancellationException) {
                        throw c
                    } catch (e: Exception) {
                        logger("connection: poll iteration failed", e)
                    }
                    withTimeoutOrNull(intervalMs) { wake.receive() }
                }
            }
        }
    }

    /** Stops polling and disconnects the gateway. [serverRequests] stays wired for the next [start]. */
    fun stop() {
        synchronized(lock) {
            generation++
            pollJob?.cancel()
            pollJob = null
            connectedUrl = null
            gateway.disconnect() // after cancelling, under the lock syncGateway checks: nothing can follow it
        }
    }

    /** Polls `/api/status` now and publishes the result before returning. Never throws (except cancellation). */
    suspend fun refreshNow() {
        refreshMutex.withLock {
            val outcome: Result<StatusDto>? = if (settings.value?.serverUrl.isNullOrEmpty()) {
                null
            } else {
                try {
                    Result.success(api.status())
                } catch (c: CancellationException) {
                    throw c
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }
            synchronized(lock) {
                val prev = poll
                poll = when {
                    outcome == null -> Poll() // no server configured: nothing to be offline from
                    outcome.isSuccess -> Poll(true, outcome.getOrThrow(), now())
                    // The server answered; the credentials are the problem.
                    outcome.exceptionOrNull() is AuthException -> prev.copy(reachable = true, lastSeenAt = now())
                    else -> prev.copy(reachable = false)
                }
            }
            outcome?.exceptionOrNull()?.let { e ->
                if (e is AuthException) reportAuthFailure() else logger("connection: status poll failed", e)
            }
            publish()
        }
    }

    /** Marks that the stored credentials no longer work. The gateway is not reconnected until cleared. */
    fun reportAuthFailure() {
        _authRequired.value = true
    }

    /** Called after a successful sign-in (or sign-out): clears [authRequired] and re-syncs the gateway now. */
    fun clearAuthRequired() {
        _authRequired.value = false
        wake.trySend(Unit)
    }

    /**
     * Connects or disconnects the gateway for the current settings, if [gen] is still the live loop.
     * Credentials are read outside [lock] (the store can be slow); the decision and the gateway call happen
     * under it, so they are atomic with respect to [stop].
     */
    private fun syncGateway(gen: Long) {
        val url = settings.value?.serverUrl.orEmpty()
        val configured = url.isNotEmpty() && creds.password() != null
        synchronized(lock) {
            if (gen != generation) return
            applyGateway(url, configured)
        }
    }

    private fun applyGateway(url: String, configured: Boolean) {
        when {
            !configured -> if (connectedUrl != null) {
                connectedUrl = null
                gateway.disconnect()
            }
            _authRequired.value -> Unit // leave it; the user must sign in again first
            connectedUrl != null && connectedUrl != url -> {
                gateway.disconnect() // server changed: drop the old socket, then connect to the new one
                connectedUrl = url
                gateway.connect()
            }
            else -> {
                connectedUrl = url
                gateway.connect() // idempotent; revives a client that gave up after an auth failure
            }
        }
    }

    private fun publish() {
        synchronized(lock) {
            _hermes.value = HermesState(
                reachable = poll.reachable && !simulateOffline.value,
                status = poll.status,
                lastSeenAt = poll.lastSeenAt,
                connection = gateway.state.value,
            )
        }
    }
}
