package labs.suchattai.tsukaima.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.contentOrNull
import labs.suchattai.tsukaima.data.ConnectionManager
import labs.suchattai.tsukaima.data.HermesRepository
import labs.suchattai.tsukaima.data.api.AuthException
import labs.suchattai.tsukaima.data.api.ModelOptionsDto
import labs.suchattai.tsukaima.data.api.PairingDto
import labs.suchattai.tsukaima.data.api.ProfileDto
import labs.suchattai.tsukaima.data.api.SkillDto
import labs.suchattai.tsukaima.data.api.ToolsetDto
import labs.suchattai.tsukaima.data.prefs.CredentialStore
import labs.suchattai.tsukaima.data.prefs.ServerUrl
import labs.suchattai.tsukaima.data.prefs.SettingsStore
import labs.suchattai.tsukaima.data.prefs.ThemeMode
import java.util.Locale

/** One pending or approved pairing entry, flattened from the API's loosely typed objects. */
data class PairingEntry(
    val platform: String,
    /** Display name of the person (falls back to the user id). */
    val who: String,
    /** Pending: the request id to approve. Approved: empty. */
    val requestId: String,
    /** The platform user id: shown in a pending card's code slot, and what revoke needs. */
    val userId: String,
)

/**
 * Everything the Settings screen shows. [serverUrl], [username] and [password] are the form's current
 * input; [storedUrl] is what the app is actually signed in to. [password] is never filled from storage:
 * [passwordSaved] only says one exists.
 */
data class SettingsUiState(
    val serverUrl: String = "",
    val username: String = "",
    val password: String = "",
    val passwordSaved: Boolean = false,
    val storedUrl: String = "",
    val signingIn: Boolean = false,
    val signInError: String? = null,
    val testing: Boolean = false,
    val testResult: String? = null,
    val profiles: List<ProfileDto> = emptyList(),
    val profile: String = "default",
    /** (provider, model) pairs of the current provider. */
    val models: List<Pair<String, String>> = emptyList(),
    val model: String? = null,
    val skills: List<SkillDto> = emptyList(),
    val toolsets: List<ToolsetDto> = emptyList(),
    val pairing: PairingDto? = null,
    val pendingPairing: List<PairingEntry> = emptyList(),
    val approvedPairing: List<PairingEntry> = emptyList(),
    val usage: String? = null,
    val theme: ThemeMode = ThemeMode.System,
    val materialYou: Boolean = false,
    val simulateOffline: Boolean = false,
) {
    /** The URL field was edited to a different server than the one signed in to, and not yet signed in again. */
    val urlChangedWithoutSignIn: Boolean
        get() = storedUrl.isNotEmpty() && serverUrl.isNotBlank() &&
            (ServerUrl.normalize(serverUrl) ?: serverUrl) != storedUrl
}

