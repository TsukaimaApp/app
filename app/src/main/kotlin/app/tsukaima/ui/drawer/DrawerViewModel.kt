package app.tsukaima.ui.drawer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import app.tsukaima.data.SessionsSource
import app.tsukaima.data.api.SessionDto
import app.tsukaima.data.prefs.Settings

/** Profile a session belongs to, for grouping: the display name, else the id, else `default`. */
val SessionDto.profileKey: String get() = profileName ?: profile ?: DEFAULT_PROFILE

private const val DEFAULT_PROFILE = "default"

data class DrawerUiState(
    val sessions: List<SessionDto> = emptyList(),
    val query: String = "",
    val showArchived: Boolean = false,
    val selectedId: String? = null,
    val loading: Boolean = true,
    /** Server search hits for [query] (null: none yet for it); shown in place of the list while [searchActive]. */
    val searchResults: List<SessionDto>? = null,
    val searching: Boolean = false,
    /** The last server search for [query] failed (shown instead of "Searching…"; cleared by the next query). */
    val searchFailed: Boolean = false,
    /** The "Cron runs" section is unfolded (collapsed by default: runs pile up and are rarely what you came for). */
    val showCron: Boolean = false,
) {
    /** [query] is long enough for the server search ([MIN_SEARCH] characters); shorter filters the list locally. */
    val searchActive: Boolean get() = query.trim().length >= MIN_SEARCH

    private val matching: List<SessionDto> by lazy {
        val q = query.trim()
        if (q.isEmpty()) {
            sessions
        } else {
            sessions.filter {
                it.title?.contains(q, ignoreCase = true) == true || it.preview?.contains(q, ignoreCase = true) == true
            }
        }
    }

    /**
     * Unarchived sessions matching [query], grouped by [profileKey]. `default` leads; other profiles keep the
     * order in which they first appear in [sessions] (the server's order, most recent first).
     */
    val grouped: Map<String, List<SessionDto>> by lazy {
        val groups = matching.filterNot { it.archived || it.isCron }.groupBy { it.profileKey }
        val default = groups[DEFAULT_PROFILE]
        if (default == null) groups else linkedMapOf(DEFAULT_PROFILE to default) + groups
    }

    /** Archived sessions matching [query] (the archived view); cron runs included, labelled by the row. */
    val archived: List<SessionDto> by lazy { matching.filter { it.archived } }

    /** Unarchived cron-run sessions matching [query], for the collapsible "Cron runs" section. */
    val cron: List<SessionDto> by lazy { matching.filter { it.isCron && !it.archived } }

    /** Every unarchived cron run, whatever the query: the count on the section row. */
    val cronCount: Int by lazy { sessions.count { it.isCron && !it.archived } }

    /** Every archived session, whatever the query: the count on the "Archived" row. */
    val archivedCount: Int by lazy { sessions.count { it.archived } }
}

/** Characters a query needs before the drawer asks the server (`/api/sessions/search`). */
const val MIN_SEARCH = 2

