package labs.suchattai.tsukaima.ui.logs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import labs.suchattai.tsukaima.data.HermesRepository
import labs.suchattai.tsukaima.data.api.ApiException
import labs.suchattai.tsukaima.data.api.LogsDto
import labs.suchattai.tsukaima.data.api.userMessage

/** The slice of [HermesRepository] the Logs screen needs; a seam for tests. Never throws. */
interface LogsSource {
    suspend fun logs(file: String, lines: Int, level: String?): Result<LogsDto>
}

class HermesLogsSource(private val repo: HermesRepository) : LogsSource {
    override suspend fun logs(file: String, lines: Int, level: String?) = repo.logs(file, lines, level)
}

const val LOG_LINES = 300
const val LEVEL_WARN = "WARNING"

data class LogsUiState(
    val file: String = "gateway",
    /** null = All, [LEVEL_WARN] = Warn+. */
    val level: String? = null,
    /** Raw lines, each carrying its own trailing newline. */
    val lines: List<String> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
) {
    /** Lines joined as received, without the final trailing newline. */
    val text: String get() = lines.joinToString("") { it }.removeSuffix("\n")
}

/**
 * Log tail state. Polling is driven by the screen (only while resumed); the VM runs one fetch at a time and
 * never loops on its own.
 */
class LogsViewModel(private val source: LogsSource) : ViewModel() {
    private val _state = MutableStateFlow(LogsUiState())
    val state: StateFlow<LogsUiState> = _state.asStateFlow()

    private var job: Job? = null

    init {
        refresh()
    }

    fun setFile(file: String) {
        if (file == _state.value.file) return
        _state.update { it.copy(file = file, lines = emptyList(), error = null) }
        restart()
    }

    fun setLevel(level: String?) {
        if (level == _state.value.level) return
        _state.update { it.copy(level = level) }
        restart()
    }

    /** Fetches the tail unless a fetch is already running. */
    fun refresh() {
        if (job?.isActive == true) return
        launchFetch()
    }

    /** A filter changed: the in-flight result is for the old filter, so drop it. */
    private fun restart() {
        job?.cancel()
        launchFetch()
    }

    private fun launchFetch() {
        val (file, level) = _state.value.let { it.file to it.level }
        _state.update { it.copy(loading = true) }
        job = viewModelScope.launch {
            val result = source.logs(file, LOG_LINES, level)
            _state.update { s ->
                result.fold(
                    onSuccess = { s.copy(lines = it.lines, error = null, loading = false) },
                    onFailure = { s.copy(error = describe(it), loading = false) },
                )
            }
        }
    }

    /** The server's `detail` from a JSON error body when it gave one, else the HTTP message. */
    private fun describe(e: Throwable): String {
        val detail = (e as? ApiException)?.userMessage()
        return if (detail != null) "Couldn't load logs: $detail" else "Couldn't load logs"
    }
}
