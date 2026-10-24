package app.tsukaima.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import app.tsukaima.data.api.AuthException
import app.tsukaima.data.api.AuthMeDto
import app.tsukaima.data.api.CronJobDto
import app.tsukaima.data.api.DeliveryTargetDto
import app.tsukaima.data.api.FileDownload
import app.tsukaima.data.api.HealthDto
import app.tsukaima.data.api.HermesApi
import app.tsukaima.data.api.LogsDto
import app.tsukaima.data.api.ModelOptionsDto
import app.tsukaima.data.api.PairingDto
import app.tsukaima.data.api.ProfilesDto
import app.tsukaima.data.api.SessionDto
import app.tsukaima.data.api.SkillDto
import app.tsukaima.data.api.SpeechDto
import app.tsukaima.data.api.StatusDto
import app.tsukaima.data.api.SystemStatsDto
import app.tsukaima.data.api.ToolsetDto
import app.tsukaima.data.api.TranscriptionDto
import app.tsukaima.data.api.UsageDto
import app.tsukaima.data.api.VoiceConfigDto
import app.tsukaima.data.api.VoiceLiveSessionDto
import app.tsukaima.data.api.VoiceLiveStatusDto
import app.tsukaima.data.api.WorkspacesDto
import app.tsukaima.data.prefs.CredentialStore
import app.tsukaima.data.prefs.ServerUrl
import app.tsukaima.data.prefs.SettingsStore

/**
 * The UI's entry point to Hermes REST: sign-in/out plus every [HermesApi] call, each returning a [Result].
 *
 * No call throws except on cancellation: any [Exception] (IO, HTTP, JSON shape, a malformed stored URL)
 * becomes [Result.failure]. An [AuthException] from a pass-through call also flags
 * [ConnectionManager.authRequired] so the UI can route to sign-in.
 *
 * [loginApi] builds a client bound to a candidate server URL, so [signIn] can verify credentials against a
 * new server before anything is persisted (the shared [api] always targets the stored URL).
 */
