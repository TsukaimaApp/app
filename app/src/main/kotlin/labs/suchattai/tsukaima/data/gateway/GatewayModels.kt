package labs.suchattai.tsukaima.data.gateway

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** One row of a session transcript (`session.create`/`resume`/`history`). */
@Serializable
data class TranscriptMessage(
    val role: String,
    val text: String = "",
    val reasoning: String? = null,
    val name: String? = null,
    val context: String? = null,
    val args: JsonObject? = null,
    @SerialName("tool_call_id") val toolCallId: String? = null,
    val timestamp: Double? = null,
    @SerialName("row_id") val rowId: Long? = null,
)

@Serializable
data class SessionCreated(
    @SerialName("session_id") val sessionId: String,
    @SerialName("stored_session_id") val storedSessionId: String? = null,
    val info: JsonObject = JsonObject(emptyMap()),
    val messages: List<TranscriptMessage> = emptyList(),
)

@Serializable
data class SessionResumed(
    @SerialName("session_id") val sessionId: String,
    @SerialName("stored_session_id") val storedSessionId: String? = null,
    val messages: List<TranscriptMessage>,
    val running: Boolean = false,
    @SerialName("pending_approval") val pendingApproval: JsonObject? = null,
    @SerialName("open_requests") val openRequests: List<JsonObject> = emptyList(),
    /** The running turn's state (`InflightTurn`): `assistant` is the reply text streamed so far. */
    val inflight: JsonObject? = null,
    /** The `session.info` snapshot (model, reasoning effort, token `usage`, …). */
    val info: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class SessionSummary(
    val id: String,
    val title: String? = null,
    val preview: String? = null,
    @SerialName("started_at") val startedAt: Double? = null,
    @SerialName("message_count") val messageCount: Int = 0,
    val source: String? = null,
)

/** `file.attach` result (gateway-contract `FileAttachResult`). */
@Serializable
data class FileAttached(
    val attached: Boolean = false,
    val name: String = "",
    val path: String = "",
    @SerialName("ref_text") val refText: String = "",
)

/** `image.attach_bytes` result (`AttachedImageResult`); [path] is what `image.detach` takes. */
@Serializable
data class ImageAttached(
    val attached: Boolean = false,
    val path: String = "",
    val message: String? = null,
)

/**
 * `config.set key=model` result. [warning] is advice to show (e.g. the context-compression preflight);
 * [confirmRequired] means nothing switched yet and [confirmMessage] says why; [deferred] means a turn is running
 * and the switch applies when the next one starts.
 */
@Serializable
data class ModelSwitched(
    val value: String = "",
    val warning: String = "",
    @SerialName("confirm_required") val confirmRequired: Boolean = false,
    @SerialName("confirm_message") val confirmMessage: String = "",
    val deferred: Boolean = false,
    val scope: String? = null,
)

/** Answer to an `approval` server request; [wire] is the exact string the server expects. */
@Suppress("EnumEntryName")
enum class ApprovalChoice(val wire: String) {
    once("once"), session("session"), always("always"), deny("deny")
}

@Serializable
internal data class HistoryResult(val messages: List<TranscriptMessage>)

@Serializable
internal data class ListResult(val sessions: List<SessionSummary> = emptyList())
