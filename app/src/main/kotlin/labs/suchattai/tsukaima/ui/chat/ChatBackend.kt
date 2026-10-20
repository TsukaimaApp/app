package labs.suchattai.tsukaima.ui.chat

import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import labs.suchattai.tsukaima.data.HermesState
import labs.suchattai.tsukaima.data.RecentIds
import labs.suchattai.tsukaima.data.api.FileDownload
import labs.suchattai.tsukaima.data.api.ModelOptionsDto
import labs.suchattai.tsukaima.data.api.WorkspacesDto
import labs.suchattai.tsukaima.data.files.FileSaver
import labs.suchattai.tsukaima.data.files.ImagePreview
import labs.suchattai.tsukaima.data.gateway.ConnectionState
import labs.suchattai.tsukaima.data.gateway.Frame
import labs.suchattai.tsukaima.data.gateway.RpcTransport
import labs.suchattai.tsukaima.data.prefs.Settings
import labs.suchattai.tsukaima.di.AppContainer
import labs.suchattai.tsukaima.domain.chat.ChatItem
import java.io.IOException

/**
 * What the chat screen needs from the app: the gateway's events, state and request transport, the shared
 * server-request stream ([labs.suchattai.tsukaima.data.ConnectionManager.serverRequests], never the
 * single-consumer [labs.suchattai.tsukaima.data.gateway.GatewayClient.serverRequests]), server status,
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
    suspend fun deleteSession(id: String): Result<Unit>

    /** A `MEDIA:` file, streaming (`/api/files/download`); the caller closes it. */
    suspend fun download(path: String): Result<FileDownload>

    /** Where downloads are saved (blocking; run on IO). */
    val fileSaver: FileSaver

    /** Offers the saved [uri] to a viewer app; false when none handles [mime]. */
    fun openFile(uri: String, mime: String): Boolean

    /** An image preview decoded from [bytes] (downsampled), or null if they are not a decodable image. */
    fun decodePreview(bytes: ByteArray): ImageBitmap?
    fun toast(text: String)
}

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
    override suspend fun deleteSession(id: String) = c.repository.deleteSession(id)
    override suspend fun download(path: String) = c.repository.download(path)
    override val fileSaver get() = c.fileSaver
    override fun openFile(uri: String, mime: String) = c.openFile(uri, mime)
    override fun decodePreview(bytes: ByteArray) = ImagePreview.decode(bytes)?.asImageBitmap()
    override fun toast(text: String) = c.toastState.show(text)
}

/** Result of `session.events.since`. */
internal data class Replay(
    val events: List<Frame.Event>,
    val latestSeq: Long?,
    val truncated: Boolean,
    val openRequests: List<JsonObject>,
)

/** Pure helpers for decoding gateway snapshots into frames the reducer understands. */
internal object ChatSync {
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
