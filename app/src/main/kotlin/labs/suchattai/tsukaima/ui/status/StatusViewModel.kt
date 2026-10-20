package labs.suchattai.tsukaima.ui.status

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import labs.suchattai.tsukaima.data.ConnectionManager
import labs.suchattai.tsukaima.data.HermesRepository
import labs.suchattai.tsukaima.data.HermesState
import labs.suchattai.tsukaima.data.api.userMessage
import labs.suchattai.tsukaima.data.api.PairingDto
import labs.suchattai.tsukaima.data.api.SessionDto
import labs.suchattai.tsukaima.data.api.SystemStatsDto
import labs.suchattai.tsukaima.ui.components.IconName
import labs.suchattai.tsukaima.ui.settings.PairingEntry
import labs.suchattai.tsukaima.ui.settings.pairingEntry
import java.util.Locale

/** The slice of [HermesRepository] + [ConnectionManager] the Status screen needs; a seam for tests. Never throws. */
interface StatusSource {
    val hermes: StateFlow<HermesState>

    /** One tick per gateway `platforms.changed`. */
    val changes: Flow<Unit> get() = emptyFlow()
    suspend fun refreshNow()
    suspend fun systemStats(): Result<SystemStatsDto>
    suspend fun sessions(limit: Int): Result<List<SessionDto>>
    suspend fun pairing(): Result<PairingDto>
    suspend fun approvePairing(platform: String, requestId: String): Result<Unit>
    suspend fun revokePairing(platform: String, userId: String): Result<Unit>
    suspend fun gatewayStart(): Result<Unit>
    suspend fun gatewayStop(): Result<Unit>
    suspend fun gatewayRestart(): Result<Unit>
}

class HermesStatusSource(
    private val repo: HermesRepository,
    private val connection: ConnectionManager,
    override val changes: Flow<Unit> = emptyFlow(),
) : StatusSource {
    override val hermes get() = connection.hermes
    override suspend fun refreshNow() = connection.refreshNow()
    override suspend fun systemStats() = repo.systemStats()
    override suspend fun sessions(limit: Int) = repo.sessions(limit)
    override suspend fun pairing() = repo.pairing()
    override suspend fun approvePairing(platform: String, requestId: String) = repo.approvePairing(platform, requestId)
    override suspend fun revokePairing(platform: String, userId: String) = repo.revokePairing(platform, userId)
    override suspend fun gatewayStart() = repo.gatewayStart()
    override suspend fun gatewayStop() = repo.gatewayStop()
    override suspend fun gatewayRestart() = repo.gatewayRestart()
}

enum class Confirm { Restart, Stop }

/** One Status row. */
data class Fact(val icon: IconName, val title: String, val subtitle: String?, val danger: Boolean = false)

data class StatusUiState(
    val hermes: HermesState = HermesState(),
    val stats: SystemStatsDto? = null,
    val pairing: PairingDto? = null,
    val lastSession: SessionDto? = null,
    val busy: Boolean = false,
    val refreshing: Boolean = false,
    val confirm: Confirm? = null,
) {
    val offline: Boolean get() = !hermes.reachable

    val running: Boolean get() = hermes.status?.gatewayState == "running"

    /** First pending pairing request, if any (the card shows one at a time). */
    val pendingPairing: PairingEntry? get() = pairing?.pending?.firstNotNullOfOrNull(::pairingEntry)

    /** The five Status rows; empty until the first poll. Offline shows the first three at 55 % opacity. */
    fun facts(): List<Fact> {
        val status = hermes.status ?: return emptyList()
        val platforms = status.gatewayPlatforms.keys.map { it.replaceFirstChar(Char::titlecase) }
        val joined = platforms.joinToString(" · ")
        val version = status.version ?: stats?.hermesVersion
        val sessions = status.activeSessions ?: 0
        return listOf(
            Fact(IconName.Dns, version?.let { "Hermes $it" } ?: "Hermes", stats?.hostname),
            if (running) {
                Fact(IconName.PlayCircle, "Gateway running", joined.ifEmpty { "No platforms" })
            } else {
                Fact(IconName.StopCircle, "Gateway stopped", "Start it to receive messages", danger = true)
            },
            Fact(
                IconName.Forum,
                "${platforms.size} ${if (platforms.size == 1) "platform" else "platforms"} connected",
                joined.ifEmpty { "No platforms" },
            ),
            Fact(IconName.Chat, "$sessions active ${if (sessions == 1) "session" else "sessions"}", status.activeAgents?.let { "$it running" }),
            Fact(IconName.Memory, memoryTitle(stats?.memory), status.disk?.usedPercent?.let { "Disk ${it.toInt()}% used" }),
        )
    }

    /** "LAST KNOWN STATUS · 14 MIN AGO" ([now] is wall-clock millis, like [HermesState.lastSeenAt]). */
    fun lastKnownLabel(now: Long): String {
        val seen = hermes.lastSeenAt ?: return "LAST KNOWN STATUS"
        return "LAST KNOWN STATUS · ${((now - seen) / 60_000L).coerceAtLeast(0)} MIN AGO"
    }
}

private fun memoryTitle(memory: JsonObject?): String {
    fun num(key: String) = (memory?.get(key) as? JsonPrimitive)?.doubleOrNull
    val total = num("total") ?: return "Memory"
    val used = (total - (num("available") ?: return "Memory")).coerceAtLeast(0.0)
    val gib = 1024.0 * 1024 * 1024
    return String.format(Locale.US, "Memory %.1f / %.1f GB", used / gib, total / gib)
}

