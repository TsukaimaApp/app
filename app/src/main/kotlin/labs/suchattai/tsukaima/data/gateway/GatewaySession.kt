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

/** Typed wrappers over the gateway's session JSON-RPC methods. Optional null params are omitted. */
class GatewaySession(private val transport: RpcTransport) {

    suspend fun create(cwd: String?, model: String?, provider: String? = null, source: String = SOURCE): SessionCreated {
        val params = buildJsonObject {
            put("source", source)
            cwd?.let { put("cwd", it) }
            model?.let { put("model", it) }
            provider?.let { put("provider", it) }
        }
        return decode("session.create", transport.request("session.create", params))
    }

    suspend fun resume(storedId: String): SessionResumed {
        val params = buildJsonObject {
            put("session_id", storedId)
            put("source", SOURCE)
            put("lazy", false)
            put("defer_history", false)
        }
        return decode("session.resume", transport.request("session.resume", params))
    }

    suspend fun history(sessionId: String): List<TranscriptMessage> =
        decode<HistoryResult>("session.history", transport.request("session.history", idParams(sessionId))).messages

    suspend fun list(limit: Int = 200): List<SessionSummary> =
        decode<ListResult>("session.list", transport.request("session.list", buildJsonObject { put("limit", limit) })).sessions

    suspend fun mostRecent(): String? {
        val v = transport.request("session.most_recent")["session_id"]
        return if (v == null || v is JsonNull) null else v.jsonPrimitive.content
    }

    /** Result body (`{status:"streaming",…}`) is intentionally ignored; output arrives as events. */
    suspend fun submit(sessionId: String, text: String) {
        transport.request("prompt.submit", buildJsonObject {
            put("session_id", sessionId)
            put("text", text)
        })
    }

    suspend fun interrupt(sessionId: String) {
        transport.request("session.interrupt", idParams(sessionId))
    }

    suspend fun setCwd(sessionId: String, cwd: String) {
        transport.request("session.cwd.set", buildJsonObject {
            put("session_id", sessionId)
            put("cwd", cwd)
        })
    }

    suspend fun setTitle(sessionId: String, title: String) {
        transport.request("session.title", buildJsonObject {
            put("session_id", sessionId)
            put("title", title)
        })
    }

    suspend fun archive(storedId: String, archived: Boolean) {
        transport.request("session.archive", buildJsonObject {
            put("session_id", storedId)
            put("archived", archived)
        })
    }

    suspend fun delete(storedId: String) {
        transport.request("session.delete", idParams(storedId))
    }

    suspend fun close(sessionId: String) {
        transport.request("session.close", idParams(sessionId))
    }

    suspend fun modelOptions(sessionId: String): ModelOptionsDto =
        decode("model.options", transport.request("model.options", idParams(sessionId)))

    suspend fun eventsSince(sessionId: String, lastSeen: Long): JsonObject =
        transport.request("session.events.since", buildJsonObject {
            put("session_id", sessionId)
            put("last_seen", lastSeen)
        })

    fun respondApproval(requestId: String, choice: ApprovalChoice) {
        transport.respond(requestId, buildJsonObject { put("choice", JsonPrimitive(choice.wire)) })
    }

    /**
     * Answers an approval by its `request_id` rather than a server-request id: for the `pending_approval` a
     * resume reports, which has no `srq-…` id to [respondApproval] to.
     */
    suspend fun approvalRespond(sessionId: String, requestId: String?, choice: ApprovalChoice) {
        transport.request("approval.respond", buildJsonObject {
            put("session_id", sessionId)
            put("choice", choice.wire)
            requestId?.let { put("request_id", it) }
        })
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
    }
}
