package app.tsukaima.ui.chat

import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import app.tsukaima.data.HermesState
import app.tsukaima.data.RecentIds
import app.tsukaima.data.api.FileDownload
import app.tsukaima.data.api.ModelOptionsDto
import app.tsukaima.data.api.TranscriptionDto
import app.tsukaima.data.api.VoiceLiveStatusDto
import app.tsukaima.data.api.WorkspacesDto
import app.tsukaima.data.files.FileSaver
import app.tsukaima.data.files.ImagePreview
import app.tsukaima.data.gateway.ConnectionState
import app.tsukaima.data.gateway.Frame
import app.tsukaima.data.gateway.RpcTransport
import app.tsukaima.data.gateway.SessionResumed
import app.tsukaima.data.prefs.Settings
import app.tsukaima.di.AppContainer
import app.tsukaima.domain.chat.ChatItem
import app.tsukaima.domain.chat.ChatReducer
import app.tsukaima.domain.livevoice.LiveTransport
import app.tsukaima.domain.voice.SpeechInput
import app.tsukaima.domain.voice.SpeechOutput
import app.tsukaima.domain.voice.VoiceLanguage
import app.tsukaima.share.SharedContent
import app.tsukaima.share.SharedFile
import java.io.IOException

/**
 * What the chat screen needs from the app: the gateway's events, state and request transport, the shared
 * server-request stream ([app.tsukaima.data.ConnectionManager.serverRequests], never the
 * single-consumer [app.tsukaima.data.gateway.GatewayClient.serverRequests]), server status,
 * settings and a few REST calls. A seam for tests. The suspend calls never throw except on cancellation.
 */
interface ChatBackend {
    val transport: RpcTransport
    val events: Flow<Frame.Event>
    val connection: StateFlow<ConnectionState>
    val serverRequests: Flow<Frame.ServerRequest>

    /** Shared with notifications: skip requests in it, add an id when answering. */
    val answeredRequestIds: MutableSet<String>

    /** Requests the server withdrew (container-wide): never adopted from the replay again. */
    val closedRequestIds: RecentIds
    val hermes: StateFlow<HermesState>

    /** Null until DataStore has loaded. */
    val settings: StateFlow<Settings?>
    suspend fun updateSettings(block: (Settings) -> Settings)

    /** This chat is bound to [live] (stored id [stored]): for notifications that only know the live id. */
    suspend fun bindSession(live: String, stored: String?)
    suspend fun modelOptions(): Result<ModelOptionsDto>
    suspend fun workspaces(): Result<WorkspacesDto>
    suspend fun renameSession(id: String, title: String): Result<Unit>
    suspend fun archiveSession(id: String): Result<Unit>
    suspend fun unarchiveSession(id: String): Result<Unit>

    /** Whether stored session [id] is archived (`GET /api/sessions/{id}`). */
    suspend fun sessionArchived(id: String): Result<Boolean>
    suspend fun deleteSession(id: String): Result<Unit>

    /** A `MEDIA:` file, streaming (`/api/files/download`); the caller closes it. */
    suspend fun download(path: String): Result<FileDownload>

    /**
     * The bytes of an `http(s)` image a reply shows (`![alt](url)`), at most 8 MB; the bearer goes only to the
     * server's own origin.
     */
    suspend fun fetchImage(url: String): Result<ByteArray>

    /** An image on the server's disk ([path] absolute or `~/…`) as a `data:` URL, through `/api/media` (bearer, 25 MiB). */
    suspend fun media(path: String): Result<String>

    /** Where downloads are saved (blocking; run on IO). */
    val fileSaver: FileSaver

    /** Offers the saved [uri] to a viewer app; false when none handles [mime]. */
    fun openFile(uri: String, mime: String): Boolean

    /** Opens an http(s) [url] in the browser; false when no app handles it. */
    fun openUrl(url: String): Boolean

    /** An image preview decoded from [bytes] (downsampled), or null if they are not a decodable image. */
    fun decodePreview(bytes: ByteArray): ImageBitmap?

