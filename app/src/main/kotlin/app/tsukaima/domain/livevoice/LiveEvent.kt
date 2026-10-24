package app.tsukaima.domain.livevoice

import app.tsukaima.data.api.HermesJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/** Who spoke a transcript fragment. */
enum class LiveSpeaker { User, Assistant }

/** One transcript delta of a live session; [startMs] and [endMs] are session-relative. */
data class LiveFragment(val speaker: LiveSpeaker, val text: String, val startMs: Long, val endMs: Long)

/** A server event from the GPT-Live data channel (`oai-events`), parsed. */
sealed interface LiveEvent {
    /** `session.started`. */
    data class Started(val sessionId: String?) : LiveEvent

    /** `session.input_transcript.delta` / `session.output_transcript.delta`. */
    data class Transcript(val fragment: LiveFragment) : LiveEvent

    /** `session.delegation.created`: the voice model asks Hermes for work. */
    data class Delegation(val id: String) : LiveEvent

    /** `error`. */
    data class Error(val message: String, val code: String?) : LiveEvent

    /** `session.closed`. */
    data class Closed(val reason: String, val usageSeconds: Double?) : LiveEvent

    /** Any other (or incomplete) event. */
    data class Unknown(val type: String) : LiveEvent

    companion object {
        /** Parses one data-channel message; null unless it is a JSON object with a non-blank `type`. Never throws. */
        fun parse(raw: String): LiveEvent? {
            val obj = runCatching { HermesJson.parseToJsonElement(raw) }.getOrNull() as? JsonObject ?: return null
            val type = obj.str("type")?.takeIf { it.isNotBlank() } ?: return null
            return when (type) {
                "session.started" -> Started(obj.obj("session")?.str("id"))
                "session.input_transcript.delta" -> Transcript(fragment(LiveSpeaker.User, obj))
                "session.output_transcript.delta" -> Transcript(fragment(LiveSpeaker.Assistant, obj))
                "session.delegation.created" -> obj.obj("delegation")?.str("id")?.takeIf { it.isNotEmpty() }
                    ?.let { Delegation(it) } ?: Unknown(type)
                "error" -> {
                    val e = obj.obj("error")
                    Error(e?.str("message") ?: "GPT-Live error", e?.str("code"))
                }
                "session.closed" -> {
                    val seconds = (obj.obj("usage")?.get("seconds") as? JsonPrimitive)?.doubleOrNull
                    Closed(obj.str("reason") ?: "closed", seconds)
                }
                else -> Unknown(type)
            }
        }

        private fun fragment(speaker: LiveSpeaker, o: JsonObject) = LiveFragment(
            speaker,
            o.str("delta") ?: "",
            (o["start_ms"] as? JsonPrimitive)?.doubleOrNull?.toLong() ?: 0L,
            (o["end_ms"] as? JsonPrimitive)?.doubleOrNull?.toLong() ?: 0L,
        )

        private fun JsonObject.str(key: String): String? =
            (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

        private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
    }
}
