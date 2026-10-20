package labs.suchattai.tsukaima.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import labs.suchattai.tsukaima.data.gateway.Frame
import labs.suchattai.tsukaima.data.gateway.RpcTransport

/**
 * Decides, for each server→client request, whether the app's screens see it. The client advertises
 * `server_requests: true`, so the agent waits for an answer to every request it sends us; a dropped one
 * would block it for the request's whole deadline (clarify: 300 s). So only `approval` is forwarded to
 * subscribers; everything else is answered here at once:
 *
 * - `clarify`: answered with `{"answers": null}` (no answers: the agent proceeds) and a one-shot [hint].
 * - Window-owned bridges (`preview.*`, `terminal.read`, `window.read`, `tour`): declined with
 *   [NOT_SHOWN_CODE], the code by which a client says no window of it shows the session.
 * - `secret`, `sudo` and anything unknown: a JSON-RPC "method not found" error.
 *
 * It also records requests the server withdrew (`request.cancel`, `approval.cancelled`) in [closed], shared
 * by chat and notifications so a withdrawn request replayed later is never shown again. Thread-safe.
 */
class ServerRequestRouter(
    private val transport: RpcTransport,
    val closed: RecentIds = RecentIds(),
    private val hint: (String) -> Unit = {},
    private val logger: (String, Throwable?) -> Unit = { _, _ -> },
) {
    /** Server-request id by the approval's `request_id`, for `approval.cancelled` (which names the latter). */
    private val srqByRequestId = object : LinkedHashMap<String, String>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > CAPACITY
    }

    /** Returns true when [req] must be forwarded to subscribers (approvals); otherwise it was answered here. */
    fun route(req: Frame.ServerRequest): Boolean {
        val method = req.method
        val sent = when {
            method == APPROVAL -> {
                req.params.string("request_id")?.let { synchronized(srqByRequestId) { srqByRequestId[it] = req.id } }
                return true
            }
            method == CLARIFY -> transport.respond(req.id, JsonObject(mapOf("answers" to JsonNull))).also { hint(CLARIFY_HINT) }
            method.startsWith("preview.") || method in WINDOW_OWNED ->
                transport.respondError(req.id, NOT_SHOWN_CODE, NOT_SHOWN_MESSAGE)
            else -> transport.respondError(req.id, METHOD_NOT_FOUND, UNSUPPORTED_MESSAGE)
        }
        if (!sent) logger("gateway: answer to '$method' ${req.id} not sent (no live socket)", null)
        return false
    }

    /** Records withdrawn requests from gateway events; every other event is ignored. */
    fun onEvent(ev: Frame.Event) {
        when (ev.type) {
            "request.cancel" -> ev.payload.string("id")?.let(closed::add)
            "approval.cancelled" -> (ev.payload["request_ids"] as? JsonArray).orEmpty()
                .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                .mapNotNull { synchronized(srqByRequestId) { srqByRequestId.remove(it) } }
                .forEach { closed.add(it) }
        }
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull?.takeIf { it.isNotBlank() }

    companion object {
        /** `NOT_SHOWN_CODE` in tui_gateway/server_requests.py: "no window of this client shows the session". */
        const val NOT_SHOWN_CODE = 4404

        /** JSON-RPC 2.0 "method not found". */
        const val METHOD_NOT_FOUND = -32601

        const val UNSUPPORTED_MESSAGE = "not supported on mobile"
        const val NOT_SHOWN_MESSAGE = "Tsukaima (mobile) cannot show previews, terminals, windows or tours"
        const val CLARIFY_HINT = "Hermes asked a question — answer it on the dashboard"

        private const val APPROVAL = "approval"
        private const val CLARIFY = "clarify"
        private const val CAPACITY = 64
        private val WINDOW_OWNED = setOf("terminal.read", "window.read", "tour")
    }
}
