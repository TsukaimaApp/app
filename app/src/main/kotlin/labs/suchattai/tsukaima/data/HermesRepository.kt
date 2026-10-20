package labs.suchattai.tsukaima.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import labs.suchattai.tsukaima.data.api.AuthException
import labs.suchattai.tsukaima.data.api.AuthMeDto
import labs.suchattai.tsukaima.data.api.CronJobDto
import labs.suchattai.tsukaima.data.api.DeliveryTargetDto
import labs.suchattai.tsukaima.data.api.HealthDto
import labs.suchattai.tsukaima.data.api.HermesApi
import labs.suchattai.tsukaima.data.api.LogsDto
import labs.suchattai.tsukaima.data.api.ModelOptionsDto
import labs.suchattai.tsukaima.data.api.PairingDto
import labs.suchattai.tsukaima.data.api.ProfilesDto
import labs.suchattai.tsukaima.data.api.SessionDto
import labs.suchattai.tsukaima.data.api.SkillDto
import labs.suchattai.tsukaima.data.api.StatusDto
import labs.suchattai.tsukaima.data.api.SystemStatsDto
import labs.suchattai.tsukaima.data.api.ToolsetDto
import labs.suchattai.tsukaima.data.api.UsageDto
import labs.suchattai.tsukaima.data.api.WorkspacesDto
import labs.suchattai.tsukaima.data.prefs.CredentialStore
import labs.suchattai.tsukaima.data.prefs.ServerUrl
import labs.suchattai.tsukaima.data.prefs.SettingsStore

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
     * Forgets the stored password and token; the gateway disconnects on the connection manager's next sync.
     * Settings (URL, username) are kept to prefill the sign-in form. Call off the main thread if the credential
     * store may not have been touched yet (first access builds the encrypted store).
     */
    fun signOut() {
        creds.clear()
        credsVersion.value++
        connection.clearAuthRequired()
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
    suspend fun profiles(): Result<ProfilesDto> = call { api.profiles() }
    suspend fun activeProfile(): Result<String> = call { api.activeProfile() }

    suspend fun logs(file: String, lines: Int = 200, level: String? = null): Result<LogsDto> =
        call { api.logs(file, lines, level) }
    suspend fun usage(days: Int = 30): Result<UsageDto> = call { api.usage(days) }
    suspend fun gatewayStart(): Result<Unit> = call { api.gatewayStart() }
    suspend fun gatewayStop(): Result<Unit> = call { api.gatewayStop() }
    suspend fun gatewayRestart(): Result<Unit> = call { api.gatewayRestart() }

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
