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
import labs.suchattai.tsukaima.data.api.CleartextGuard
import labs.suchattai.tsukaima.data.api.ModelOptionsDto
import labs.suchattai.tsukaima.data.api.PairingDto
import labs.suchattai.tsukaima.data.api.ProfileDto
import labs.suchattai.tsukaima.data.api.SkillDto
import labs.suchattai.tsukaima.data.api.ToolsetDto
import labs.suchattai.tsukaima.data.prefs.CredentialStore
import labs.suchattai.tsukaima.data.prefs.NotificationPrefs
import labs.suchattai.tsukaima.data.prefs.ServerUrl
import labs.suchattai.tsukaima.data.prefs.SettingsStore
import labs.suchattai.tsukaima.data.prefs.ThemeMode
import labs.suchattai.tsukaima.data.prefs.VoiceRecognition
import labs.suchattai.tsukaima.diagnostics.CrashRecord
import labs.suchattai.tsukaima.domain.voice.VoiceLanguage
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
    /** The "Sign out?" confirmation is showing. */
    val confirmSignOut: Boolean = false,
    val signingOut: Boolean = false,
    val notifications: NotificationPrefs = NotificationPrefs(),
    /** The approved pairing whose "Revoke?" confirmation is showing. */
    val confirmRevoke: PairingEntry? = null,
    /** Tsukaima holds the device's assistant role (read by the screen on resume; the role needs a Context). */
    val assistantRoleHeld: Boolean = false,
    /** The voice language setting (BCP-47 tag); null = system default. */
    val voiceLanguage: String? = null,
    /**
     * The voice language picker's options, "System default" first: device locales and the fallback list at start,
     * the full list (with the TTS engine's languages) once the sheet opened.
     */
    val voiceLanguages: List<VoiceLanguage> = emptyList(),
    /** Where voice mode recognises speech. */
    val voiceRecognition: VoiceRecognition = VoiceRecognition.Phone,
    /** The server's speech-to-text model (`/api/audio/voice-config`), once probed; null = unknown or none. */
    val sttModel: String? = null,
    /** The crash recorded on an earlier run: Settings shows "The app crashed last time" while set. */
    val lastCrash: CrashRecord? = null,
    /** A problem report is being built (logcat runs for up to a few seconds). */
    val reporting: Boolean = false,
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
    /** App-wide sign-out side effects beyond the repository: notifications, the foreground service. */
    private val onSignedOut: () -> Unit = {},
    /** Shows a transient message (the app's toast). */
    private val toast: (String) -> Unit = {},
    /**
     * The voice language picker's options (`AppContainer.voiceLanguages`), read on [io]: without the TTS engine's
     * languages (`false`, cheap) at start for the row's label, with them (`true`) when the sheet opens.
     */
    private val voiceLanguages: (includeEngine: Boolean) -> List<VoiceLanguage> = { emptyList() },
    /** Builds the problem report (`Diagnostics.buildReport`); blocking, run on [io]. */
    private val buildReport: () -> String = { "" },
    /** The crash recorded on an earlier run (`Diagnostics.lastCrash`); blocking, run on [io]. */
    private val lastCrash: () -> CrashRecord? = { null },
    /** Forgets the recorded crash (`Diagnostics.clearCrash`); blocking, run on [io]. */
    private val clearCrash: () -> Unit = {},
) : ViewModel() {
    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    private var seeded = false

    private val _signedIn = Channel<Unit>(Channel.CONFLATED)

    /** One event per successful [signIn]; the screen decides whether that means leaving first-run. */
    val signedIn: Flow<Unit> = _signedIn.receiveAsFlow()

    private val _signedOut = Channel<Unit>(Channel.CONFLATED)

    /** One event per [signOut]; the app routes to Onboarding. */
    val signedOut: Flow<Unit> = _signedOut.receiveAsFlow()

    private val _reports = Channel<String>(Channel.BUFFERED)

    /** One finished problem report per [reportProblem]; the screen opens the share sheet with it. */
    val reports: Flow<String> = _reports.receiveAsFlow()

    init {
        viewModelScope.launch {
            val crash = withContext(io) { runCatching(lastCrash).getOrNull() }
            _state.update { it.copy(lastCrash = crash) }
        }
        viewModelScope.launch {
            val options = withContext(io) { voiceLanguages(false) }
            _state.update { if (it.voiceLanguages.isEmpty()) it.copy(voiceLanguages = options) else it } // the sheet's list wins
        }
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
                        notifications = s.notifications,
                        voiceLanguage = s.voiceLanguage,
                        voiceRecognition = s.voiceRecognition,
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
            } else if (CleartextGuard.refuses(target)) {
                CleartextGuard.MESSAGE
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
        if (ServerUrl.normalize(s.serverUrl)?.let(CleartextGuard::refuses) == true) {
            _state.update { it.copy(signInError = CleartextGuard.MESSAGE) }
            return Result.failure(CleartextGuard.refused())
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

    private fun signInErrorText(e: Throwable, url: String): String = when {
        e is AuthException -> e.message ?: "Authentication failed"
        e is IllegalArgumentException -> "Enter a valid server URL"
        e.message == CleartextGuard.MESSAGE -> CleartextGuard.MESSAGE
        else -> "Can't reach Hermes at ${ServerUrl.normalize(url) ?: url}"
    }

    fun askSignOut(show: Boolean) = _state.update { it.copy(confirmSignOut = show) }

    /**
     * Signs out ([HermesRepository.signOut]: credentials cleared, gateway disconnected, back to first run), then
     * [onSignedOut] clears notifications and stops the foreground service. The form forgets the password and every
     * server-side list; the URL and username stay for the next sign-in.
     */
    fun signOut() {
        if (_state.value.signingOut) return
        _state.update { it.copy(confirmSignOut = false, signingOut = true) }
        loadJob?.cancel()
        viewModelScope.launch {
            repo.signOut()
            onSignedOut()
            _state.update {
                SettingsUiState(
                    serverUrl = it.serverUrl, username = it.username, storedUrl = it.storedUrl, profile = it.profile,
                    theme = it.theme, materialYou = it.materialYou, simulateOffline = it.simulateOffline,
                    notifications = it.notifications, voiceLanguage = it.voiceLanguage, voiceLanguages = it.voiceLanguages,
                    lastCrash = it.lastCrash,
                )
            }
            _signedOut.trySend(Unit)
        }
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

    /** Optimistic; reverted if the server refuses. */
    fun toggleSkill(name: String, enabled: Boolean) {
        setSkill(name, enabled)
        viewModelScope.launch {
            repo.toggleSkill(name, enabled).onFailure { setSkill(name, !enabled) }
        }
    }

    private fun setSkill(name: String, enabled: Boolean) = _state.update { st ->
        st.copy(skills = st.skills.map { if (it.name == name) it.copy(enabled = enabled) else it })
    }

    /**
     * Enables or disables a toolset for new sessions (`PUT /api/tools/toolsets/{name}`); optimistic, reverted with a
     * toast if the server refuses.
     */
    fun toggleToolset(name: String, enabled: Boolean) {
        setToolset(name, enabled)
        viewModelScope.launch {
            repo.toggleToolset(name, enabled).onFailure {
                setToolset(name, !enabled)
                toast("Couldn’t ${if (enabled) "enable" else "disable"} $name")
            }
        }
    }

    private fun setToolset(name: String, enabled: Boolean) = _state.update { st ->
        st.copy(toolsets = st.toolsets.map { if (it.name == name) it.copy(enabled = enabled) else it })
    }

    /** Turns one kind of notification on or off ([NotificationPrefs]); the coordinator reads it per decision. */
    fun setNotifications(block: (NotificationPrefs) -> NotificationPrefs) {
        _state.update { it.copy(notifications = block(it.notifications)) }
        viewModelScope.launch { settings.update { it.copy(notifications = block(it.notifications)) } }
    }

    /** Pairing approvals/revokes in flight, by entry: a second tap while one runs is ignored. Main thread only. */
    private val pairingInFlight = mutableSetOf<String>()

    /** Approves [entry] and reloads the list; the outcome is toasted, and a double tap sends one request. */
    fun approvePairing(entry: PairingEntry) {
        val key = "approve ${entry.platform} ${entry.requestId}"
        if (!pairingInFlight.add(key)) return
        viewModelScope.launch {
            try {
                repo.approvePairing(entry.platform, entry.requestId)
                    .onSuccess { toast("Approved ${entry.who}") }
                    .onFailure { toast("Couldn’t approve ${entry.who}") }
                reloadPairing()
            } finally {
                pairingInFlight.remove(key)
            }
        }
    }

    /** Opens (or with null closes) the "Revoke?" confirmation for an approved pairing. */
    fun askRevoke(entry: PairingEntry?) = _state.update { it.copy(confirmRevoke = entry) }

    /** Revokes [entry] (`POST /api/pairing/revoke`) and reloads the list; the outcome is toasted, once per tap burst. */
    fun revokePairing(entry: PairingEntry) {
        _state.update { it.copy(confirmRevoke = null) }
        val key = "revoke ${entry.platform} ${entry.userId}"
        if (!pairingInFlight.add(key)) return
        viewModelScope.launch {
            try {
                repo.revokePairing(entry.platform, entry.userId)
                    .onSuccess { toast("Revoked ${entry.who}") }
                    .onFailure { toast("Couldn’t revoke ${entry.who}") }
                reloadPairing()
            } finally {
                pairingInFlight.remove(key)
            }
        }
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

    /** The Language sheet opens: (re)loads its options off the main thread (the TTS engine's list may have come in). */
    fun loadVoiceLanguages() {
        viewModelScope.launch {
            val options = withContext(io) { voiceLanguages(true) }
            _state.update { it.copy(voiceLanguages = options) }
        }
    }

    /** Saves the voice language (null = system default); the next recogniser run and utterance use it. */
    fun setVoiceLanguage(tag: String?) {
        _state.update { it.copy(voiceLanguage = tag) }
        viewModelScope.launch { settings.update { it.copy(voiceLanguage = tag) } }
    }

    /** Saves where voice mode recognises speech; the next voice session uses it. */
    fun setVoiceRecognition(mode: VoiceRecognition) {
        _state.update { it.copy(voiceRecognition = mode) }
        viewModelScope.launch { settings.update { it.copy(voiceRecognition = mode) } }
    }

    /**
     * The speech recognition sheet opens: asks the server which STT model it relays to, for the "On Hermes" label.
     * A failure, or no STT provider, leaves the generic label.
     */
    fun loadSttModel() {
        viewModelScope.launch {
            val stt = repo.voiceConfig().getOrNull()?.stt
            val model = stt?.model?.takeIf { stt.provider != null && stt.provider != "none" && it.isNotBlank() }
            _state.update { it.copy(sttModel = model) }
        }
    }

    fun setAssistantRoleHeld(held: Boolean) = _state.update { it.copy(assistantRoleHeld = held) }

    /** No settings screen for the default assistant could be opened: say where it is. */
    fun assistantSettingsUnavailable() = toast("Open Settings → Default apps → Digital assistant app")

    // ---- support -------------------------------------------------------------------------

    /**
     * "Report a problem": builds the report off the main thread and emits it on [reports]. With [fromCrash] (the
     * crash banner's "Report") the recorded crash is forgotten once the report carries it. A tap while one is being
     * built is ignored.
     */
    fun reportProblem(fromCrash: Boolean = false) {
        if (_state.value.reporting) return
        _state.update { it.copy(reporting = true) }
        viewModelScope.launch {
            val text = withContext(io) { runCatching(buildReport) }
            text.onSuccess { report ->
                _reports.send(report)
                if (fromCrash) dismissCrash()
            }.onFailure { toast("Couldn’t build the report") }
            _state.update { it.copy(reporting = false) }
        }
    }

    /** The crash banner's "Dismiss": hides it and forgets the recorded crash. */
    fun dismissCrash() {
        _state.update { it.copy(lastCrash = null) }
        viewModelScope.launch { withContext(io) { runCatching(clearCrash) } }
    }

    /** The share sheet could not be opened for a finished report. */
    fun reportShareFailed() = toast("Couldn’t open the share sheet")

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

/** The speech recognition option for Hermes: the server's STT [model] when known, else the generic name. */
fun serverRecognitionLabel(model: String?): String = "On Hermes (${model ?: "Whisper"})"