/**
 * Sessions drawer state: the session list, search, archived view and session mutations. Reloads whenever the
 * server URL in [settings] changes (and once DataStore has loaded); [refresh] reloads on demand. Each mutation
 * is followed by a [refresh]. Failures are reported on [errors] as toast text: every mutation failure, but a
 * load failure only for the first failure since the last successful load, and never while [isReachable] is
 * false (the status line already says Hermes is unreachable).
 *
 * Live refresh: each of [changes] (the gateway's `sessions.changed`) reloads the list, debounced by [changeDebounceMs],
 * but only while the drawer is [setVisible]. Search: a query of [MIN_SEARCH]+ characters asks the server after
 * [searchDebounceMs] of quiet; a newer query supersedes it.
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class DrawerViewModel(
    private val repo: SessionsSource,
    settings: StateFlow<Settings?>,
    private val isReachable: () -> Boolean = { true },
    changes: Flow<Unit> = emptyFlow(),
    private val changeDebounceMs: Long = 1_000,
    private val searchDebounceMs: Long = 300,
    /** Stored session [id] was deleted from the drawer (e.g. to forget it as the share card's last chat). */
    private val onDeleted: suspend (id: String) -> Unit = {},
) : ViewModel() {
    private val _state = MutableStateFlow(DrawerUiState())
    val state: StateFlow<DrawerUiState> = _state.asStateFlow()

    private val _errors = Channel<String>(Channel.BUFFERED)

    /** One-shot error messages for the shell's toast. */
    val errors: Flow<String> = _errors.receiveAsFlow()

    private val _openSessionRemoved = Channel<String>(Channel.BUFFERED)

    /**
     * One-shot: the session open in the main pane ([DrawerUiState.selectedId]) was deleted or archived from the
     * drawer. The shell moves to a new chat.
     */
    val openSessionRemoved: Flow<String> = _openSessionRemoved.receiveAsFlow()

    /** A load failure has been toasted since the last successful load. */
    private var loadFailureReported = false

    private var serverUrl: String = ""
    private var refreshJob: Job? = null
    private var searchJob: Job? = null
    private val visible = MutableStateFlow(false)

    init {
        viewModelScope.launch {
            settings.filterNotNull().map { it.serverUrl }.distinctUntilChanged().collect { url ->
                serverUrl = url
                if (url.isEmpty()) {
                    refreshJob?.cancel()
                    _state.update { it.copy(sessions = emptyList(), loading = false) }
                } else {
                    // A different server: never show the old server's sessions.
                    _state.update { it.copy(sessions = emptyList(), loading = true) }
                    refresh()
                }
            }
        }
        viewModelScope.launch {
            visible.flatMapLatest { shown -> if (shown) changes.debounce(changeDebounceMs) else emptyFlow() }.collect { refresh() }
        }
    }

    /** Whether the drawer is on screen (phone overlay open or the wide pane, while the app is resumed). */
    fun setVisible(shown: Boolean) {
        visible.value = shown
    }

    /** Reloads the list; a newer call supersedes one in flight. Keeps the old list if the load fails. */
    fun refresh() {
        if (serverUrl.isEmpty()) return
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch { load() }
    }

    fun setQuery(query: String) {
        val previous = _state.value.query.trim()
        _state.update { it.copy(query = query) }
        val q = query.trim()
        if (q == previous && (_state.value.searchResults != null || _state.value.searching)) return
        searchJob?.cancel()
        if (q.length < MIN_SEARCH) {
            _state.update { it.copy(searchResults = null, searching = false, searchFailed = false) }
            return
        }
        _state.update { it.copy(searchResults = null, searchFailed = false) }
        searchJob = viewModelScope.launch {
            delay(searchDebounceMs)
            search(q)
        }
    }

    private suspend fun search(q: String) {
        _state.update { it.copy(searching = true) }
        repo.search(q)
            .onSuccess { hits -> _state.update { if (it.query.trim() == q) it.copy(searchResults = hits) else it } }
            .onFailure {
                _state.update { if (it.query.trim() == q) it.copy(searchFailed = true) else it }
                if (isReachable()) _errors.trySend("Couldn't search sessions")
            }
        _state.update { it.copy(searching = false) }
    }

    fun setShowArchived(show: Boolean) = _state.update { it.copy(showArchived = show) }

    fun setShowCron(show: Boolean) = _state.update { it.copy(showCron = show) }

    /** The session the main pane is showing (null for a new chat or a non-chat screen). */
    fun select(id: String?) = _state.update { it.copy(selectedId = id) }

    fun rename(id: String, title: String) = mutate("Couldn't rename the session") { repo.rename(id, title) }

    fun archive(id: String, archived: Boolean) = mutate(
        failure = if (archived) "Couldn't archive the session" else "Couldn't unarchive the session",
        removes = id.takeIf { archived },
    ) { repo.archive(id, archived) }

    fun delete(id: String) = mutate("Couldn't delete the session", removes = id) {
        repo.delete(id).onSuccess { onDeleted(id) }
    }

    /** Runs [block]; on success, if it removed the open session ([removes]), says so on [openSessionRemoved]. */
    private fun mutate(failure: String, removes: String? = null, block: suspend () -> Result<Unit>) {
        viewModelScope.launch {
            block()
                .onSuccess { if (removes != null && removes == _state.value.selectedId) _openSessionRemoved.trySend(removes) }
                .onFailure { _errors.trySend(failure) }
            refreshJob?.cancel()
            load()
            _state.value.query.trim().takeIf { it.length >= MIN_SEARCH }?.let { search(it) }
        }
    }

    private suspend fun load() {
        repo.sessions()
            .onSuccess { list ->
                loadFailureReported = false
                _state.update { it.copy(sessions = list, loading = false) }
            }
            .onFailure {
                _state.update { it.copy(loading = false) }
                if (!loadFailureReported && isReachable()) {
                    loadFailureReported = true
                    _errors.trySend("Couldn't load sessions")
                }
            }
    }
}

/**
 * Compact age for a session row: `now` under a minute, then `5m`, `3h`, `2d`, `1w`, `2mo`, `1y`. [startedAt] is
 * unix seconds (as Hermes reports it), [now] epoch millis. Null [startedAt] gives an empty label; a start in
 * the future (clock skew) reads `now`.
 */
fun ageLabel(startedAt: Double?, now: Long): String {
    if (startedAt == null) return ""
    val seconds = (now / 1000.0 - startedAt).toLong()
    val minutes = seconds / 60
    val hours = minutes / 60
    val days = hours / 24
    return when {
        minutes < 1 -> "now"
        hours < 1 -> "${minutes}m"
        days < 1 -> "${hours}h"
        days < 7 -> "${days}d"
        days < 30 -> "${days / 7}w"
        days < 365 -> "${days / 30}mo"
        else -> "${days / 365}y"
    }
}
