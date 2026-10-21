package labs.suchattai.tsukaima.data.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import labs.suchattai.tsukaima.data.prefs.Settings
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSource
import java.io.Closeable
import java.io.IOException
import java.net.URLDecoder
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Hermes dashboard REST client. [baseUrl] has no trailing slash. [settings] supplies the active
 * profile, which is appended as `?profile=` to management endpoints when it is not "default".
 * [plainClient] (no auth interceptor) fetches images from other origins ([fetchImage]); it defaults to [client],
 * whose requests to other origins then go out tagged [NoAuth].
 */
class HermesApi(
    private val client: OkHttpClient,
    private val baseUrl: () -> String,
    private val settings: () -> Settings = { Settings() },
    private val plainClient: OkHttpClient = client,
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
        send("POST", "api/auth/ws-ticket", JsonObject.serializer(), managed = false).requireString("ticket", "ws-ticket")

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
        source: String? = null,
    ): List<SessionDto> =
        get(
            "api/sessions", "sessions", SessionDto.serializer(),
            "limit" to limit.coerceIn(0, MAX_SESSIONS).toString(),
            "archived" to archived,
            "exclude_sources" to excludeSources,
            "source" to source,
        )

    /**
     * The drawer's list: archived ones too (split client-side), and the cron-run sessions as their own list
     * (`source=cron`, as the dashboard does) so the 100-row cap applies to each, not to both together.
     */
    suspend fun drawerSessions(): List<SessionDto> = coroutineScope {
        val chats = async { sessions(MAX_SESSIONS, archived = "include", excludeSources = "cron") }
        val cron = async { sessions(MAX_SESSIONS, archived = "include", source = "cron") }
        chats.await() + cron.await()
    }

    suspend fun renameSession(id: String, title: String) {
        sendUnit("PATCH", "api/sessions", id, body = withProfile { put("title", title) })
    }

    suspend fun archiveSession(id: String, archived: Boolean) {
        sendUnit("PATCH", "api/sessions", id, body = withProfile { put("archived", archived) })
    }

    suspend fun deleteSession(id: String) {
        sendUnit("DELETE", "api/sessions", id)
    }

    /**
     * `GET /api/sessions/search?q=&limit=` (session ids first, then FTS over message text, then titles): the
     * `results` array, rows shaped like [SessionDto] plus a `snippet` with `>>>`/`<<<` around the match. A blank
     * [query] answers no results. [limit] is clamped to the server's `1..100`.
     */
    suspend fun searchSessions(query: String, limit: Int = SEARCH_LIMIT): List<SessionDto> =
        get(
            "api/sessions/search", "results", SessionDto.serializer(),
            "q" to query, "limit" to limit.coerceIn(1, MAX_SESSIONS).toString(),
        ).distinctBy { it.id } // FTS can return several rows of one session; the drawer keys rows by id

    /**
     * Whether stored session [id] is archived (`GET /api/sessions/{id}`). The detail row is the raw database row,
     * where `archived` is `0`/`1`, so it is read as a number or a boolean; a missing flag reads as not archived.
     */
    suspend fun sessionArchived(id: String): Boolean {
        val row = call(newRequest("GET", "api/sessions", null, true, true, emptyArray(), arrayOf(id))) {
            HermesJson.parseToJsonElement(it).jsonObject
        }
        val flag = row["archived"] as? JsonPrimitive ?: return false
        return flag.booleanOrNull ?: ((flag.intOrNull ?: 0) != 0)
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

    /**
     * `PUT /api/tools/toolsets/{name}` `{"enabled": bool}`: the toolset's configuration platform (`cli` for most)
     * gains or loses it, for sessions started afterwards. Unknown name → 400.
     */
    suspend fun toggleToolset(name: String, enabled: Boolean) {
        sendUnit("PUT", "api/tools/toolsets", name, body = buildJsonObject { put("enabled", enabled) })
    }

    suspend fun profiles(): ProfilesDto = get("api/profiles", ProfilesDto.serializer(), managed = false)

    suspend fun activeProfile(): String =
        get("api/profiles/active", JsonObject.serializer(), managed = false).requireString("active", "profiles/active")

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

    // ---- files ---------------------------------------------------------------------------

    /**
     * `GET /api/files/download?path=` ([path] absolute or `~/…`), streamed: the caller reads
     * [FileDownload.body] and must close the download. The refusals the server gives (404 gone, 400 not a
     * file, 403 sensitive path, 413 over its 100 MB cap) become an [ApiException] with a user-facing message.
     */
    suspend fun download(path: String): FileDownload {
        val request = Request.Builder().url(url("api/files/download", managed = false, arrayOf("path" to path))).build()
        val response = downloadClient.newCall(request).await()
        if (!response.isSuccessful) {
            val text = response.use { it.body.string() }
            if (response.code == 401) throw AuthException()
            throw downloadError(response.code, text)
        }
        return FileDownload(
            name = response.header("Content-Disposition")?.let(::dispositionFilename)
                ?: path.trimEnd('/').substringAfterLast('/').ifEmpty { "download" },
            mime = response.body.contentType()?.let { "${it.type}/${it.subtype}" } ?: OCTET_STREAM,
            size = response.body.contentLength().takeIf { it >= 0 },
            body = response.body.source(),
        )
    }

    /**
     * Runs the call off the caller's thread; cancelling the coroutine cancels the call, and a response that lands
     * after cancellation is closed rather than leaked (`withContext` would discard it unclosed).
     */
    private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { cancel() }
        enqueue(
            object : Callback {
                override fun onFailure(call: Call, e: java.io.IOException) {
                    if (cont.isActive) cont.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    cont.resume(response) { _, _, _ -> response.close() }
                }
            },
        )
    }

    /**
     * The shared client's 20 s call timeout spans reading the body, which would cut a large file short: downloads
     * get no call timeout, only a read timeout between bytes. Same interceptors (bearer, re-login) and pool.
     */
    private val downloadClient: OkHttpClient by lazy {
        client.newBuilder().callTimeout(0, TimeUnit.MILLISECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    }

    private fun downloadError(code: Int, body: String): ApiException {
        val server = ApiException.fromResponse(code, body)
        val message = when (code) {
            404 -> "File no longer exists on the server"
            400 -> if (server.detail?.contains("not a file", ignoreCase = true) != false) "Not a file" else return server
            403 -> "Server refuses this file"
            413 -> "File too large for the server to send"
            else -> return server
        }
        return ApiException(code, message)
    }

    // ---- images in replies ---------------------------------------------------------------

    /**
     * The bytes of an `http(s)` image a reply shows (`![alt](url)`), at most [IMAGE_MAX_BYTES]. A [url] on the
     * server's own origin ([ImageOrigin.sameOrigin] with [baseUrl]) goes through the authed client, so a
     * server-hosted image gets the bearer (tagged [NoReLogin]: a 401 never triggers a password re-login); any other
     * goes through [plainClient] tagged [NoAuth], so no credential leaves for a third party. The authed fetch follows
     * no redirect ([imageClient]): a same-origin 3xx could otherwise carry the bearer to another host, so it fails
     * like any other non-2xx. Non-2xx (401 included: it is that host's verdict, not a sign-in prompt) →
     * [ApiException]; a body over the cap (by `Content-Length` or as it arrives) → [ImageTooLargeException]; not
     * http(s) → [IllegalArgumentException]; anything else is a (transient) [IOException].
     */
    suspend fun fetchImage(url: String): ByteArray {
        val target = url.toHttpUrlOrNull() ?: throw IllegalArgumentException("Not an http(s) URL")
        val sameOrigin = ImageOrigin.sameOrigin(url, baseUrl())
        val builder = Request.Builder().url(target)
        if (sameOrigin) builder.tag(NoReLogin::class.java, NoReLogin()) else builder.tag(NoAuth::class.java, NoAuth())
        val response = (if (sameOrigin) imageClient else plainClient).newCall(builder.build()).await()
        // `use` first: a cancellation at the dispatcher hop would otherwise discard the response unclosed.
        return response.use {
            withContext(Dispatchers.IO) {
                if (!it.isSuccessful) throw ApiException(it.code, "Image unavailable (${it.code})")
                val body = it.body
                val tooLarge = "Image over ${IMAGE_MAX_BYTES / 1_000_000} MB"
                if (body.contentLength() > IMAGE_MAX_BYTES) throw ImageTooLargeException(tooLarge)
                val source = body.source()
                if (source.request(IMAGE_MAX_BYTES + 1)) throw ImageTooLargeException(tooLarge)
                source.readByteArray()
            }
        }
    }

    /** [client] (bearer, pool) without redirects, for same-origin images ([fetchImage], whose requests skip re-login). */
    private val imageClient: OkHttpClient by lazy {
        client.newBuilder().followRedirects(false).followSslRedirects(false).build()
    }

    /**
     * `GET /api/media?path=` ([path] absolute or `~/…`): an image on the server's disk (what `vision_analyze`
     * looked at) as the `data_url` it answers, `data:image/<type>;base64,…`. Same origin, so the authed client
     * without redirects ([mediaClient]), tagged [NoReLogin]: a 401 is that image's verdict, never a re-login.
     * Non-2xx (403 outside the media roots, 404 gone, 413 over 25 MiB, 415 not an image, 401) → [ApiException]; a body
     * over [MEDIA_MAX_BODY_BYTES] → [ImageTooLargeException]; a 2xx without `data_url` → [ApiException]; anything else
     * is a (transient) [IOException].
     */
    suspend fun fetchMediaDataUrl(path: String): String {
        val request = Request.Builder()
            .url(url("api/media", managed = false, arrayOf("path" to path)))
            .tag(NoReLogin::class.java, NoReLogin())
            .build()
        val response = mediaClient.newCall(request).await()
        // `use` first: a cancellation at the dispatcher hop would otherwise discard the response unclosed.
        return response.use {
            withContext(Dispatchers.IO) {
                if (!it.isSuccessful) throw ApiException(it.code, "Image unavailable (${it.code})")
                val body = it.body
                val tooLarge = "Image over ${MEDIA_MAX_BODY_BYTES / 1_000_000} MB"
                if (body.contentLength() > MEDIA_MAX_BODY_BYTES) throw ImageTooLargeException(tooLarge)
                val source = body.source()
                if (source.request(MEDIA_MAX_BODY_BYTES + 1)) throw ImageTooLargeException(tooLarge)
                val text = source.readUtf8()
                val json = try {
                    HermesJson.parseToJsonElement(text) as? JsonObject
                } catch (e: SerializationException) {
                    null
                }
                json?.requireString("data_url", "Image") ?: throw ApiException(it.code, "Image: unexpected response")
            }
        }
    }

    /** [imageClient] with room for a 25 MiB image (~35 MB as base64): 60 s per call, 30 s between bytes. */
    private val mediaClient: OkHttpClient by lazy {
        imageClient.newBuilder().callTimeout(60, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    }

    // ---- audio ---------------------------------------------------------------------------

    /**
     * `POST /api/audio/transcribe`: one recorded utterance ([mime]: `audio/wav`, or `audio/ogg` for Opus), sent as a
     * base64 `data:` URL labelled with [mime]; Hermes relays it to its
     * configured STT provider (the request carries no language: the server's setting or auto-detection applies).
     * 400 (bad payload, provider error, STT not configured) and 413 (over 25 MB) keep the server's `detail` on the
     * [ApiException]. Uses [transcribeClient]: a long utterance on a slow uplink outlasts the shared timeouts.
     */
    suspend fun transcribe(audio: ByteArray, mime: String = "audio/wav"): TranscriptionDto {
        val body = buildJsonObject {
            put("data_url", "data:$mime;base64," + Base64.getEncoder().encodeToString(audio))
            put("mime_type", mime)
        }
        val request = newRequest("POST", "api/audio/transcribe", body, true, false, emptyArray())
        // Cancellable (await): closing the mic or leaving voice mode aborts the upload.
        val response = transcribeClient.newCall(request).await()
        return withContext(Dispatchers.IO) {
            response.use { decode(request, it) { text -> HermesJson.decodeFromString(TranscriptionDto.serializer(), text) } }
        }
    }

    /**
     * `POST /api/audio/speak`: [text] synthesised by the server's configured TTS provider, in its voice and language
     * settings. 400 (no provider configured, provider error) keeps the server's `detail` on the [ApiException].
     * Cancellable, and on [transcribeClient]: a long sentence on a slow provider outlasts the shared timeouts.
     */
    suspend fun speak(text: String): SpeechDto {
        val request = newRequest("POST", "api/audio/speak", buildJsonObject { put("text", text) }, true, false, emptyArray())
        val response = transcribeClient.newCall(request).await()
        return withContext(Dispatchers.IO) {
            response.use { decode(request, it) { body -> HermesJson.decodeFromString(SpeechDto.serializer(), body) } }
        }
    }

    /** `GET /api/audio/voice-config`, the speech-to-text and text-to-speech parts only (see [VoiceConfigDto]). */
    suspend fun voiceConfig(): VoiceConfigDto = get("api/audio/voice-config", VoiceConfigDto.serializer(), managed = false)

    /** Audio relays ([transcribe], [speak]): 60 s, longer than the shared client's 20 s call timeout. */
    private val transcribeClient: OkHttpClient by lazy {
        client.newBuilder()
            .callTimeout(TRANSCRIBE_TIMEOUT_S, TimeUnit.SECONDS)
            .readTimeout(TRANSCRIBE_TIMEOUT_S, TimeUnit.SECONDS)
            .writeTimeout(TRANSCRIBE_TIMEOUT_S, TimeUnit.SECONDS)
            .build()
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
        withContext(Dispatchers.IO) { client.newCall(request).execute().use { decode(request, it, decode) } }

    /** [response]'s body decoded with [decode]: 401 → [AuthException], other non-2xx → [ApiException]. */
    private fun <T> decode(request: Request, response: Response, decode: (String) -> T): T {
        val text = response.body.string()
        if (response.code == 401) throw AuthException()
        if (!response.isSuccessful) throw ApiException.fromResponse(response.code, text)
        return try {
            decode(text)
        } catch (e: SerializationException) {
            throw ApiException(response.code, "Unexpected response from ${request.url.encodedPath}: ${e.message}")
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
     * The string at [key] of a 2xx reply; a missing, null or non-string value is an [ApiException] (code 200,
     * "[what]: [key] missing in response"), never an NPE.
     */
    private fun JsonObject.requireString(key: String, what: String): String =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw ApiException(200, "$what: $key missing in response")

    /**
     * Behind HTTPS the dashboard prefixes its cookies (`__Host-hermes_session_at`, see Hermes
     * `dashboard_auth/cookies.py`), so the name is matched with and without the RFC 6265bis prefixes.
     */
    private fun parseSessionCookie(header: String): String? {
        val pair = header.substringBefore(';').trim()
        val name = pair.substringBefore('=')
        if (!pair.contains('=') || COOKIE_PREFIXES.none { name == it + SESSION_COOKIE }) return null
        // RFC 6265 allows the value in double quotes (`name="v"`); the quotes are not part of it.
        val raw = pair.substringAfter('=').trim()
        val value = if (raw.length >= 2 && raw.startsWith('"') && raw.endsWith('"')) raw.substring(1, raw.length - 1) else raw
        return value.takeIf { it.isNotEmpty() }
    }

    companion object {
        /** [fetchImage] reads at most this much; a bigger image is a failure. */
        internal const val IMAGE_MAX_BYTES = 8_000_000L

        /** The server sends at most 25 MiB of image ([MEDIA_MAX_IMAGE_BYTES]); base64 in JSON is about 4/3 of that. */
        internal const val MEDIA_MAX_BODY_BYTES = 35_000_000L

        /** `/api/media`'s own cap on the file (413 above it); a decoded `data:` image over it is a failure too. */
        const val MEDIA_MAX_IMAGE_BYTES = 25L * 1024 * 1024

        private const val SESSION_COOKIE = "hermes_session_at"
        private val DISPOSITION_PARAM = Regex(""";\s*([^=;\s]+)\s*=\s*(\"((?:[^\"\\]|\\.)*)\"|[^;]*)""")
        private val QUOTED_PAIR = Regex("""\\(.)""")
        private val COOKIE_PREFIXES = listOf("", "__Host-", "__Secure-")
        private const val DEFAULT_PROFILE = "default"
        /** `le=100` on the server's `limit` query. */
        private const val MAX_SESSIONS = 100
        private const val SEARCH_LIMIT = 20
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private val EMPTY_BODY = JsonObject(emptyMap())
        private const val OCTET_STREAM = "application/octet-stream"
        /** Upload plus server-side recognition of up to 30 s of audio. */
        private const val TRANSCRIBE_TIMEOUT_S = 60L

        /**
         * The filename of a `Content-Disposition` header: RFC 5987 `filename*=UTF-8''…` wins over `filename="…"`
         * (Starlette sends the former for non-ASCII names). Any directory part is dropped; null when absent.
         */
        private fun dispositionFilename(header: String): String? {
            // Quote-aware: a quoted value may hold `;` and `\"` (RFC 6266 quoted-string).
            val params = DISPOSITION_PARAM.findAll(header).associate { m ->
                val quoted = m.groups[3]?.value
                m.groupValues[1].lowercase() to (quoted?.replace(QUOTED_PAIR, "$1") ?: m.groupValues[2].trim())
            }
            val extended = params["filename*"]?.let { v ->
                val charset = v.substringBefore('\'', "").ifEmpty { "UTF-8" }
                runCatching { URLDecoder.decode(v.substringAfterLast('\'').replace("+", "%2B"), charset) }.getOrNull()
            }
            val plain = params["filename"]
            return (extended ?: plain)?.substringAfterLast('/')?.substringAfterLast('\\')?.takeIf { it.isNotBlank() }
        }
    }
}

/**
 * A file streaming in from `/api/files/download`: [name] from `Content-Disposition`, [mime] without parameters,
 * [size] from `Content-Length` when given. [body] is the open response body; closing the download releases the
 * connection.
 */
class FileDownload(val name: String, val mime: String, val size: Long?, val body: BufferedSource) : Closeable {
    override fun close() = body.close()
}
