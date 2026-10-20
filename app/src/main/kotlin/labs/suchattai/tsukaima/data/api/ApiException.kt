package labs.suchattai.tsukaima.data.api

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.IOException

/**
 * Non-2xx response that is not an authentication failure (including transient 429/503 on a re-login).
 * [message] includes a truncated response body; [detail] is the server's own explanation (FastAPI's
 * `{"detail": …}`) when the body carried one.
 */
class ApiException(val code: Int, message: String, val detail: String? = null) : IOException(message) {
    companion object {
        private const val MAX_BODY_IN_MESSAGE = 300

        /** From a response [code] and [body]: `detail` is a string, or the `msg`s of a validation error list. */
        fun fromResponse(code: Int, body: String): ApiException {
            val snippet = body.trim().take(MAX_BODY_IN_MESSAGE)
            return ApiException(code, if (snippet.isEmpty()) "HTTP $code" else "HTTP $code: $snippet", parseDetail(body))
        }

        private fun parseDetail(body: String): String? {
            val root = try {
                HermesJson.parseToJsonElement(body) as? JsonObject
            } catch (_: Exception) {
                null
            } ?: return null
            return when (val d = root["detail"]) {
                is JsonPrimitive -> d.contentOrNull
                is JsonArray -> d.mapNotNull { ((it as? JsonObject)?.get("msg") as? JsonPrimitive)?.contentOrNull }
                    .joinToString("; ")
                else -> null
            }?.trim()?.takeIf { it.isNotEmpty() }
        }
    }
}

/** What to tell the user about [this] failure: the server's `detail` when it gave one, else the message. */
fun Throwable.userMessage(): String? = (this as? ApiException)?.detail ?: message?.takeIf { it.isNotBlank() }

/** 401 after a re-login attempt failed, or no stored credentials to re-login with. */
class AuthException(message: String = "Authentication failed") : IOException(message)

/** Request tag: skip the bearer header and the 401 re-login retry (login, health, status, auth providers). */
class NoAuth