    /**
     * A tool row's image ([ChatMedia.toolImage]) decoded like [decodePreview], its long side also held to
     * [ImagePreview.MAX_SIDE] (an extreme strip would otherwise be a huge texture).
     */
    fun decodeToolImage(bytes: ByteArray): ImageBitmap? = decodePreview(bytes)
    fun toast(text: String)

    /** One recorded utterance (WAV) through Hermes' speech-to-text relay (`/api/audio/transcribe`). */
    suspend fun transcribe(wav: ByteArray): Result<TranscriptionDto>

    /**
     * A fresh speech recogniser for one voice-mode session (it holds the mic while listening): the phone's, or the
     * server relay, per the speech recognition setting at the time of the call.
     */
    fun speechInput(): SpeechInput

    /**
     * The speech output for one voice-mode session: the phone's text-to-speech (shared), or Hermes' relay, per the
     * speech output setting at the time of the call.
     */
    fun speechOutput(): SpeechOutput

    /** The voice language picker's options, "System default" first (device locales, TTS languages, fallback). */
    fun voiceLanguages(): List<VoiceLanguage>

    /**
     * Whether the user allows the GPT-Live engine ([Settings.liveVoice]); it is used only when the server reports
     * `gpt-live` too ([voiceLiveStatus]). False for backends without a live path.
     *
     * The live members ([liveVoiceEnabled], [voiceLiveStatus], [liveTransport], [startLiveService],
     * [stopLiveService], [liveVoiceEndRequests], [liveVoiceSeconds]) are overridden together: a backend that returns
     * true here must provide [liveTransport] (its default throws) and a [voiceLiveStatus] that can say `gpt-live`.
     */
    val liveVoiceEnabled: Boolean get() = false

    /** The server's voice engine (`GET …/voice-live/status`); a failure means the phone engine. */
    suspend fun voiceLiveStatus(): Result<VoiceLiveStatusDto> = Result.failure(IOException("no live voice"))

    /** A fresh transport for one live voice session. Only called when [liveVoiceEnabled]. */
    fun liveTransport(): LiveTransport = error("no live voice")

    /** A live voice session started: keep mic and connection alive while the screen is off. */
    fun startLiveService() = Unit

    /** The live voice session ended. */
    fun stopLiveService() = Unit

    /** The live voice notification's End action: the chat with a live session open closes it. */
    val liveVoiceEndRequests: Flow<Unit> get() = emptyFlow()

    /** Seconds of the live voice session running now (the chat writes it, the notification shows it). */
    val liveVoiceSeconds: MutableStateFlow<Int> get() = MutableStateFlow(0) // no live path: a write goes nowhere

    /** A share from another app waiting for its chat ([SharedContent.target]); null when none. */
    val pendingShare: StateFlow<SharedContent?> get() = NoPendingShare

    /** Takes [content] off [pendingShare] (compare-and-set to null); false when another chat already took it. */
    fun takeShare(content: SharedContent): Boolean = false

    /** Deletes the cached copy of a shared [file] once its attachment is sent or dropped (off the caller's thread). */
    fun deleteSharedFile(file: SharedFile) = Unit

    /**
     * Records stored session [id] as the chat "Continue last chat" opens ([Settings.lastChatId]); null forgets it.
     * Writes only on a change.
     */
    suspend fun rememberLastChat(id: String?) {
        if (settings.value?.lastChatId != id) updateSettings { it.copy(lastChatId = id) }
    }
}

/** [ChatBackend.pendingShare] for backends without a share channel. */
private val NoPendingShare: StateFlow<SharedContent?> = MutableStateFlow<SharedContent?>(null).asStateFlow()