class SettingsViewModel(
    private val repo: HermesRepository,
    private val settings: SettingsStore,
    private val creds: CredentialStore,
    private val connection: ConnectionManager,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    private var seeded = false

    private val _signedIn = Channel<Unit>(Channel.CONFLATED)

    /** One event per successful [signIn]; the screen decides whether that means leaving first-run. */
    val signedIn: Flow<Unit> = _signedIn.receiveAsFlow()

    init {
        viewModelScope.launch {
            settings.settings.collect { s ->
                val firstLoad = !seeded
                seeded = true
                _state.update {
                    it.copy(
                        storedUrl = s.serverUrl,
                        profile = s.profile,
                        model = s.model ?: it.model,
                        theme = s.theme,
                        materialYou = s.materialYou,
                        // Seed the form once; later emissions (our own writes) must not clobber typing.
                        serverUrl = if (firstLoad && it.serverUrl.isEmpty()) s.serverUrl else it.serverUrl,
                        username = if (firstLoad && it.username.isEmpty()) s.username else it.username,
                    )
                }
                if (firstLoad) {
                    val saved = withContext(io) { creds.password() != null }
                    _state.update { it.copy(passwordSaved = saved) }
                    if (saved && s.serverUrl.isNotEmpty()) reload()
                }
            }
        }
        viewModelScope.launch { connection.simulateOffline.collect { v -> _state.update { it.copy(simulateOffline = v) } } }
    }

    // ---- form ----------------------------------------------------------------------------

    fun setServerUrl(v: String) = _state.update { it.copy(serverUrl = v, signInError = null) }
    fun setUsername(v: String) = _state.update { it.copy(username = v, signInError = null) }
    fun setPassword(v: String) = _state.update { it.copy(password = v, signInError = null) }

    /** Health check against the stored server; the result is "Hermes <version>" or the error text. */
    fun testConnection() {
        if (_state.value.testing) return
        _state.update { it.copy(testing = true, testResult = null) }
        viewModelScope.launch {
            val input = _state.value.serverUrl
            val target = ServerUrl.normalize(input)
            val text = if (target == null) {
                "Enter a valid server URL"
            } else {
                repo.health(target).fold(
                    onSuccess = { h -> if (h.version.isNullOrBlank()) "Hermes reachable" else "Hermes ${h.version}" },
                    onFailure = { "Can't reach Hermes at $target" },
                )
            }
            _state.update { it.copy(testing = false, testResult = text) }
        }
    }

    /** UI entry point; see [signIn]. */
    fun onSignIn() {
        viewModelScope.launch { signIn() }
    }

    /**
     * Signs in with the form's values. Nothing is saved unless the server accepts them (see
     * [HermesRepository.signIn]). Failure leaves [SettingsUiState.signInError] set and the form intact.
     */
    suspend fun signIn(): Result<Unit> {
        val s = _state.value
        if (s.signingIn) return Result.failure(IllegalStateException("Sign-in already in progress"))
        if (s.password.isEmpty()) {
            val e = IllegalArgumentException("Enter your password")
            _state.update { it.copy(signInError = e.message) }
            return Result.failure(e)
        }
        _state.update { it.copy(signingIn = true, signInError = null) }
        val result = repo.signIn(s.serverUrl, s.username, s.password)
        result.onSuccess {
            _state.update {
                it.copy(
                    signingIn = false,
                    password = "",
                    passwordSaved = true,
                    serverUrl = ServerUrl.normalize(s.serverUrl) ?: s.serverUrl,
                    storedUrl = ServerUrl.normalize(s.serverUrl) ?: it.storedUrl,
                )
            }
            _signedIn.trySend(Unit)
            reload()
        }.onFailure { e ->
            _state.update { it.copy(signingIn = false, signInError = signInErrorText(e, s.serverUrl)) }
        }
        return result
    }

    private fun signInErrorText(e: Throwable, url: String): String = when (e) {
        is AuthException -> e.message ?: "Authentication failed"
        is IllegalArgumentException -> "Enter a valid server URL"
        else -> "Can't reach Hermes at ${ServerUrl.normalize(url) ?: url}"
    }

    // ---- remote data ---------------------------------------------------------------------

    private var loadJob: Job? = null

    /** Starts a fresh [loadRemote], cancelling any load still in flight. Never blocks the caller. */
    private fun reload() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch { loadRemote() }
    }

    /** Loads profiles, models, skills, toolsets, pairing and usage. A failed piece keeps its previous value. */
    suspend fun loadRemote() {
        coroutineScope {
            listOf(
                async { repo.profiles().onSuccess { p -> _state.update { it.copy(profiles = p.profiles) } } },
                async { repo.modelOptions().onSuccess(::applyModels) },
                async { repo.skills().onSuccess { v -> _state.update { it.copy(skills = v) } } },
                async { repo.toolsets().onSuccess { v -> _state.update { it.copy(toolsets = v) } } },
                async { repo.pairing().onSuccess(::applyPairing) },
                async { repo.usage(30).onSuccess { u -> _state.update { it.copy(usage = usageSummary(u.totals)) } } },
            ).awaitAll()
        }
    }

    private fun applyModels(o: ModelOptionsDto) {
        val provider = o.provider ?: o.providers.firstOrNull { it.isCurrent == true }?.slug
        val models = provider?.let { slug ->
            o.providers.firstOrNull { it.slug == slug }?.models.orEmpty().map { slug to it }
        }.orEmpty()
        _state.update { it.copy(models = models, model = it.model ?: o.model) }
    }

    private fun applyPairing(p: PairingDto) {
        _state.update {
            it.copy(
                pairing = p,
                pendingPairing = p.pending.mapNotNull(::pairingEntry),
                approvedPairing = p.approved.mapNotNull(::pairingEntry),
            )
        }
    }

    // ---- actions -------------------------------------------------------------------------

    fun setProfile(name: String) {
        _state.update {
            it.copy(
                profile = name,
                skills = emptyList(), toolsets = emptyList(), models = emptyList(), model = null,
                pairing = null, pendingPairing = emptyList(), approvedPairing = emptyList(), usage = null,
            )
        }
        // The API reads the profile from settings, so the load waits for the write; but the write is its own
        // job, chained after any earlier one, so a reload that cancels the load cannot lose it.
        val previous = profileWrite
        val write = viewModelScope.launch {
            previous?.join()
            settings.update { it.copy(profile = name, model = null, modelProvider = null) }
        }
        profileWrite = write
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            write.join()
            loadRemote()
        }
    }

    /** The latest profile write; never cancelled with [loadJob]. */
    private var profileWrite: Job? = null

    /** Saves the model with its provider: a new chat sends both, so a stale provider must not ride along. */
    fun setModel(provider: String, model: String) {
        _state.update { it.copy(model = model) }
        viewModelScope.launch { settings.update { it.copy(model = model, modelProvider = provider) } }
    }

    /** Optimistic; reverted if the server refuses. Toolsets have no counterpart: they are read-only. */
    fun toggleSkill(name: String, enabled: Boolean) {
        setSkill(name, enabled)
        viewModelScope.launch {
            repo.toggleSkill(name, enabled).onFailure { setSkill(name, !enabled) }
        }
    }

    private fun setSkill(name: String, enabled: Boolean) = _state.update { st ->
        st.copy(skills = st.skills.map { if (it.name == name) it.copy(enabled = enabled) else it })
    }

    fun approvePairing(entry: PairingEntry) {
        viewModelScope.launch { repo.approvePairing(entry.platform, entry.requestId); reloadPairing() }
    }

    fun revokePairing(entry: PairingEntry) {
        viewModelScope.launch { repo.revokePairing(entry.platform, entry.userId); reloadPairing() }
    }

    private suspend fun reloadPairing() {
        repo.pairing().onSuccess(::applyPairing)
    }

    fun setTheme(mode: ThemeMode) {
        viewModelScope.launch { settings.update { it.copy(theme = mode) } }
    }

    fun setMaterialYou(on: Boolean) {
        viewModelScope.launch { settings.update { it.copy(materialYou = on) } }
    }

    fun setSimulateOffline(on: Boolean) {
        connection.simulateOffline.value = on
    }
}

