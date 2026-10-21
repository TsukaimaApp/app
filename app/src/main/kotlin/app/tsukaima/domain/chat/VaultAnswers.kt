package app.tsukaima.domain.chat

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Answer values for the `vault.*` masked prompts. Pure; the values are never logged. */
object VaultAnswers {
    /**
     * The value answering `vault.save_login`: compact JSON `{"identifier":…,"password":…}`, sent as a *string*
     * inside `{"value": …}`. Built with kotlinx.serialization so quotes and backslashes are escaped.
     */
    fun saveLogin(identifier: String, password: String): String =
        buildJsonObject {
            put("identifier", identifier)
            put("password", password)
        }.toString()

    /**
     * Whether the `vault.code` [hint] asks for digits only, so the card offers the number keyboard: it mentions
     * digits or numbers and no letters, backup / recovery codes, phrases or words (which a number pad would lock out).
     */
    fun numericCode(hint: String?): Boolean =
        hint != null && NUMERIC.containsMatchIn(hint) && !NOT_NUMERIC.containsMatchIn(hint)

    private val NUMERIC = Regex("digit|number", RegexOption.IGNORE_CASE)
    private val NOT_NUMERIC = Regex("letter|backup|recovery|phrase|word", RegexOption.IGNORE_CASE)
}
