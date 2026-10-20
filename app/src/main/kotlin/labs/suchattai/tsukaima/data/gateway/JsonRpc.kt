package labs.suchattai.tsukaima.data.gateway

import labs.suchattai.tsukaima.data.api.HermesJson
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

data class RpcError(val code: Int, val message: String, val data: JsonElement? = null)

/** A decoded inbound JSON-RPC frame. */
sealed interface Frame {
    data class Response(val id: String, val result: JsonObject?, val error: RpcError?) : Frame
    data class Event(val type: String, val sessionId: String, val seq: Long?, val payload: JsonObject) : Frame
    data class ServerRequest(val id: String, val method: String, val params: JsonObject) : Frame
    data class Unknown(val raw: String) : Frame
}

/** Pure codec for the Hermes chat WebSocket's JSON-RPC 2.0 frames. */
object JsonRpc {
    /** JSON-RPC "internal error": the code given to a response that carried neither a result nor an error. */
    const val INVALID_RESPONSE = -32603

    fun decodeFrames(text: String): List<Frame> =
        text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }.map { line ->
            try {
                decodeLine(line)
            } catch (e: Throwable) {
                // A malformed line is Unknown: a parse exception, or the stack overflow a pathologically deep one
                // causes in the recursive JSON reader. Any other Error (out of memory, linkage) is not the line's
                // fault and propagates.
                if (e is Error && e !is StackOverflowError) throw e
                Frame.Unknown(line)
            }
        }

    private fun decodeLine(line: String): Frame {
        val obj = HermesJson.parseToJsonElement(line) as? JsonObject ?: return Frame.Unknown(line)
        val id = (obj["id"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
        val method = (obj["method"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        val hasOutcome = "result" in obj || "error" in obj

        if (id != null && hasOutcome) {
            val result = obj["result"] as? JsonObject
            val error = (obj["error"] as? JsonObject)?.let { e ->
                RpcError(
                    code = (e["code"] as? JsonPrimitive)?.intOrNull ?: 0,
                    message = (e["message"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                    data = e["data"]?.takeIf { it !is JsonNull },
                )
            }
            // `"error": null` is no error, so the response must carry a result; without one it answers nothing.
            if (error == null && "result" !in obj) {
                return Frame.Response(id, null, RpcError(INVALID_RESPONSE, "response carried neither result nor error"))
            }
            return Frame.Response(id, result, error)
        }
        if (method == "event") {
            val p = obj["params"] as? JsonObject ?: JsonObject(emptyMap())
            return Frame.Event(
                type = (p["type"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                sessionId = (p["session_id"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull.orEmpty(),
                seq = (p["seq"] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull, // "12" is not a seq
                payload = p["payload"] as? JsonObject ?: JsonObject(emptyMap()),
            )
        }
        if (method != null && id != null) {
            return Frame.ServerRequest(id, method, obj["params"] as? JsonObject ?: JsonObject(emptyMap()))
        }
        return Frame.Unknown(line)
    }

    fun encodeRequest(id: String, method: String, params: JsonObject?): String =
        buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            put("params", params ?: JsonObject(emptyMap()))
        }.toString()

    /** A JSON-RPC error response to a server→client request ([id] echoed). */
    fun encodeError(id: String, code: Int, message: String): String =
        buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put(
                "error",
                buildJsonObject {
                    put("code", code)
                    put("message", message)
                },
            )
        }.toString()

    fun encodeResult(id: String, result: JsonObject): String =
        buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("result", result)
        }.toString()
}