class ContainerChatBackend(private val c: AppContainer) : ChatBackend {
    override val transport: RpcTransport get() = c.gateway
    override val events get() = c.gateway.events
    override val connection get() = c.gateway.state
    override val serverRequests get() = c.connection.serverRequests
    override val answeredRequestIds get() = c.answeredRequestIds
    override val closedRequestIds get() = c.connection.closedRequestIds
    override val hermes get() = c.connection.hermes
    override val settings get() = c.settings
    override suspend fun updateSettings(block: (Settings) -> Settings) {
        try {
            c.settingsStore.update(block)
        } catch (e: IOException) {
            Log.w("Tsukaima", "chat: saving settings failed", e)
        }
    }
    override suspend fun bindSession(live: String, stored: String?) {
        if (stored == null) return
        c.sessionBindings.bind(live, stored)
        // Also the fallback for a notification whose live id is unknown (e.g. after a process restart).
        if (c.settings.value?.lastSessionId != stored) updateSettings { it.copy(lastSessionId = stored) }
    }
    override suspend fun modelOptions() = c.repository.modelOptions()
    override suspend fun workspaces() = c.repository.workspaces()
    override suspend fun renameSession(id: String, title: String) = c.repository.renameSession(id, title)
    override suspend fun archiveSession(id: String) = c.repository.archiveSession(id, archived = true)
    override suspend fun unarchiveSession(id: String) = c.repository.archiveSession(id, archived = false)
    override suspend fun sessionArchived(id: String) = c.repository.sessionArchived(id)
    override suspend fun deleteSession(id: String) = c.repository.deleteSession(id)
    override suspend fun download(path: String) = c.repository.download(path)
    override suspend fun fetchImage(url: String) = c.repository.fetchImage(url)
    override suspend fun media(path: String) = c.repository.media(path)
    override val fileSaver get() = c.fileSaver
    override fun openFile(uri: String, mime: String) = c.openFile(uri, mime)
    override fun openUrl(url: String) = c.openUrl(url)
    override fun decodePreview(bytes: ByteArray) = ImagePreview.decode(bytes)?.asImageBitmap()
    override fun decodeToolImage(bytes: ByteArray) = ImagePreview.decode(bytes, ImagePreview.MAX_SIDE)?.asImageBitmap()
    override fun toast(text: String) = c.toastState.show(text)
    override suspend fun transcribe(wav: ByteArray) = c.repository.transcribe(wav)
    override fun speechInput() = c.speechInput()
    override fun speechOutput() = c.speechOutput()
    override fun voiceLanguages() = c.voiceLanguages(includeEngine = true)
    override val liveVoiceEnabled get() = c.settings.value?.liveVoice ?: true
    override suspend fun voiceLiveStatus() = c.repository.voiceLiveStatus()
    override fun liveTransport() = c.liveTransport()
    override fun startLiveService() = c.startLiveService()
    override fun stopLiveService() = c.stopLiveService()
    override val liveVoiceEndRequests get() = c.liveVoiceEndRequests
    override val liveVoiceSeconds get() = c.liveVoiceSeconds
    override val pendingShare get() = c.pendingShare
    override fun takeShare(content: SharedContent) = c.pendingShare.compareAndSet(content, null)
    override fun deleteSharedFile(file: SharedFile) {
        c.appScope.launch(Dispatchers.IO) { c.sharedFiles.delete(file) }
    }
}

/** Result of `session.events.since`. */
internal data class Replay(
    val events: List<Frame.Event>,
    val latestSeq: Long?,
    val truncated: Boolean,
    val openRequests: List<JsonObject>,
)

/**
 * What a `session.compress` came to: [message] for the toast, [storedId] when compaction rotated the session onto a
 * new stored id, and the [info] snapshot (token counters) when the server sent one.
 */
internal data class Compacted(val message: String, val storedId: String?, val info: JsonObject?, val usage: JsonObject? = null)

/** Pure helpers for decoding gateway snapshots into frames the reducer understands. */
internal object ChatSync {
    /**
     * Reads a `session.compress` result (live-probed shape, see the v0.5.0 rulings): the summary's `headline` when
     * present ("Compressed 42 → 12 messages…", or why nothing was removed), else a line from `status`; a lock held
     * elsewhere (`compressed: false`) or a background run (`status: pending`) report the server's `message`.
     */
    fun compacted(result: JsonObject): Compacted {
        val info = result["info"] as? JsonObject
        val status = result.string("status")
        val message = when {
            (result["compressed"] as? JsonPrimitive)?.contentOrNull == "false" ->
                result.string("message") ?: "Compaction is already running elsewhere"
            status == "pending" -> result.string("message") ?: "Compacting in the background"
            else -> (result["summary"] as? JsonObject)?.string("headline")
                ?: if (status == "aborted") "Compaction was aborted" else "Context compacted"
        }
        return Compacted(message, info?.string("stored_session_id")?.takeIf { it.isNotBlank() }, info, result["usage"] as? JsonObject)
    }

