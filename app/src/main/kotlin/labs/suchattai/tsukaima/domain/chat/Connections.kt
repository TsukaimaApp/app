package labs.suchattai.tsukaima.domain.chat

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlin.math.ceil

/**
 * One credential an MCP install still needs (`required_env[]` of a connection target): the card draws a field per
 * entry, masked when [secret], prefilled with [default], labelled [prompt] or else [name].
 */
data class ConnectionEnvField(
    val name: String,
    val required: Boolean,
    val secret: Boolean,
    val default: String,
    val prompt: String?,
)

/**
 * One row of a connection operation (`connection.request` / `connection.update` `targets[]`, Hermes
 * `ConnectionOperationTarget`). [kind], [action] and [state] are the wire strings, lower-cased; a value this app does
 * not know is kept as given. [resolved]: the row needs nothing more from the user (the server's flag, else
 * `connected` / `skipped`).
 */
data class ConnectionTarget(
    val name: String,
    val kind: String,
    val action: String,
    val state: String,
    val resolved: Boolean,
    val display: String? = null,
    val description: String? = null,
    val detail: String? = null,
    val instructions: String? = null,
    val connectUrl: String? = null,
    val requiredEnv: List<ConnectionEnvField> = emptyList(),
) {
    /** What the row is called on the card: the catalog's display name, else the target's name. */
    val label: String get() = display ?: name

    /** The state chip's text: a known state in words, an unknown one as given. */
    val stateLabel: String
        get() = when (state) {
            "initiated" -> "waiting"
            "not_connected" -> "not connected"
            else -> state
        }
}

/**
 * The card's answer to a connection operation (Hermes `ConnectionAnswer`): per-target outcomes and an optional
 * Continue ([settledBy] `continue`). [toString] never shows env values: they may be secrets.
 */
data class ConnectionAnswer(val targets: List<Target> = emptyList(), val settledBy: String? = null) {
    /** One row's answer: [status] `approved` or `skipped`; [env] the credential values an install asked for. */
    data class Target(val name: String, val status: String, val env: Map<String, String>? = null) {
        override fun toString() = "Target(name=$name, status=$status, env=${env?.keys})"
    }

    /** The `result` of `connection.respond`: `{targets:[{name, status, env?}], settled_by?}`. */
    fun toJson(): JsonObject = buildJsonObject {
        put("targets", buildJsonArray {
            targets.forEach { t ->
                add(buildJsonObject {
                    put("name", t.name)
                    put("status", t.status)
                    t.env?.let { env -> put("env", JsonObject(env.mapValues { JsonPrimitive(it.value) })) }
                })
            }
        })
        settledBy?.let { put("settled_by", it) }
    }
}

/** Builders and parsing for connection operations (`manage_connections`; `tui_gateway/contracts/connectors_operation.py`). */
object ConnectionAnswers {
    const val APPROVED = "approved"
    const val SKIPPED = "skipped"
    const val CONTINUE = "continue"

    private val RESOLVED_STATES = setOf("connected", "skipped")

    /** "Skip all": every target `skipped`, no `settled_by` (the operation settles once all rows are resolved). */
    fun skipAll(targets: List<ConnectionTarget>) = ConnectionAnswer(targets.map { ConnectionAnswer.Target(it.name, SKIPPED) })

    fun skip(name: String) = ConnectionAnswer(listOf(ConnectionAnswer.Target(name, SKIPPED)))

    /** "Connect" on a row: `approved`, with the [env] values when the target asked for credentials. */
    fun approve(name: String, env: Map<String, String>? = null) =
        ConnectionAnswer(listOf(ConnectionAnswer.Target(name, APPROVED, env?.takeIf { it.isNotEmpty() })))

    /** "Continue": end the operation now with whatever is unresolved. */
    fun continueNow() = ConnectionAnswer(settledBy = CONTINUE)

    /** The `owner` of an operation opened on live session [sessionId] (`ConnectorOwner`). */
    fun sessionOwner(sessionId: String): JsonObject = buildJsonObject {
        put("type", "session")
        put("session_id", sessionId)
    }

    /** `targets[]` of a request or update; entries without a name are dropped, nothing throws. */
    fun targets(array: JsonArray?): List<ConnectionTarget> = array.orEmpty().mapNotNull { entry ->
        val o = entry as? JsonObject ?: return@mapNotNull null
        val name = o.text("name")?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        val state = o.text("state")?.lowercase() ?: "pending"
        ConnectionTarget(
            name = name,
            kind = o.text("kind")?.lowercase().orEmpty(),
            action = o.text("action")?.lowercase().orEmpty(),
            state = state,
            resolved = (o["resolved"] as? JsonPrimitive)?.booleanOrNull ?: (state in RESOLVED_STATES),
            display = o.text("display")?.trim()?.takeIf { it.isNotEmpty() },
            description = o.text("description")?.takeIf { it.isNotBlank() },
            detail = o.text("detail")?.takeIf { it.isNotBlank() },
            instructions = o.text("instructions")?.takeIf { it.isNotBlank() },
            connectUrl = o.text("connect_url")?.takeIf { it.isNotBlank() },
            requiredEnv = (o["required_env"] as? JsonArray).orEmpty().mapNotNull(::envField),
        )
    }

    private fun envField(entry: kotlinx.serialization.json.JsonElement): ConnectionEnvField? {
        val o = entry as? JsonObject ?: return null
        val name = o.text("name")?.takeIf { it.isNotBlank() } ?: return null
        return ConnectionEnvField(
            name = name,
            required = (o["required"] as? JsonPrimitive)?.booleanOrNull ?: false,
            secret = (o["secret"] as? JsonPrimitive)?.booleanOrNull ?: false,
            default = o.text("default").orEmpty(),
            prompt = o.text("prompt")?.takeIf { it.isNotBlank() },
        )
    }

    /**
     * Time left until [deadlineAt] (epoch seconds, server clock) as of [nowSeconds] (the device clock, skew not
     * corrected): "n min left" (rounded up) from a minute on, "n s left" below, "Expired" at or past it; null when
     * the operation carries no deadline.
     */
    fun countdown(deadlineAt: Double, nowSeconds: Double): String? {
        if (deadlineAt <= 0) return null
        val left = deadlineAt - nowSeconds
        return when {
            left <= 0 -> "Expired"
            left >= 60 -> "${ceil(left / 60).toInt()} min left"
            else -> "${ceil(left).toInt()} s left"
        }
    }

    private fun JsonObject.text(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull && it.isString }?.contentOrNull
}
