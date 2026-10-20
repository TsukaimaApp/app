package labs.suchattai.tsukaima.data.gateway

import kotlinx.serialization.json.JsonObject
import java.io.IOException

/** What chat features need from the gateway connection: client→server requests and answers to server requests. */
interface RpcTransport {
    /**
     * Sends [method] and suspends until the matching response.
     * @throws RpcException the server answered with an error frame.
     * @throws GatewayClosedException the connection closed (or was never wanted) while the request was pending.
     * @throws IOException (plain, not a subclass) when no response arrives within the implementation's timeout.
     */
    suspend fun request(method: String, params: JsonObject = JsonObject(emptyMap())): JsonObject

    /**
     * [request] with its own deadline [timeoutMs], for methods that legitimately run long (`session.compress`
     * summarizes with a model call). Implementations without a deadline of their own just [request].
     */
    suspend fun requestSlow(method: String, params: JsonObject, timeoutMs: Long): JsonObject = request(method, params)

    /**
     * Answers a server→client request ([Frame.ServerRequest]) with [result], echoing its [id]. Returns
     * whether the frame was enqueued on a live connection; false means it was not sent.
     */
    fun respond(id: String, result: JsonObject): Boolean

    /** Declines a server→client request with a JSON-RPC error frame; same return contract as [respond]. */
    fun respondError(id: String, code: Int, message: String): Boolean
}

/** The server answered a request with a JSON-RPC error frame. */
class RpcException(val code: Int, message: String) : IOException(message)

/** The socket closed, or the client was disconnected, while a request was pending. */
class GatewayClosedException : IOException("gateway closed")
