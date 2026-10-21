package app.tsukaima.ui.voice

import org.junit.Test
import org.junit.Assert.assertEquals
import app.tsukaima.domain.chat.AgentStatus
import app.tsukaima.domain.chat.StatusTone
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
}
