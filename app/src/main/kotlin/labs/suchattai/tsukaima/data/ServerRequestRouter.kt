package labs.suchattai.tsukaima.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import labs.suchattai.tsukaima.data.gateway.Frame
import labs.suchattai.tsukaima.data.gateway.RpcTransport
import labs.suchattai.tsukaima.domain.chat.ClarifyAnswers

/**
 * Decides, for each server→client request, whether the app's screens see it. The client advertises
 * `server_requests: true`, so the agent waits for an answer to every request it sends us; a dropped one
 * would block it for the request's whole deadline (clarify: 300 s). So only `approval`, `clarify` and the
 * masked prompts ([SECRET_METHODS]: `sudo`, `secret`, `vault.unlock_prompt`) are forwarded to subscribers (chat
 * and notifications answer them); everything else is answered here at once:
 *
 * - `clarify` with no askable question: cancelled with `{}` (no `answers` = cancel all), as the TUI does.
 * - Window-owned bridges (`preview.*`, `terminal.read`, `window.read`, `tour`) and `vault.save_login`: declined
 *   with [NOT_SHOWN_CODE], the code by which a client says no window of it shows the session. A decline does not
 *   settle the request while another attached client (a desktop window) may still answer it; once every client
 *   declined, Hermes resolves it with a `{"success":false}` refusal, which `vault.save_login` reads as "not saved".
 * - `vault.code`: answered `{"value": ""}` at once, the "user entered no code" reply. Not [NOT_SHOWN_CODE]: with the
 *   phone as the only client Hermes would hand the refusal JSON to the browser tool as the one-time code itself.
 * - Anything else (unknown methods): a JSON-RPC "method not found" error.
 *
 * It also records requests the server withdrew (`request.cancel`, `approval.cancelled`) in [closed], shared
 * by chat and notifications so a withdrawn request replayed later is never shown again. Thread-safe.
 */
class ServerRequestRouter(
    private val transport: RpcTransport,
    val closed: RecentIds = RecentIds(),
    private val logger: (String, Throwable?) -> Unit = { _, _ -> },
) {
    /** Server-request id by the approval's `request_id`, for `approval.cancelled` (which names the latter). */
    private val srqByRequestId = object : LinkedHashMap<String, String>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > CAPACITY
    }

    /** Returns true when [req] must be forwarded to subscribers (approvals, questions, masked prompts); otherwise it was answered here. */
    fun route(req: Frame.ServerRequest): Boolean {
        val method = req.method
        val sent = when {
            method == APPROVAL -> {
                req.params.string("request_id")?.let { synchronized(srqByRequestId) { srqByRequestId[it] = req.id } }
                return true
            }
            method == CLARIFY -> {
                if (ClarifyAnswers.questions(req.params).isNotEmpty()) return true
                transport.respond(req.id, JsonObject(emptyMap()))
            }
            method in SECRET_METHODS -> return true
            method.startsWith("preview.") || method in WINDOW_OWNED ->
                transport.respondError(req.id, NOT_SHOWN_CODE, NOT_SHOWN_MESSAGE)
            method == VAULT_SAVE_LOGIN -> transport.respondError(req.id, NOT_SHOWN_CODE, VAULT_NOT_SHOWN_MESSAGE)
            method == VAULT_CODE -> transport.respond(req.id, buildJsonObject { put("value", "") })
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
        const val VAULT_NOT_SHOWN_MESSAGE = "Tsukaima (mobile) cannot save logins to a password manager"

        private const val APPROVAL = "approval"
        private const val CLARIFY = "clarify"
        private const val VAULT_SAVE_LOGIN = "vault.save_login"
        private const val VAULT_CODE = "vault.code"
        private const val CAPACITY = 64

        /** Masked one-string prompts: the reply is `{"value": "<string>"}`, `""` = skipped / declined. */
        val SECRET_METHODS = setOf("sudo", "secret", "vault.unlock_prompt")
        private val WINDOW_OWNED = setOf("terminal.read", "window.read", "tour")
    }
}