/**
 * Status home. Gateway actions call the API, then poll [StatusSource.refreshNow] every [pollMs] (up to
 * [maxPolls] times, 10 s by default) until the gateway reaches the expected state; [busy] covers the whole thing.
 * A `platforms.changed` ([StatusSource.changes]) re-runs [refresh], debounced by [changeDebounceMs], while the
 * screen is [setVisible].
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class StatusViewModel(
    private val source: StatusSource,
    private val clock: () -> Long = System::currentTimeMillis,
    private val pollMs: Long = 1_000,
    private val maxPolls: Int = 10,
    /** Shows a transient message (the app's toast). */
    private val toast: (String) -> Unit = {},
    private val changeDebounceMs: Long = 1_000,
) : ViewModel() {
    private val _state = MutableStateFlow(StatusUiState(hermes = source.hermes.value))
    val state: StateFlow<StatusUiState> = _state.asStateFlow()

    private var actionJob: Job? = null
    private var refreshJob: Job? = null
    private val visible = MutableStateFlow(false)

    init {
        viewModelScope.launch { source.hermes.collect { h -> _state.update { it.copy(hermes = h) } } }
        viewModelScope.launch {
            visible.flatMapLatest { shown -> if (shown) source.changes.debounce(changeDebounceMs) else emptyFlow() }.collect { refresh() }
        }
        refresh()
    }

    /** Whether the Status screen is resumed on screen (live refresh runs only then). */
    fun setVisible(shown: Boolean) {
        visible.value = shown
    }

    fun lastKnownLabel(): String = _state.value.lastKnownLabel(clock())

    /** Re-polls the status and reloads stats, the last session and pairing. A failed piece keeps its old value. */
    fun refresh() {
        // Ignored while one is in flight: repeated Retry taps must not stack parallel refreshes.
        if (refreshJob?.isActive == true) return
        _state.update { it.copy(refreshing = true) }
        refreshJob = viewModelScope.launch {
            try {
                loadAll()
            } finally {
                _state.update { it.copy(refreshing = false) }
            }
        }
    }

    private suspend fun loadAll() {
        run {
            coroutineScope {
                listOf(
                    async { source.refreshNow() },
                    async { source.systemStats().onSuccess { s -> _state.update { it.copy(stats = s) } } },
                    async {
                        source.sessions(LAST_SESSION_LIMIT).onSuccess { list ->
                            _state.update { it.copy(lastSession = list.firstOrNull { s -> !s.archived }) }
                        }
                    },
                    async { source.pairing().onSuccess { p -> _state.update { it.copy(pairing = p) } } },
                ).awaitAll()
            }
        }
    }

    fun askRestart() = _state.update { it.copy(confirm = Confirm.Restart) }
    fun askStop() = _state.update { it.copy(confirm = Confirm.Stop) }
    fun dismissConfirm() = _state.update { it.copy(confirm = null) }

    /** Runs the action the open confirm dialog asked for. */
    fun confirm() {
        val which = _state.value.confirm ?: return
        dismissConfirm()
        when (which) {
            Confirm.Restart -> restartGateway()
            Confirm.Stop -> stopGateway()
        }
    }

    fun startGateway(): Job = gatewayAction("start", "running") { source.gatewayStart() }
    fun restartGateway(): Job = gatewayAction("restart", "running", requireNewUpdatedAt = true) { source.gatewayRestart() }
    fun stopGateway(): Job = gatewayAction("stop", "stopped") { source.gatewayStop() }

    /** A refused action is reported with the server's `detail` ([toast]); nothing is polled then. */
    private fun gatewayAction(
        verb: String,
        target: String,
        requireNewUpdatedAt: Boolean = false,
        call: suspend () -> Result<Unit>,
    ): Job {
        actionJob?.takeIf { it.isActive }?.let { return it }
        _state.update { it.copy(busy = true) }
        // A restart reads "running" before and after, so it is only done once gateway_updated_at moves.
        val before = source.hermes.value.status?.gatewayUpdatedAt
        val job = viewModelScope.launch {
            try {
                val result = call()
                result.exceptionOrNull()?.let { e ->
                    toast("Couldn’t $verb the gateway" + (e.userMessage()?.let { ": $it" } ?: ""))
                }
                if (result.isSuccess) {
                    var polls = 0
                    while (polls < maxPolls) {
                        delay(pollMs)
                        source.refreshNow()
                        polls++
                        val status = source.hermes.value.status
                        if (status?.gatewayState == target && !(requireNewUpdatedAt && status.gatewayUpdatedAt == before)) break
                    }
                }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
        actionJob = job
        return job
    }

    fun approvePairing(entry: PairingEntry) {
        viewModelScope.launch { source.approvePairing(entry.platform, entry.requestId); reloadPairing() }
    }

    fun revokePairing(entry: PairingEntry) {
        viewModelScope.launch { source.revokePairing(entry.platform, entry.userId); reloadPairing() }
    }

    private suspend fun reloadPairing() {
        source.pairing().onSuccess { p -> _state.update { it.copy(pairing = p) } }
    }

    private companion object {
        const val LAST_SESSION_LIMIT = 10
    }
}
