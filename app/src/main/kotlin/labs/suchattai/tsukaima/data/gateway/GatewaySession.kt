package labs.suchattai.tsukaima.data.gateway

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put
import labs.suchattai.tsukaima.data.api.HermesJson
import labs.suchattai.tsukaima.data.api.ModelOptionsDto
import labs.suchattai.tsukaima.domain.chat.ConnectionAnswer
import java.io.IOException

/**
 * Typed wrappers over the gateway's session JSON-RPC methods. Optional null params are omitted.
 *
 * [profile] is the active Hermes profile (read per call); unless it is "default" (or unset) every request
 * carries it as `profile`, which every contract params object accepts.
 */
class GatewaySession(private val transport: RpcTransport, private val profile: () -> String? = { null }) {

    suspend fun create(cwd: String?, model: String?, provider: String? = null, source: String = SOURCE): SessionCreated {
        val params = buildJsonObject {
            put("source", source)
            cwd?.let { put("cwd", it) }
            model?.let { put("model", it) }
            provider?.let { put("provider", it) }
        }
        return decode("session.create", rpc("session.create", params))
    }

    suspend fun resume(storedId: String): SessionResumed {
        val params = buildJsonObject {
            put("session_id", storedId)
            put("source", SOURCE)
            put("lazy", false)
            put("defer_history", false)
        }
        return decode("session.resume", rpc("session.resume", params))
    }

    suspend fun history(sessionId: String): List<TranscriptMessage> =
        decode<HistoryResult>("session.history", rpc("session.history", idParams(sessionId))).messages

    /** The most recent stored session id, or null when there is none or the reply's id is not a string/number. */
    suspend fun mostRecent(): String? {
        val v = rpc("session.most_recent", JsonObject(emptyMap()))["session_id"] as? JsonPrimitive
        return if (v == null || v is JsonNull) null else v.content
    }

    /**
     * Sends a user turn; its output arrives as events. [voice]: a spoken turn (the server answers with its voice
     * persona); [interrupted]: the user barged in on the previous spoken reply. [truncateBeforeRowId] rewinds first
     * (edit / regenerate): the rows from that user row on are archived before the new turn. The cut is always
     * confirmed, including one that archives several user turns (`confirm_deep_truncate`) or the whole transcript
     * (`confirm_empty_truncate`, editing the first message): the user asked for exactly that rewind. [queued]: sent
     * while a turn runs, the server holds it until that turn ends (result `status` `queued`) instead of applying its
     * `display.busy_input_mode` (by default a redirect of the live turn).
     */
    suspend fun submit(
        sessionId: String,
        text: String,
        voice: Boolean = false,
        interrupted: Boolean = false,
        truncateBeforeRowId: Long? = null,
        queued: Boolean = false,
    ): SubmitResult {
        val result = rpc("prompt.submit", buildJsonObject {
            put("session_id", sessionId)
            put("text", text)
            if (voice) put("voice_turn", true)
            if (interrupted) put("interrupted", true)
            if (queued) put("queued", true)
            if (truncateBeforeRowId != null) {
                put("truncate_before_row_id", truncateBeforeRowId)
                put("confirm_truncate", true)
                put("confirm_deep_truncate", true)
                put("confirm_empty_truncate", true)
            }
        })
        // The turn is running once the call succeeded: a result we cannot read must not turn it into a failed send.
        return runCatching { HermesJson.decodeFromJsonElement<SubmitResult>(result) }.getOrDefault(SubmitResult())
    }

    /**
     * Asks a side question (`prompt.btw`): a detached agent answers from the session's context without touching its
     * history. Returns the `task_id`; the answer arrives as a `btw.complete` event on [sessionId].
     */
    suspend fun btw(sessionId: String, text: String): String = sideTask("prompt.btw", sessionId, text)

    /**
     * Starts a background task (`prompt.background`): a detached agent works on [text] beside the session. Returns
     * the `task_id`; the result arrives as a `background.complete` event on [sessionId].
     */
    suspend fun background(sessionId: String, text: String): String = sideTask("prompt.background", sessionId, text)