class HermesRepository(
    private val api: HermesApi,
    private val settings: SettingsStore,
    private val creds: CredentialStore,
    private val connection: ConnectionManager,
    private val loginApi: (baseUrl: String) -> HermesApi,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : SessionsSource {
    /** Bumped on every credential write so [isConfigured] re-reads the (non-observable) credential store. */
    private val credsVersion = MutableStateFlow(0)

    /** True when a server URL is saved and a password is stored. */
    val isConfigured: Flow<Boolean> = combine(settings.settings, credsVersion) { s, _ ->
        s.serverUrl.isNotEmpty() && creds.password() != null
    }.flowOn(io).distinctUntilChanged()

    /**
     * Normalizes [url], logs in against it, then persists the token, password, URL and username and marks
     * onboarding done. Bad credentials yield `Result.failure(AuthException)` and persist nothing.
     */
    suspend fun signIn(url: String, username: String, password: String): Result<Unit> = attempt {
        val base = ServerUrl.normalize(url) ?: throw IllegalArgumentException("Invalid server URL: '$url'")
        val token = loginApi(base).login(username, password)
        withContext(io) { creds.save(password, token, base) }
        credsVersion.value++
        settings.update { it.copy(serverUrl = base, username = username, onboarded = true) }
        connection.clearAuthRequired()
    }

    /**
     * Sign-out: forgets the stored password and token (off the main thread: the first credential access builds the
     * encrypted store), disconnects the gateway at once ([ConnectionManager.signOut]) and clears `onboarded` and
     * the last session, so the next launch starts at Onboarding. The URL and username are kept to prefill the
     * sign-in form. Never throws except on cancellation; a failed settings write is returned as a failure.
     */
    suspend fun signOut(): Result<Unit> {
        withContext(io) { creds.clear() }
        credsVersion.value++
        connection.signOut()
        return attempt { settings.update { it.copy(onboarded = false, lastSessionId = null, lastChatId = null) } }
    }

    // ---- pass-through --------------------------------------------------------------------

    suspend fun health(): Result<HealthDto> = call { api.health() }

    /** Unauthenticated health check against an arbitrary [url] (the sign-in form's, before it is saved). */
    suspend fun health(url: String): Result<HealthDto> = attempt {
        val base = ServerUrl.normalize(url) ?: throw IllegalArgumentException("Invalid server URL: '$url'")
        loginApi(base).health()
    }

    suspend fun status(): Result<StatusDto> = call { api.status() }
    suspend fun systemStats(): Result<SystemStatsDto> = call { api.systemStats() }
    suspend fun authProviders(): Result<List<String>> = call { api.authProviders() }
    suspend fun me(): Result<AuthMeDto> = call { api.me() }
    suspend fun wsTicket(): Result<String> = call { api.wsTicket() }

    suspend fun sessions(limit: Int): Result<List<SessionDto>> = call { api.sessions(limit) }
    suspend fun renameSession(id: String, title: String): Result<Unit> = call { api.renameSession(id, title) }
    suspend fun archiveSession(id: String, archived: Boolean): Result<Unit> = call { api.archiveSession(id, archived) }
    suspend fun deleteSession(id: String): Result<Unit> = call { api.deleteSession(id) }

    /** The drawer's list (archived included, cron runs excluded); see [HermesApi.drawerSessions]. */
    override suspend fun sessions(): Result<List<SessionDto>> = call { api.drawerSessions() }
    override suspend fun rename(id: String, title: String): Result<Unit> = renameSession(id, title)
    override suspend fun archive(id: String, archived: Boolean): Result<Unit> = archiveSession(id, archived)
    override suspend fun delete(id: String): Result<Unit> = deleteSession(id)
    override suspend fun search(query: String): Result<List<SessionDto>> = call { api.searchSessions(query) }

    suspend fun sessionArchived(id: String): Result<Boolean> = call { api.sessionArchived(id) }

    suspend fun modelOptions(): Result<ModelOptionsDto> = call { api.modelOptions() }
    suspend fun workspaces(): Result<WorkspacesDto> = call { api.workspaces() }

    suspend fun cronJobs(): Result<List<CronJobDto>> = call { api.cronJobs() }
    suspend fun createCronJob(name: String, schedule: String, prompt: String, deliver: String): Result<CronJobDto> =
        call { api.createCronJob(name, schedule, prompt, deliver) }
    suspend fun updateCronJob(id: String, updates: JsonObject): Result<Unit> = call { api.updateCronJob(id, updates) }
    suspend fun pauseCronJob(id: String): Result<Unit> = call { api.pauseCronJob(id) }
    suspend fun resumeCronJob(id: String): Result<Unit> = call { api.resumeCronJob(id) }
    suspend fun triggerCronJob(id: String): Result<Unit> = call { api.triggerCronJob(id) }
    suspend fun deleteCronJob(id: String): Result<Unit> = call { api.deleteCronJob(id) }
    suspend fun deliveryTargets(): Result<List<DeliveryTargetDto>> = call { api.deliveryTargets() }

    suspend fun pairing(): Result<PairingDto> = call { api.pairing() }
    suspend fun approvePairing(platform: String, requestId: String): Result<Unit> =
        call { api.approvePairing(platform, requestId) }
    suspend fun revokePairing(platform: String, userId: String): Result<Unit> = call { api.revokePairing(platform, userId) }
    suspend fun skills(): Result<List<SkillDto>> = call { api.skills() }
    suspend fun toggleSkill(name: String, enabled: Boolean): Result<Unit> = call { api.toggleSkill(name, enabled) }
    suspend fun toolsets(): Result<List<ToolsetDto>> = call { api.toolsets() }
    suspend fun toggleToolset(name: String, enabled: Boolean): Result<Unit> = call { api.toggleToolset(name, enabled) }
    suspend fun profiles(): Result<ProfilesDto> = call { api.profiles() }
    suspend fun activeProfile(): Result<String> = call { api.activeProfile() }

    suspend fun logs(file: String, lines: Int = 200, level: String? = null): Result<LogsDto> =
        call { api.logs(file, lines, level) }
    suspend fun usage(days: Int = 30): Result<UsageDto> = call { api.usage(days) }
    suspend fun gatewayStart(): Result<Unit> = call { api.gatewayStart() }
    suspend fun gatewayStop(): Result<Unit> = call { api.gatewayStop() }
    suspend fun gatewayRestart(): Result<Unit> = call { api.gatewayRestart() }

    /** A file the agent delivered (`MEDIA:`), streaming; the caller closes it. */
    suspend fun download(path: String): Result<FileDownload> = call { api.download(path) }

    /**
     * An image a reply shows (`![alt](url)`): bearer only on the server's own origin, at most 8 MB. A 401 is that
     * image's verdict, never a sign-in prompt, so failures are not reported to the connection ([attempt]).
     */
    suspend fun fetchImage(url: String): Result<ByteArray> = attempt { api.fetchImage(url) }

    /**
     * An image on the server's disk (`vision_analyze`'s `image_url`) through `/api/media`, as its `data:` URL. Like
     * [fetchImage], a 401 is that image's verdict, not reported to the connection ([attempt]).
     */
    suspend fun media(path: String): Result<String> = attempt { api.fetchMediaDataUrl(path) }

    /** One utterance ([mime]: WAV, or Opus in Ogg) through the server's speech-to-text relay. */
    suspend fun transcribe(audio: ByteArray, mime: String = "audio/wav"): Result<TranscriptionDto> =
        call { api.transcribe(audio, mime) }
    suspend fun voiceConfig(): Result<VoiceConfigDto> = call { api.voiceConfig() }

    /** Whether the server runs GPT-Live voice. */
    suspend fun voiceLiveStatus(): Result<VoiceLiveStatusDto> = call { api.voiceLiveStatus() }

    /** Exchanges a WebRTC offer for the GPT-Live answer. */
    suspend fun voiceLiveSession(sdp: String, history: JsonArray?): Result<VoiceLiveSessionDto> =
        call { api.voiceLiveSession(sdp, history) }

    /** One sentence through the server's text-to-speech relay. */
    suspend fun speak(text: String): Result<SpeechDto> = call { api.speak(text) }

    // ---- plumbing ------------------------------------------------------------------------

    /** [attempt] that also reports auth failures to the connection manager. */
    private suspend inline fun <T> call(crossinline block: suspend () -> T): Result<T> =
        attempt(block).onFailure { if (it is AuthException) connection.reportAuthFailure() }

    /** runCatching that rethrows cancellation and lets [Error]s (OOM etc.) crash as they should. */
    private suspend inline fun <T> attempt(crossinline block: suspend () -> T): Result<T> =
        try {
            Result.success(block())
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            Result.failure(e)
        }
}
