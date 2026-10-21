package app.tsukaima.data.gateway

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
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
    /** A tool row's result, forwarded only for `write_file` / `patch` / `skill_manage` (where an orphan notice shows). */
    val content: JsonElement? = null,
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
    /** The open connection operation (`connection.request` payload), so a client that missed the event restores its card. */
    @SerialName("pending_connection") val pendingConnection: JsonObject? = null,
    /** The session's todo list snapshot `{todos, revision}` (absent when it never had one); restores the checklist card. */
    @SerialName("todo_state") val todoState: JsonObject? = null,
    /** The running turn's state (`InflightTurn`): `assistant` is the reply text streamed so far. */
    val inflight: JsonObject? = null,
    /** The `session.info` snapshot (model, reasoning effort, token `usage`, …). */
    val info: JsonObject = JsonObject(emptyMap()),
)

/**
 * `prompt.submit` result. [status] is `streaming` for a turn that started (else `queued`, `steered`, …, or null on a
 * typed stop phrase); [userRowId] is the persisted row of the accepted input, absent when not proven yet.
 * [survivorUserRowIds] (after a truncation) are the kept user turns' row ids in order; a null entry means that
 * turn's cached id is stale.
 */
@Serializable
data class SubmitResult(
    val status: String? = null,
    @SerialName("user_row_id") val userRowId: Long? = null,
    @SerialName("survivor_user_row_ids") val survivorUserRowIds: List<Long?>? = null,
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

/** One rendered page of a `pdf.attach` result; [path] is what `image.detach` takes. */
@Serializable
data class PdfPage(val path: String = "", val page: Int = 0, val width: Int = 0, val height: Int = 0)

/**
 * `pdf.attach` result: the PDF's pages are rendered to PNGs and queued as images for the next turn. [count] is
 * the session's whole image queue; [text] is the server's line about the attach (normally
 * `[User attached PDF: name (N page(s))]`).
 */
@Serializable
data class PdfAttached(
    val attached: Boolean = false,
    val filename: String = "",
    @SerialName("pages_attached") val pagesAttached: Int = 0,
    val pages: List<PdfPage> = emptyList(),
    val count: Int = 0,
    val text: String? = null,
) {
    /** The queued page images, in page order. */
    val pagePaths: List<String> get() = pages.map(PdfPage::path).filter(String::isNotBlank)
}

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
