package labs.suchattai.tsukaima.domain.chat

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

    /** Whether the `vault.code` [hint] asks for digits, so the card offers the number keyboard. */
    fun numericCode(hint: String?): Boolean = hint?.contains("digit", ignoreCase = true) == true
}
