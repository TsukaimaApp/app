package labs.suchattai.tsukaima.ui.chat.components

import labs.suchattai.tsukaima.domain.chat.AgentStatus
import labs.suchattai.tsukaima.ui.theme.DarkColors
import labs.suchattai.tsukaima.ui.theme.LightColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StatusToneTest {
    @Test
    fun idleIsGreyNotPulsing() {
        val v = statusVisual(AgentStatus.Idle, LightColors)
        assertEquals(LightColors.ink3, v.color)
        assertFalse(v.pulsing)
    }

    @Test
    fun thinkingIsAccentPulsing() {
        val v = statusVisual(AgentStatus.Thinking("Summoning…"), DarkColors)
        assertEquals(DarkColors.accent, v.color)
        assertTrue(v.pulsing)
        assertTrue(statusVisual(AgentStatus.Tool("shell"), LightColors).pulsing)
        assertTrue(statusVisual(AgentStatus.Replying, LightColors).pulsing)
    }

    @Test
    fun approvalIsAmber() {
        val v = statusVisual(AgentStatus.Approval, LightColors)
        assertEquals(LightColors.warn, v.color)
        assertFalse(v.pulsing)
    }

    @Test
    fun offlineIsRed() {
        assertEquals(LightColors.danger, statusVisual(AgentStatus.Offline, LightColors).color)
        assertEquals(LightColors.danger, statusVisual(AgentStatus.Stopped, LightColors).color)
        assertFalse(statusVisual(AgentStatus.Offline, LightColors).pulsing)
    }
}