    private suspend fun sideTask(method: String, sessionId: String, text: String): String {
        val result = rpc(method, buildJsonObject {
            put("session_id", sessionId)
            put("text", text)
        })
        return (result["task_id"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
            ?: throw IOException("$method: malformed result: no task_id")
    }

    /** Stops the running turn; returns the server's `status` (`interrupted` / `not_interrupted`), null if absent. */
    suspend fun interrupt(sessionId: String): String? =
        (rpc("session.interrupt", idParams(sessionId))["status"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

    suspend fun setCwd(sessionId: String, cwd: String) {
        rpc("session.cwd.set", buildJsonObject {
            put("session_id", sessionId)
            put("cwd", cwd)
        })
    }

    suspend fun setTitle(sessionId: String, title: String) {
        rpc("session.title", buildJsonObject {
            put("session_id", sessionId)
            put("title", title)
        })
    }

    suspend fun archive(storedId: String, archived: Boolean) {
        rpc("session.archive", buildJsonObject {
            put("session_id", storedId)
            put("archived", archived)
        })
    }

    suspend fun delete(storedId: String) {
        rpc("session.delete", idParams(storedId))
    }

    suspend fun close(sessionId: String) {
        rpc("session.close", idParams(sessionId))
    }

    suspend fun modelOptions(sessionId: String): ModelOptionsDto =
        decode("model.options", rpc("model.options", idParams(sessionId)))

    /**
     * Switches live session [sessionId] to [model] of [provider] (`config.set key=model`), for this session only:
     * the value is `"<model> --provider <slug> --session"`, as the desktop picker sends it, so the profile default
     * is never rewritten. While a turn runs the server queues the switch for the next turn (`deferred`); a model
     * its selection guards flag comes back `confirm_required` (nothing switched) until it is re-sent with
     * [confirmExpensive].
     */
    suspend fun switchModel(sessionId: String, model: String, provider: String, confirmExpensive: Boolean = false): ModelSwitched =
        decode("config.set", rpc("config.set", buildJsonObject {
            put("session_id", sessionId)
            put("key", "model")
            put("value", "$model --provider $provider --session")
            if (confirmExpensive) put("confirm_expensive_model", true)
        }))

    /**
     * Sets the session's reasoning effort (`config.set key=reasoning`, session-scoped: no `scope`, so the server
     * keeps it on the session and never writes the profile default). Returns the value the server reports.
     */
    suspend fun setReasoning(sessionId: String, level: String): String? =
        (rpc("config.set", buildJsonObject {
            put("session_id", sessionId)
            put("key", "reasoning")
            put("value", level)
        })["value"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

    /** `session.usage`: the session's token counters and context occupancy (`context_percent`, …). */
    suspend fun usage(sessionId: String): JsonObject = rpc("session.usage", idParams(sessionId))

    /**
     * Compacts the idle session's context (`session.compress`): the result has `status` (`compressed` /
     * `aborted` / `pending`), a `summary` with a `headline`, `info` and `usage`; a lock held elsewhere answers
     * `compressed: false` with a `message`. Summarizing is a model call, so the deadline is [COMPRESS_TIMEOUT_MS].
     */
    suspend fun compress(sessionId: String): JsonObject =
        rpc("session.compress", idParams(sessionId), COMPRESS_TIMEOUT_MS)

    suspend fun eventsSince(sessionId: String, lastSeen: Long): JsonObject =
        rpc("session.events.since", buildJsonObject {
            put("session_id", sessionId)
            put("last_seen", lastSeen)
        })

    /**
     * Uploads a non-image file into the session workspace (`file.attach` with the bytes as a [dataUrl]); the
     * result's `ref_text` (`@file:…`) goes into the prompt text.
     */
    suspend fun attachFile(sessionId: String, dataUrl: String, name: String): FileAttached =
        decode("file.attach", rpc("file.attach", buildJsonObject {
            put("session_id", sessionId)
            put("data_url", dataUrl)
            put("name", name)
        }))

    /** Queues an image (raw base64, no `data:` prefix) for the session's next turn (`image.attach_bytes`). */
    suspend fun attachImage(sessionId: String, contentBase64: String, filename: String): ImageAttached =
        decode("image.attach_bytes", rpc("image.attach_bytes", buildJsonObject {
            put("session_id", sessionId)
            put("content_base64", contentBase64)
            put("filename", filename)
        }))

    /**
     * Attaches a PDF (raw base64, no `data:` prefix) by having the server render its pages to images and queue
     * them for the next turn (`pdf.attach`, at most 25 pages per call). An unreadable PDF answers an RPC error.
     * Rendering may take up to the server's 120 s `pdftoppm` limit, so the deadline is [PDF_TIMEOUT_MS].
     */
    suspend fun attachPdf(sessionId: String, contentBase64: String, filename: String): PdfAttached =
        decode("pdf.attach", rpc("pdf.attach", buildJsonObject {
            put("session_id", sessionId)
            put("content_base64", contentBase64)
            put("filename", filename)
        }, PDF_TIMEOUT_MS))

    /** Drops a queued image ([path] as `image.attach_bytes` returned it) before the next turn. */
    suspend fun detachImage(sessionId: String, path: String) {
        rpc("image.detach", buildJsonObject {
            put("session_id", sessionId)
            put("path", path)
        })
    }

    /** Returns whether the answer was enqueued on a live connection ([RpcTransport.respond]). */
    fun respondApproval(requestId: String, choice: ApprovalChoice): Boolean =
        transport.respond(requestId, buildJsonObject { put("choice", JsonPrimitive(choice.wire)) })

    /**
     * Answers a `clarify` request by its server-request id: `{"answers": {qid: answer|null}}`, or `{}` (no
     * `answers` key = cancel all, gateway-contract `ClarifyResult`) when [answers] is null. Returns whether the
     * answer was enqueued on a live connection.
     */
    fun respondClarify(srqId: String, answers: Map<String, String?>?): Boolean =
        transport.respond(
            srqId,
            if (answers == null) {
                JsonObject(emptyMap())
            } else {
                buildJsonObject { put("answers", JsonObject(answers.mapValues { (_, v) -> JsonPrimitive(v) })) }
            },
        )

    /**
     * Answers a masked one-string prompt (`sudo`, `secret`, `vault.*`) by its server-request id:
     * `{"value": value}`, where `""` means skipped / declined (gateway-contract `ValueResult`). Returns whether the
     * answer was enqueued on a live connection.
     */
    fun respondValue(srqId: String, value: String): Boolean =
        transport.respond(srqId, buildJsonObject { put("value", value) })

    /**
     * Answers an approval by its `request_id` rather than a server-request id: for the `pending_approval` a
     * resume reports, which has no `srq-…` id to [respondApproval] to.
     */
    suspend fun approvalRespond(sessionId: String, requestId: String?, choice: ApprovalChoice) {
        rpc("approval.respond", buildJsonObject {
            put("session_id", sessionId)
            put("choice", choice.wire)
            requestId?.let { put("request_id", it) }
        })
    }

    /**
     * Answers connection operation [opId] (`connection.respond {owner, op_id, result}`); [owner] is
     * `{type: "session", session_id}` of the live session the request arrived on. The success value is the server's
     * `settled`: whether this answer ended the operation. Env values in [answer] go to the socket only.
     */
    suspend fun respondConnection(opId: String, owner: JsonObject, answer: ConnectionAnswer): Result<Boolean> = rpcResult {
        val result = rpc("connection.respond", buildJsonObject {
            put("owner", owner)
            put("op_id", opId)
            put("result", answer.toJson())
        })
        (result["settled"] as? JsonPrimitive)?.booleanOrNull ?: false
    }

    /**
     * The browser leg of operation [opId] came back (`connectors.operation.wake {owner, op_id}`): the backend reads
     * the account now instead of at its next tick. Best effort; the operation may have settled meanwhile.
     */
    suspend fun wakeConnection(owner: JsonObject, opId: String): Result<Unit> = rpcResult {
        rpc("connectors.operation.wake", buildJsonObject {
            put("owner", owner)
            put("op_id", opId)
        })
        Unit
    }

    /** [block]'s value, or its failure; cancellation is never caught. */
    private inline fun <T> rpcResult(block: () -> T): Result<T> =
        try {
            Result.success(block())
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            Result.failure(e)
        }

    /** Every request goes through here: adds `profile` when one other than "default" is active. */
    private suspend fun rpc(method: String, params: JsonObject, timeoutMs: Long? = null): JsonObject {
        val active = profile()?.takeIf { it.isNotBlank() && it != DEFAULT_PROFILE }
        val withProfile = if (active == null) params else JsonObject(params + ("profile" to JsonPrimitive(active)))
        return if (timeoutMs == null) transport.request(method, withProfile) else transport.requestSlow(method, withProfile, timeoutMs)
    }

    private fun idParams(id: String) = buildJsonObject { put("session_id", id) }

    private inline fun <reified T> decode(method: String, result: JsonObject): T =
        try {
            HermesJson.decodeFromJsonElement<T>(result)
        } catch (e: SerializationException) {
            throw IOException("$method: malformed result: ${e.message}", e)
        } catch (e: IllegalArgumentException) {
            throw IOException("$method: malformed result: ${e.message}", e)
        }

    companion object {
        const val SOURCE = "tsukaima"

        /** `compression.context_total_ceiling_seconds` (600 s by default) plus a minute of slack. */
        const val COMPRESS_TIMEOUT_MS = 660_000L

        /** `pdf.attach` renders pages with `pdftoppm`, which the server allows 120 s; plus upload and queueing. */
        const val PDF_TIMEOUT_MS = 180_000L
        private const val DEFAULT_PROFILE = "default"
    }
}
