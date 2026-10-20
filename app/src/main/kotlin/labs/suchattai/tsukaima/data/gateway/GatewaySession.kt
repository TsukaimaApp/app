package labs.suchattai.tsukaima.data.gateway

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import labs.suchattai.tsukaima.data.api.HermesJson
import labs.suchattai.tsukaima.data.api.ModelOptionsDto
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

    suspend fun list(limit: Int = 200): List<SessionSummary> =
        decode<ListResult>("session.list", rpc("session.list", buildJsonObject { put("limit", limit) })).sessions

    suspend fun mostRecent(): String? {
        val v = rpc("session.most_recent", JsonObject(emptyMap()))["session_id"]
        return if (v == null || v is JsonNull) null else v.jsonPrimitive.content
    }

    /** Result body (`{status:"streaming",…}`) is intentionally ignored; output arrives as events. */
    suspend fun submit(sessionId: String, text: String) {
        rpc("prompt.submit", buildJsonObject {
            put("session_id", sessionId)
            put("text", text)
        })
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
     * Answers a masked one-string prompt (`sudo`, `secret`, `vault.unlock_prompt`) by its server-request id:
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

    /** Every request goes through here: adds `profile` when one other than "default" is active. */
    private suspend fun rpc(method: String, params: JsonObject): JsonObject {
        val active = profile()?.takeIf { it.isNotBlank() && it != DEFAULT_PROFILE }
        return transport.request(method, if (active == null) params else JsonObject(params + ("profile" to JsonPrimitive(active))))
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
        private const val DEFAULT_PROFILE = "default"
    }
}
