package labs.suchattai.tsukaima.ui.cron

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import labs.suchattai.tsukaima.data.HermesRepository
import labs.suchattai.tsukaima.data.api.CronJobDto
import labs.suchattai.tsukaima.data.api.DeliveryTargetDto

/** The slice of [HermesRepository] the Cron screens need; a seam for tests. Never throws. */
interface CronSource {
    suspend fun jobs(): Result<List<CronJobDto>>
    suspend fun deliveryTargets(): Result<List<DeliveryTargetDto>>
    suspend fun create(name: String, schedule: String, prompt: String, deliver: String): Result<CronJobDto>
    suspend fun update(id: String, updates: JsonObject): Result<Unit>
    suspend fun pause(id: String): Result<Unit>
    suspend fun resume(id: String): Result<Unit>
    suspend fun trigger(id: String): Result<Unit>
    suspend fun delete(id: String): Result<Unit>
}

class HermesCronSource(private val repo: HermesRepository) : CronSource {
    override suspend fun jobs() = repo.cronJobs()
    override suspend fun deliveryTargets() = repo.deliveryTargets()
    override suspend fun create(name: String, schedule: String, prompt: String, deliver: String) =
        repo.createCronJob(name, schedule, prompt, deliver)
    override suspend fun update(id: String, updates: JsonObject) = repo.updateCronJob(id, updates)
    override suspend fun pause(id: String) = repo.pauseCronJob(id)
    override suspend fun resume(id: String) = repo.resumeCronJob(id)
    override suspend fun trigger(id: String) = repo.triggerCronJob(id)
    override suspend fun delete(id: String) = repo.deleteCronJob(id)
}

const val LOCAL_TARGET = "local"
const val SCHEDULE_ERROR = "Use 5 fields: minute hour day month weekday"

/** Hermes marks a paused job with `state == "paused"` or `enabled == false`. */
val CronJobDto.isPaused: Boolean get() = state == "paused" || enabled == false

val CronJobDto.expr: String get() = schedule?.expr ?: schedule?.display ?: scheduleDisplay.orEmpty()

val CronJobDto.title: String get() = name?.takeIf { it.isNotBlank() } ?: id

data class CronDraft(
    val name: String = "",
    val prompt: String = "",
    val schedule: String = "0 9 * * *",
    val deliver: String = LOCAL_TARGET,
)

data class CronUiState(
    val jobs: List<CronJobDto> = emptyList(),
    val targets: List<DeliveryTargetDto> = emptyList(),
    val loading: Boolean = false,
    /** The job being edited; null when closed or when creating a new one. */
    val editing: CronJobDto? = null,
    val isNew: Boolean = false,
    val editorOpen: Boolean = false,
    val draft: CronDraft = CronDraft(),
    /** The draft as loaded, to send only what changed. */
    val baseline: CronDraft = CronDraft(),
    val saving: Boolean = false,
) {
    /** Interval and once jobs carry a display string, not a cron expression; the dashboard edits those. */
    val scheduleEditable: Boolean get() = editing == null || editing.schedule?.kind.let { it == null || it == "cron" }

    val scheduleError: String?
        get() = if (!scheduleEditable || draft.schedule.trim().split(Regex("\\s+")).size == 5) null else SCHEDULE_ERROR

    val canSave: Boolean
        get() = draft.name.isNotBlank() && draft.prompt.isNotBlank() && scheduleError == null && !saving

    /** Delivery chips: the server's targets, or a lone "Session only" when it lists none. */
    val deliveryChoices: List<DeliveryTargetDto>
        get() = targets.ifEmpty { listOf(DeliveryTargetDto(LOCAL_TARGET, "Session only")) }
}

