package app.tsukaima.assist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistLaunchTest {
    @Test
    fun theAssistantSessionIsTrusted() = assertEquals(AssistLaunch.Trusted, AssistLaunch.trust(nonceValid = true))

    @Test
    fun anythingWithoutTheSessionNonceIsUntrusted() =
        // Including an explicit ACTION_ASSIST from another app while Tsukaima holds the assistant role.
        assertEquals(AssistLaunch.Untrusted, AssistLaunch.trust(nonceValid = false))

    @Test
    fun aNonceIsValidOnceAndOnlyTheLatest() {
        val first = AssistNonce.issue()
        val second = AssistNonce.issue()
        assertNotEquals(first, second)
        assertFalse("superseded", AssistNonce.consume(first))
        assertFalse(AssistNonce.consume(null))
        assertFalse(AssistNonce.consume("forged"))
        assertTrue(AssistNonce.consume(second))
        assertFalse("used up", AssistNonce.consume(second))
    }
}
