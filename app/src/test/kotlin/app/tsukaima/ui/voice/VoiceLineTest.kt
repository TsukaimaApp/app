package app.tsukaima.ui.voice

import org.junit.Test
import org.junit.Assert.assertEquals
import app.tsukaima.domain.chat.AgentStatus
import app.tsukaima.domain.chat.StatusTone
import app.tsukaima.domain.voice.LiveInfo
import app.tsukaima.domain.voice.VoiceMode
import app.tsukaima.domain.voice.VoicePhase

class VoiceLineTest {
    @Test fun listeningPulsesAccent() = assertEquals(VoiceLine("Listening…", StatusTone.Accent, true), voiceLine(VoicePhase.Listening, AgentStatus.Idle, VoiceMode.InApp))
    @Test fun thinkingShowsTheFlair() = assertEquals("Steeping matcha…", voiceLine(VoicePhase.Thinking, AgentStatus.Thinking("Steeping matcha…"), VoiceMode.InApp).label)
    @Test fun thinkingShowsToolLabel() = assertEquals(VoiceLine("Running shell", StatusTone.Accent, true), voiceLine(VoicePhase.Thinking, AgentStatus.Tool("shell"), VoiceMode.InApp))
    @Test fun speakingPulsesAccent() = assertEquals(VoiceLine("Speaking", StatusTone.Accent, true), voiceLine(VoicePhase.Speaking, AgentStatus.Replying, VoiceMode.InApp))
    @Test fun approvalIsAmberStatic() = assertEquals(VoiceLine("Waiting for your approval", StatusTone.Amber, false), voiceLine(VoicePhase.Approval, AgentStatus.Approval, VoiceMode.InApp))
    @Test fun mutedIsGreyStatic() = assertEquals(VoiceLine("Muted", StatusTone.Grey, false), voiceLine(VoicePhase.Muted, AgentStatus.Idle, VoiceMode.InApp))
    @Test fun idleAssistSaysTapToSpeak() = assertEquals("Tap to speak", voiceLine(VoicePhase.Idle, AgentStatus.Idle, VoiceMode.Assist).label)
    @Test fun idleInAppSaysTapToSpeak() = assertEquals(VoiceLine("Tap to speak", StatusTone.Grey, false), voiceLine(VoicePhase.Idle, AgentStatus.Idle, VoiceMode.InApp))
    @Test fun offlineStaysRed() = assertEquals(StatusTone.Red, voiceLine(VoicePhase.Idle, AgentStatus.Offline, VoiceMode.InApp).tone)
    @Test fun stoppedKeepsLabelInAssist() = assertEquals(VoiceLine("Gateway stopped", StatusTone.Red, false), voiceLine(VoicePhase.Idle, AgentStatus.Stopped, VoiceMode.Assist))
    @Test fun transcribingPulsesAccentWhileListening() = assertEquals(VoiceLine("Transcribing…", StatusTone.Accent, true), voiceLine(VoicePhase.Listening, AgentStatus.Idle, VoiceMode.InApp, transcribing = true))
    @Test fun transcribingIsIgnoredOutsideListening() = assertEquals("Muted", voiceLine(VoicePhase.Muted, AgentStatus.Idle, VoiceMode.InApp, transcribing = true).label)

    private fun live(phase: VoicePhase, seconds: Int = 64, ended: String? = null, status: AgentStatus = AgentStatus.Idle) =
        voiceLine(phase, status, VoiceMode.InApp, live = LiveInfo("marin", seconds, ended))

    @Test fun liveClockFormatsMinutesAndPaddedSeconds() {
        assertEquals("0:00", liveClock(0))
        assertEquals("1:04", liveClock(64))
        assertEquals("12:30", liveClock(750))
    }

    @Test fun liveRunningLinesCarryTheClock() {
        assertEquals(VoiceLine("Listening · 1:04", StatusTone.Accent, true), live(VoicePhase.Listening))
        assertEquals(VoiceLine("Speaking · 1:04", StatusTone.Accent, true), live(VoicePhase.Speaking))
        assertEquals("Running shell · 1:04", live(VoicePhase.Thinking, status = AgentStatus.Tool("shell")).label)
        assertEquals(
            VoiceLine("Waiting for your approval · 1:04", StatusTone.Amber, false),
            live(VoicePhase.Approval, status = AgentStatus.Approval),
        )
        assertEquals(VoiceLine("Muted · 1:04", StatusTone.Grey, false), live(VoicePhase.Muted))
        assertEquals(VoiceLine("Connecting…", StatusTone.Accent, true), live(VoicePhase.Idle))
    }

    @Test fun liveEndedLinesNameTheReason() {
        fun ended(reason: String) = live(VoicePhase.Idle, ended = reason)
        listOf("close_requested", "stop_phrase", "closed").forEach {
            assertEquals(VoiceLine("Live voice ended", StatusTone.Grey, false), ended(it))
        }
        listOf("connection_lost", "whatever").forEach { assertEquals("Live voice dropped", ended(it).label) }
        assertEquals("Audio taken by another app", ended("audio_focus_lost").label)
        assertEquals("Live voice couldn’t start", ended("start_failed").label)
    }
}
