package labs.suchattai.tsukaima.data.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import labs.suchattai.tsukaima.data.prefs.Settings
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Hermes dashboard REST client. [baseUrl] has no trailing slash. [settings] supplies the active
 * profile, which is appended as `?profile=` to management endpoints when it is not "default".
 */
class HermesApi(
    private val client: OkHttpClient,
    private val baseUrl: () -> String,
    private val settings: () -> Settings = { Settings() },
) {
    // ---- public / auth -------------------------------------------------------------------

    suspend fun health(): HealthDto = get("api/health", HealthDto.serializer(), managed = false, auth = false)

    suspend fun status(): StatusDto = get("api/status", StatusDto.serializer(), managed = false, auth = false)

    suspend fun systemStats(): SystemStatsDto = get("api/system/stats", SystemStatsDto.serializer(), managed = false)

    suspend fun authProviders(): List<String> =
        get("api/auth/providers", JsonObject.serializer(), managed = false, auth = false)["providers"]
            ?.jsonArray.orEmpty()
            .mapNotNull { (it as? JsonObject)?.get("name")?.jsonPrimitive?.content }

    suspend fun me(): AuthMeDto = get("api/auth/me", AuthMeDto.serializer(), managed = false)

    suspend fun wsTicket(): String =
        send("POST", "api/auth/ws-ticket", JsonObject.serializer(), managed = false)["ticket"]!!.jsonPrimitive.content

    /** Blocking password login (used by the auth interceptor on the OkHttp thread). Returns the session token. */
    fun loginBlocking(username: String, password: String): String {
        val body = buildJsonObject {
            put("provider", "basic")
            put("username", username)
            put("password", password)
        }
        val request = Request.Builder()
            .url(url("auth/password-login", managed = false))
            .post(HermesJson.encodeToString(JsonObject.serializer(), body).toRequestBody(JSON))
            .tag(NoAuth::class.java, NoAuth())
            .build()
        client.newCall(request).execute().use { response ->
            when (response.code) {
                401 -> throw AuthException("Invalid username or password (401)")
                // Transient: not a credentials verdict, so no re-login latch and no sign-in prompt.
                429 -> throw ApiException(429, "Login rate limited by server (429)")
                503 -> throw ApiException(503, "Login unavailable on server (503)")
            }
            if (!response.isSuccessful) throw ApiException.fromResponse(response.code, response.body.string())
            return response.headers("Set-Cookie").firstNotNullOfOrNull { parseSessionCookie(it) }
                ?: throw ApiException(response.code, "Login response carried no $SESSION_COOKIE cookie")
        }
    }

    suspend fun login(username: String, password: String): String =
        withContext(Dispatchers.IO) { loginBlocking(username, password) }

    // ---- sessions ------------------------------------------------------------------------

    /**
     * `GET /api/sessions`. [limit] is clamped to the server's `0..100` (it answers 422 outside it).
     * [archived] is `exclude` (server default) / `only` / `include`; [excludeSources] is a comma list.
     */
    suspend fun sessions(
        limit: Int = MAX_SESSIONS,
        archived: String? = null,
        excludeSources: String? = null,
    ): List<SessionDto> =
        get(
            "api/sessions", "sessions", SessionDto.serializer(),
            "limit" to limit.coerceIn(0, MAX_SESSIONS).toString(),
            "archived" to archived,
            "exclude_sources" to excludeSources,
        )

    /** The drawer's list: archived ones too (split client-side), without cron-run sessions. */
    suspend fun drawerSessions(): List<SessionDto> =
        sessions(MAX_SESSIONS, archived = "include", excludeSources = "cron")

    suspend fun renameSession(id: String, title: String) {
        sendUnit("PATCH", "api/sessions", id, body = withProfile { put("title", title) })
    }

    suspend fun archiveSession(id: String, archived: Boolean) {
        sendUnit("PATCH", "api/sessions", id, body = withProfile { put("archived", archived) })
    }

    suspend fun deleteSession(id: String) {
        sendUnit("DELETE", "api/sessions", id)
    }

    suspend fun modelOptions(): ModelOptionsDto = get("api/model/options", ModelOptionsDto.serializer())

    suspend fun workspaces(): WorkspacesDto = get("api/chat/workspaces", WorkspacesDto.serializer())

    // ---- cron ----------------------------------------------------------------------------

    suspend fun cronJobs(): List<CronJobDto> = get("api/cron/jobs", ListSerializer(CronJobDto.serializer()))

    suspend fun createCronJob(name: String, schedule: String, prompt: String, deliver: String): CronJobDto =
        send(
            "POST", "api/cron/jobs", CronJobDto.serializer(),
            body = buildJsonObject {
                put("name", name)
                put("schedule", schedule)
                put("prompt", prompt)
                put("deliver", deliver)
            },
        )

    suspend fun updateCronJob(id: String, updates: JsonObject) {
        sendUnit("PUT", "api/cron/jobs", id, body = buildJsonObject { put("updates", updates) })
    }

    suspend fun pauseCronJob(id: String) {
        sendUnit("POST", "api/cron/jobs", id, "pause")
    }

    suspend fun resumeCronJob(id: String) {
        sendUnit("POST", "api/cron/jobs", id, "resume")
    }

    suspend fun triggerCronJob(id: String) {
        sendUnit("POST", "api/cron/jobs", id, "trigger")
    }

    suspend fun deleteCronJob(id: String) {
        sendUnit("DELETE", "api/cron/jobs", id)
    }

    suspend fun deliveryTargets(): List<DeliveryTargetDto> =
        get("api/cron/delivery-targets", "targets", DeliveryTargetDto.serializer())

    // ---- pairing / skills / toolsets / profiles ------------------------------------------

    suspend fun pairing(): PairingDto = get("api/pairing", PairingDto.serializer())

    suspend fun approvePairing(platform: String, requestId: String) {
        sendUnit(
            "POST", "api/pairing/approve",
            body = withProfile {
                put("platform", platform)
                put("request_id", requestId)
            },
        )
    }

    suspend fun revokePairing(platform: String, userId: String) {
        sendUnit(
            "POST", "api/pairing/revoke",
            body = withProfile {
                put("platform", platform)
                put("user_id", userId)
            },
        )
    }

    suspend fun skills(): List<SkillDto> = get("api/skills", ListSerializer(SkillDto.serializer()))

    suspend fun toggleSkill(name: String, enabled: Boolean) {
        sendUnit(
            "PUT", "api/skills/toggle",
            body = buildJsonObject {
                put("name", name)
                put("enabled", enabled)
            },
        )
    }

    suspend fun toolsets(): List<ToolsetDto> = get("api/tools/toolsets", ListSerializer(ToolsetDto.serializer()))

    suspend fun profiles(): ProfilesDto = get("api/profiles", ProfilesDto.serializer(), managed = false)

    suspend fun activeProfile(): String =
        get("api/profiles/active", JsonObject.serializer(), managed = false)["active"]!!.jsonPrimitive.content

    // ---- logs / usage / gateway ----------------------------------------------------------

    suspend fun logs(file: String, lines: Int = 200, level: String? = null): LogsDto =
        get(
            "api/logs", LogsDto.serializer(),
            "file" to file, "lines" to lines.toString(), "level" to level,
        )

    suspend fun usage(days: Int = 30): UsageDto =
        get("api/analytics/usage", UsageDto.serializer(), "days" to days.toString())

    suspend fun gatewayStart() {
        sendUnit("POST", "api/gateway/start")
    }

    suspend fun gatewayStop() {
        sendUnit("POST", "api/gateway/stop")
    }

    suspend fun gatewayRestart() {
        sendUnit("POST", "api/gateway/restart")
    }

    // ---- plumbing ------------------------------------------------------------------------

    private suspend fun <T> get(
        path: String,
        serializer: KSerializer<T>,
        vararg query: Pair<String, String?>,
        managed: Boolean = true,
        auth: Boolean = true,
    ): T = call(newRequest("GET", path, null, auth, managed, query)) { HermesJson.decodeFromString(serializer, it) }

    /** GET of a wrapper object like `{"sessions":[…]}`; returns the array under [key] decoded with [element]. */
    private suspend fun <T> get(
        path: String,
        key: String,
        element: KSerializer<T>,
        vararg query: Pair<String, String?>,
    ): List<T> = call(newRequest("GET", path, null, true, true, query)) { text ->
        val array = HermesJson.parseToJsonElement(text).jsonObject[key]?.jsonArray.orEmpty()
        array.map { HermesJson.decodeFromJsonElement(element, it) }
    }

    private suspend fun <T> send(
        method: String,
        path: String,
        serializer: KSerializer<T>,
        body: JsonObject = EMPTY_BODY,
        managed: Boolean = true,
    ): T = call(newRequest(method, path, body, true, managed, emptyArray())) { HermesJson.decodeFromString(serializer, it) }

    /** Mutation whose response body is ignored. Extra [segments] are appended (and percent-encoded) to [path]. */
    private suspend fun sendUnit(method: String, path: String, vararg segments: String, body: JsonObject? = null) {
        val effectiveBody = if (method == "DELETE") body else body ?: EMPTY_BODY
        call(newRequest(method, path, effectiveBody, true, true, emptyArray(), segments)) { }
    }

    private suspend fun <T> call(request: Request, decode: (String) -> T): T =
        withContext(Dispatchers.IO) {
            client.newCall(request).execute().use { response ->
                val text = response.body.string()
                if (response.code == 401) throw AuthException()
                if (!response.isSuccessful) throw ApiException.fromResponse(response.code, text)
                try {
                    decode(text)
                } catch (e: SerializationException) {
                    throw ApiException(response.code, "Unexpected response from ${request.url.encodedPath}: ${e.message}")
                }
            }
        }

    private fun newRequest(
        method: String,
        path: String,
        body: JsonObject?,
        auth: Boolean,
        managed: Boolean,
        query: Array<out Pair<String, String?>>,
        segments: Array<out String> = emptyArray(),
    ): Request {
        val builder = Request.Builder().url(url(path, managed, query, segments))
        builder.method(method, body?.let { HermesJson.encodeToString(JsonObject.serializer(), it).toRequestBody(JSON) })
        if (!auth) builder.tag(NoAuth::class.java, NoAuth())
        return builder.build()
    }

    private fun url(
        path: String,
        managed: Boolean,
        query: Array<out Pair<String, String?>> = emptyArray(),
        segments: Array<out String> = emptyArray(),
    ): HttpUrl {
        val builder = baseUrl().toHttpUrl().newBuilder().addPathSegments(path)
        segments.forEach { builder.addPathSegment(it) }
        query.forEach { (k, v) -> if (v != null) builder.addQueryParameter(k, v) }
        val profile = settings().profile
        if (managed && profile != DEFAULT_PROFILE) builder.addQueryParameter("profile", profile)
        return builder.build()
    }

    /**
     * A JSON body plus `"profile"` when the active profile is not "default": PATCH /api/sessions/{id} and the
     * pairing mutations read the profile from the body only (the `?profile=` query is ignored there).
     */
    private fun withProfile(block: JsonObjectBuilder.() -> Unit): JsonObject =
        buildJsonObject {
            block()
            val profile = settings().profile
            if (profile != DEFAULT_PROFILE) put("profile", profile)
        }

    /**
     * Behind HTTPS the dashboard prefixes its cookies (`__Host-hermes_session_at`, see Hermes
     * `dashboard_auth/cookies.py`), so the name is matched with and without the RFC 6265bis prefixes.
     */
    private fun parseSessionCookie(header: String): String? {
        val pair = header.substringBefore(';').trim()
        val name = pair.substringBefore('=')
        if (!pair.contains('=') || COOKIE_PREFIXES.none { name == it + SESSION_COOKIE }) return null
        return pair.substringAfter('=').takeIf { it.isNotEmpty() }
    }

    private companion object {
        const val SESSION_COOKIE = "hermes_session_at"
        val COOKIE_PREFIXES = listOf("", "__Host-", "__Secure-")
        const val DEFAULT_PROFILE = "default"
        /** `le=100` on the server's `limit` query. */
        const val MAX_SESSIONS = 100
        val JSON = "application/json; charset=utf-8".toMediaType()
        val EMPTY_BODY = JsonObject(emptyMap())
    }
}