    /** The first line of a server notice, trimmed to a toast's length. */
    fun toastLine(text: String): String {
        val line = text.trim().lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        return if (line.length > TOAST_MAX) line.take(TOAST_MAX - 1) + "…" else line
    }

    private const val TOAST_MAX = 160

    /** Card id for a resume's `pending_approval`, which has no server-request id. */
    const val PENDING_PREFIX = "pending-"

    fun replay(result: JsonObject): Replay = Replay(
        events = (result["events"] as? JsonArray).orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            Frame.Event(
                type = o.string("type").orEmpty(),
                sessionId = o.string("session_id").orEmpty(),
                seq = (o["seq"] as? JsonPrimitive)?.longOrNull,
                payload = o["payload"] as? JsonObject ?: JsonObject(emptyMap()),
            )
        },
        latestSeq = (result["latest_seq"] as? JsonPrimitive)?.longOrNull,
        truncated = (result["truncated"] as? JsonPrimitive)?.contentOrNull == "true",
        openRequests = (result["open_requests"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject },
    )

    /**
     * An `open_requests` entry (`{id, method, params}`) as a server request bound to [liveId]: the entry is
     * this session's by construction, but may still name the live id it was issued under.
     */
    fun openRequest(entry: JsonObject, liveId: String): Frame.ServerRequest? {
        val id = entry.string("id") ?: return null
        val method = entry.string("method") ?: return null
        val params = entry["params"] as? JsonObject ?: JsonObject(emptyMap())
        return Frame.ServerRequest(id, method, JsonObject(params + ("session_id" to JsonPrimitive(liveId))))
    }

    /** A resume's `pending_approval` payload as an approval request with a synthetic [PENDING_PREFIX] id. */
    fun pendingApproval(payload: JsonObject, liveId: String): Frame.ServerRequest {
        val requestId = payload.string("request_id").orEmpty()
        return Frame.ServerRequest(
            id = PENDING_PREFIX + requestId,
            method = "approval",
            params = JsonObject(payload + ("session_id" to JsonPrimitive(liveId))),
        )
    }

    /**
     * A resumed session's transcript: its history, plus the reply streamed so far when a turn is running.
     * `messages` holds only rows already persisted (the running turn's user row, not its partial reply); the
     * resume's `inflight.assistant` carries every `message.delta` of the turn so far (live-probed, see the
     * v0.4.0 rulings), shown as a streaming bubble that the live deltas continue.
     */
    fun transcript(r: SessionResumed): List<ChatItem> {
        val history = ChatReducer.fromHistory(r.messages)
        val partial = r.inflight?.string("assistant")?.takeIf { r.running && it.isNotEmpty() } ?: return history
        return history + ChatItem.AssistantMessage(INFLIGHT_ID, partial, streaming = true, reasoning = null)
    }

    /** Item id of the partial reply [transcript] adds for a running turn. */
    const val INFLIGHT_ID = "a-inflight"

    /** Whether the app can answer [card] safely: by its server-request id, or by a non-empty `request_id`. */
    fun answerable(card: ChatItem.Approval): Boolean =
        !card.id.startsWith(PENDING_PREFIX) || card.requestId.isNotEmpty()

    /** Candidate working directories from `/api/chat/workspaces`: default first, then projects and repos. */
    fun workspacePaths(dto: WorkspacesDto): List<String> {
        val projects = dto.projects.flatMap { p ->
            listOfNotNull(p.string("primary_path")) +
                (p["folders"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.string("path") }
        }
        val repos = dto.repos.mapNotNull { it.string("root") ?: it.string("path") }
        return (listOfNotNull(dto.defaultCwd) + projects + repos).filter { it.isNotBlank() }.distinct()
    }

    /** `~/x` for paths under [home]. */
    fun displayPath(path: String, home: String?): String = when {
        home.isNullOrEmpty() -> path
        path == home -> "~"
        path.startsWith("$home/") -> "~" + path.removePrefix(home)
        else -> path
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
}