/** Cron list and editor state. [toast] shows a transient message (the app's ToastState). */
class CronViewModel(
    private val source: CronSource,
    private val toast: (String) -> Unit = {},
) : ViewModel() {
    private val _state = MutableStateFlow(CronUiState())
    val state: StateFlow<CronUiState> = _state.asStateFlow()

    private val _saved = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Emits once after a successful save, so the editor screen can close. */
    val saved: SharedFlow<Unit> = _saved.asSharedFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        _state.update { it.copy(loading = true) }
        coroutineScope {
            val jobs = async { source.jobs() }
            val targets = async { source.deliveryTargets() }
            val j = jobs.await()
            val t = targets.await()
            _state.update {
                it.copy(
                    jobs = j.getOrDefault(it.jobs),
                    targets = t.getOrDefault(it.targets),
                    loading = false,
                )
            }
            if (j.isFailure) toast("Couldn’t load jobs")
        }
    }

    fun runNow(id: String) {
        val name = _state.value.jobs.firstOrNull { it.id == id }?.title ?: id
        viewModelScope.launch {
            source.trigger(id)
                .onSuccess { toast("Running $name") }
                .onFailure { toast("Couldn’t run $name") }
        }
    }

    fun pause(id: String) = act(id, "pause") { source.pause(id) }
    fun resume(id: String) = act(id, "resume") { source.resume(id) }
    fun delete(id: String) = act(id, "delete") { source.delete(id) }

    private fun act(id: String, verb: String, call: suspend () -> Result<Unit>) {
        val name = _state.value.jobs.firstOrNull { it.id == id }?.title ?: id
        viewModelScope.launch {
            call().onFailure { toast("Couldn’t $verb $name") }
            load()
        }
    }

    /** Opens the editor on [job], or on a blank draft when null. */
    fun startEdit(job: CronJobDto?) {
        val draft = if (job == null) {
            CronDraft()
        } else {
            CronDraft(
                name = job.name.orEmpty(),
                prompt = job.prompt.orEmpty(),
                schedule = job.expr,
                deliver = (job.deliver as? JsonPrimitive)?.contentOrNull ?: LOCAL_TARGET,
            )
        }
        _state.update {
            it.copy(editorOpen = true, editing = job, isNew = job == null, saving = false, draft = draft, baseline = draft)
        }
    }

    fun closeEditor() {
        _state.update { it.copy(editorOpen = false, editing = null, isNew = false, saving = false) }
    }

    fun updateDraft(name: String? = null, prompt: String? = null, schedule: String? = null, deliver: String? = null) {
        _state.update {
            it.copy(
                draft = it.draft.copy(
                    name = name ?: it.draft.name,
                    prompt = prompt ?: it.draft.prompt,
                    schedule = schedule ?: it.draft.schedule,
                    deliver = deliver ?: it.draft.deliver,
                ),
            )
        }
    }

    fun save() {
        val s = _state.value
        if (!s.canSave) return
        val d = s.draft.copy(name = s.draft.name.trim(), prompt = s.draft.prompt.trim(), schedule = s.draft.schedule.trim().replace(Regex("\\s+"), " "))
        val editing = s.editing
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            val result = if (editing == null) {
                source.create(d.name, d.schedule, d.prompt, d.deliver).map { }
            } else {
                val base = s.baseline
                val changed = buildMap<String, JsonPrimitive> {
                    if (d.name != base.name) put("name", JsonPrimitive(d.name))
                    if (d.prompt != base.prompt) put("prompt", JsonPrimitive(d.prompt))
                    if (s.scheduleEditable && d.schedule != base.schedule) put("schedule", JsonPrimitive(d.schedule))
                    if (d.deliver != base.deliver) put("deliver", JsonPrimitive(d.deliver))
                }
                if (changed.isEmpty()) Result.success(Unit) else source.update(editing.id, JsonObject(changed))
            }
            result
                .onSuccess {
                    closeEditor()
                    _saved.tryEmit(Unit)
                    load()
                }
                .onFailure {
                    _state.update { it.copy(saving = false) }
                    toast("Couldn’t save job")
                }
        }
    }
}