internal fun pairingEntry(o: JsonObject): PairingEntry? {
    fun str(vararg keys: String) = keys.firstNotNullOfOrNull { (o[it] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) }
    val platform = str("platform") ?: return null
    val userId = str("user_id", "id").orEmpty()
    return PairingEntry(
        platform = platform,
        who = str("user_name", "username", "name") ?: userId,
        requestId = str("request_id").orEmpty(),
        userId = userId,
    )
}

/** "1.2M tokens · 318 sessions · $4.50" from the usage totals; null when there is nothing to show. */
fun usageSummary(totals: JsonObject?): String? {
    if (totals == null) return null
    fun num(vararg keys: String) = keys.firstNotNullOfOrNull { (totals[it] as? JsonPrimitive)?.doubleOrNull }
    val tokens = listOfNotNull(num("total_input"), num("total_output")).takeIf { it.isNotEmpty() }?.sum()
    val sessions = num("total_sessions")
    val cost = listOf("total_actual_cost", "total_estimated_cost").firstNotNullOfOrNull { k -> num(k)?.takeIf { it > 0 } }
    val parts = listOfNotNull(
        tokens?.let { "${compact(it)} tokens" },
        sessions?.let { "${it.toLong()} sessions" },
        cost?.let { String.format(Locale.US, "$%.2f", it) },
    )
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

private fun compact(n: Double): String = when {
    n >= 1_000_000 -> String.format(Locale.US, "%.1fM", n / 1_000_000)
    n >= 1_000 -> String.format(Locale.US, "%.1fK", n / 1_000)
    else -> n.toLong().toString()
}
