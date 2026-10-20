package labs.suchattai.tsukaima.domain.chat

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import labs.suchattai.tsukaima.data.api.HermesJson

/**
 * Encoding of `clarify` answers, matching the official TUI (ui-tui `components/prompts.tsx`, `lib/text.ts`):
 * a single-select or free-text answer is the plain string; a multi-select answer is `JSON.stringify` of the
 * picked choices followed by the typed "Other" value; an empty answer is sent as null (skipped).
 */
object ClarifyAnswers {
    /** `JSON.stringify(values)`: a compact JSON array of strings; "" when nothing is picked. */
    fun encodeMultiSelect(selected: List<String>): String =
        if (selected.isEmpty()) "" else JsonArray(selected.map(::JsonPrimitive)).toString()

    /** The wire answer for [q] from the picked [choices] and the typed [other] text; null = skipped. */
    fun answer(q: ClarifyQuestion, choices: List<String>, other: String): String? {
        val typed = other.trim().takeIf { it.isNotEmpty() }
        return when {
            q.choices == null -> typed
            q.multiSelect -> encodeMultiSelect(choices + listOfNotNull(typed)).takeIf { it.isNotEmpty() }
            else -> choices.firstOrNull() ?: typed
        }
    }

    /** Human text for a sent answer (`clarifyAnswerText`): a multi-select array joined with ", ". */
    fun display(answer: String?, multiSelect: Boolean): String {
        if (answer.isNullOrBlank()) return SKIPPED
        if (!multiSelect) return answer
        val items = try {
            (HermesJson.parseToJsonElement(answer) as? JsonArray)?.map { (it as? JsonPrimitive)?.contentOrNull ?: it.toString() }
        } catch (_: IllegalArgumentException) {
            null
        }
        return items?.joinToString(", ") ?: answer
    }

    /** Parses a `clarify` request's `questions` (entries without a qid or question text are dropped, as the TUI does). */
    fun questions(params: JsonObject): List<ClarifyQuestion> =
        (params["questions"] as? JsonArray).orEmpty().mapNotNull { raw ->
            val q = raw as? JsonObject ?: return@mapNotNull null
            val qid = (q["qid"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val text = (q["question"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val choices = (q["choices"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.takeIf { it.isNotEmpty() }
            val multi = (q["multi_select"] as? JsonPrimitive)?.contentOrNull == "true"
            ClarifyQuestion(qid, text, choices, multi)
        }

    const val SKIPPED = "Skipped"
}
