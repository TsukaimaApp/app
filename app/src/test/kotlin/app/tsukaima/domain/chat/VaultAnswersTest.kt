package app.tsukaima.domain.chat

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VaultAnswersTest {
    @Test
    fun saveLoginIsCompactJsonWithIdentifierAndPassword() {
        assertEquals(
            """{"identifier":"me@example.com","password":"hunter2"}""",
            VaultAnswers.saveLogin("me@example.com", "hunter2"),
        )
    }

    @Test
    fun saveLoginEscapesQuotesAndBackslashes() {
        val password = """p"a\ss"""
        val encoded = VaultAnswers.saveLogin("a\"b", password)
        assertEquals("""{"identifier":"a\"b","password":"p\"a\\ss"}""", encoded)
        val parsed = Json.parseToJsonElement(encoded).jsonObject
        assertEquals("a\"b", parsed["identifier"]!!.jsonPrimitive.content)
        assertEquals(password, parsed["password"]!!.jsonPrimitive.content)
    }

    @Test
    fun numericCodeOnlyWhenTheHintSaysDigits() {
        assertTrue(VaultAnswers.numericCode("6-digit code from your authenticator"))
        assertTrue(VaultAnswers.numericCode("Enter the DIGITS sent by SMS"))
        assertFalse(VaultAnswers.numericCode("Code from the email"))
        assertFalse(VaultAnswers.numericCode(null))
        assertTrue(VaultAnswers.numericCode("6 digits"))
        assertTrue(VaultAnswers.numericCode("The number from the SMS"))
        assertFalse(VaultAnswers.numericCode("6 digits or a backup code"))
        assertFalse(VaultAnswers.numericCode("8 digits or recovery phrase"))
        assertFalse(VaultAnswers.numericCode("Letters and numbers"))
        assertFalse(VaultAnswers.numericCode(""))
    }
}
