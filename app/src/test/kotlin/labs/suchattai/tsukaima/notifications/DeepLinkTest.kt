package labs.suchattai.tsukaima.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeepLinkTest {
    @Test
    fun deepLinkSessionIdValidated() {
        assertEquals("20261020_010916_48c967", DeepLink.sessionId("20261020_010916_48c967"))
        assertEquals("a-B_9", DeepLink.sessionId("a-B_9"))
        assertEquals("x".repeat(64), DeepLink.sessionId("x".repeat(64)))
        assertNull(DeepLink.sessionId(null))
        assertNull(DeepLink.sessionId(""))
        assertNull(DeepLink.sessionId("x".repeat(65)))
        assertNull(DeepLink.sessionId("../settings"))
        assertNull(DeepLink.sessionId("a b"))
        assertNull(DeepLink.sessionId("a?x=1"))
        assertNull(DeepLink.sessionId("abc\n"))
        assertNull(DeepLink.sessionId("ｓｅｓｓｉｏｎ")) // fullwidth letters are not [A-Za-z]
    }
}
