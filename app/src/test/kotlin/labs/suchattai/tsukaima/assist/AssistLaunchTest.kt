package labs.suchattai.tsukaima.assist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistLaunchTest {
    @Test
    fun theAssistantSessionIsTrusted() =
        assertEquals(AssistLaunch.Trusted, AssistLaunch.trust(action = null, nonceValid = true, roleHeld = false))

    @Test
    fun assistActionIsTrustedWhileTheRoleIsHeld() =
        assertEquals(AssistLaunch.Trusted, AssistLaunch.trust(AssistLaunch.ACTION_ASSIST, nonceValid = false, roleHeld = true))

    @Test
    fun assistActionWithoutTheRoleIsUntrusted() =
        assertEquals(AssistLaunch.Untrusted, AssistLaunch.trust(AssistLaunch.ACTION_ASSIST, nonceValid = false, roleHeld = false))

    @Test
    fun anyOtherLaunchIsUntrusted() {
        assertEquals(AssistLaunch.Untrusted, AssistLaunch.trust(action = null, nonceValid = false, roleHeld = true))
        assertEquals(AssistLaunch.Untrusted, AssistLaunch.trust("android.intent.action.MAIN", nonceValid = false, roleHeld = true))
    }

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
