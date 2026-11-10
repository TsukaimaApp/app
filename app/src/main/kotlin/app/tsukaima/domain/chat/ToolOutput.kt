package app.tsukaima.domain.chat

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * What an opened tool row shows for a tool's result.
 *
 * Hermes tools answer in different shapes: `terminal` `{output, exit_code, error}`, `read_file` `{content, …}`, others
 * `{result}`, `{stdout, stderr}`, a bare string, or data only (`web_search` `{data: {web: […]}}`). A live
 * `tool.complete.result` is that object (or the raw string when it was not JSON); a stored message's `content` is the
 * same thing as JSON text. Reading only `output` left every tool but `terminal` at "No output".
 */
object ToolOutput {
    /** Longer text is cut ([text]): a 1 MB file read is not laid out in a chat row. */
    const val MAX_CHARS = 20_000

    /** The result's main text, in the order tried. */
    private val TEXT_KEYS = listOf("output", "content", "stdout", "result", "text", "summary", "message")

    private val pretty = Json { prettyPrint = true; prettyPrintIndent = "  " }

    /**
     * The text of [result]: its main text field (the first of [TEXT_KEYS] that is a non-blank string), followed by
     * `stderr` and a string `error` when it has them; an `error` alone when there is no text; else the whole result
     * pretty-printed. A string that is JSON text is read as that JSON. Null when there is nothing to show (null, an
     * empty string, an empty object). Cut at [MAX_CHARS] with a note of what was left out.
     */
    fun text(result: JsonElement?): String? = raw(result)?.takeIf { it.isNotBlank() }?.let(::cut)

    private fun raw(result: JsonElement?): String? = when (result) {
        null, is JsonNull -> null
        is JsonPrimitive -> if (result.isString) parsed(result.content)?.let(::raw) ?: result.content else result.content
        is JsonArray -> if (result.isEmpty()) null else pretty.encodeToString(JsonElement.serializer(), result)
        is JsonObject -> {
            fun string(key: String) = (result[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
            val main = TEXT_KEYS.firstNotNullOfOrNull(::string)
            val extra = listOfNotNull(string("stderr"), string("error")).filter { main == null || it !in main }
            when {
                main != null || extra.isNotEmpty() -> (listOfNotNull(main) + extra).joinToString("\n")
                result.isEmpty() -> null
                else -> pretty.encodeToString(JsonElement.serializer(), result)
            }
        }
    }

    /** [text] as a JSON object or array when it is one; null for anything else (plain words, a number, broken JSON). */
    private fun parsed(text: String): JsonElement? {
        val t = text.trim()
        if (!(t.startsWith("{") && t.endsWith("}")) && !(t.startsWith("[") && t.endsWith("]"))) return null
        return try {
            Json.parseToJsonElement(t).takeIf { it is JsonObject || it is JsonArray }
        } catch (_: Exception) {
            null
        }
    }

    private fun cut(text: String): String =
        if (text.length <= MAX_CHARS) text else text.take(MAX_CHARS) + "\n… ${text.length - MAX_CHARS} more characters"
}
