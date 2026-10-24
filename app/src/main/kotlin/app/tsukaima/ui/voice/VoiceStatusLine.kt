package app.tsukaima.ui.voice

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.tsukaima.domain.chat.AgentStatus
import app.tsukaima.domain.chat.StatusTone
import app.tsukaima.domain.voice.LiveInfo
import app.tsukaima.domain.voice.VoiceMode
import app.tsukaima.domain.voice.VoicePhase
import app.tsukaima.domain.voice.liveClock
import app.tsukaima.ui.chat.components.PreviewPage
import app.tsukaima.ui.chat.components.StatusDot
import app.tsukaima.ui.chat.components.statusVisual
import app.tsukaima.ui.theme.Motion
import app.tsukaima.ui.theme.TsukaimaType
import app.tsukaima.ui.theme.tsukaima

/** The voice status line's content: [label], dot [tone] and whether the dot [pulsing]. */
data class VoiceLine(val label: String, val tone: StatusTone, val pulsing: Boolean)

/**
 * The status line for a voice [phase]. Thinking/Approval defer to the chat [status] label (the thinking flair, the
 * running tool, the waiting request); Idle keeps Offline/Stopped red and otherwise invites speech (the mic is closed
 * until a tap, in both modes). [transcribing] (server recognition has the utterance) turns Listening into
 * "Transcribing…". [mode] does not change the mapping; it is kept for the callers' symmetry. A GPT-Live session
 * ([live]) adds its clock to the running phases ("Listening · 1:24", Idle is "Connecting…") and, once ended, names why.
 */
@Suppress("UNUSED_PARAMETER")
fun voiceLine(
    phase: VoicePhase,
    status: AgentStatus,
    mode: VoiceMode,
    transcribing: Boolean = false,
    live: LiveInfo? = null,
): VoiceLine = if (live != null) liveLine(phase, status, live) else when (phase) {
    VoicePhase.Listening -> VoiceLine(if (transcribing) "Transcribing…" else "Listening…", StatusTone.Accent, true)
    VoicePhase.Thinking -> VoiceLine(status.label, StatusTone.Accent, true)
    VoicePhase.Speaking -> VoiceLine("Speaking", StatusTone.Accent, true)
    VoicePhase.Approval -> VoiceLine(status.label, StatusTone.Amber, false)
    VoicePhase.Muted -> VoiceLine("Muted", StatusTone.Grey, false)
    VoicePhase.Idle -> when {
        status == AgentStatus.Offline || status == AgentStatus.Stopped -> VoiceLine(status.label, StatusTone.Red, false)
        else -> VoiceLine("Tap to speak", StatusTone.Grey, false)
    }
}

private fun liveLine(phase: VoicePhase, status: AgentStatus, live: LiveInfo): VoiceLine {
    live.ended?.let { reason ->
        val label = when (reason) {
            "close_requested", "stop_phrase", "closed" -> "Live voice ended"
            "audio_focus_lost" -> "Audio taken by another app"
            "start_failed" -> "Live voice couldn’t start"
            else -> "Live voice dropped"
        }
        return VoiceLine(label, StatusTone.Grey, false)
    }
    val clock = liveClock(live.seconds)
    return when (phase) {
        VoicePhase.Listening -> VoiceLine("Listening · $clock", StatusTone.Accent, true)
        VoicePhase.Speaking -> VoiceLine("Speaking · $clock", StatusTone.Accent, true)
        VoicePhase.Thinking -> VoiceLine("${status.label} · $clock", StatusTone.Accent, true)
        VoicePhase.Approval -> VoiceLine("${status.label} · $clock", StatusTone.Amber, false)
        VoicePhase.Muted -> VoiceLine("Muted · $clock", StatusTone.Grey, false)
        VoicePhase.Idle -> VoiceLine("Connecting…", StatusTone.Accent, true)
    }
}

/**
 * Centred 7dp dot + serif-italic [line]; the label cross-fades in 180 ms when it changes. [centred] false starts it
 * at the leading edge (the assist sheet).
 */
@Composable
fun VoiceStatusLine(line: VoiceLine, fontSize: TextUnit = 16.sp, modifier: Modifier = Modifier, centred: Boolean = true) {
    val colors = MaterialTheme.tsukaima
    val visual = statusVisual(line.tone, line.pulsing, colors)
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, if (centred) Alignment.CenterHorizontally else Alignment.Start),
    ) {
        StatusDot(visual)
        Crossfade(targetState = line.label, animationSpec = tween(180, easing = Motion.easing), label = "voiceLabel") { label ->
            Text(
                text = label,
                style = TsukaimaType.statusLine.copy(fontSize = fontSize),
                color = colors.ink2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = if (centred) TextAlign.Center else TextAlign.Start,
            )
        }
    }
}

@Composable
private fun VoiceStatusLinePreview(dark: Boolean) = PreviewPage(dark) {
    VoiceStatusLine(voiceLine(VoicePhase.Listening, AgentStatus.Idle, VoiceMode.InApp))
    VoiceStatusLine(voiceLine(VoicePhase.Thinking, AgentStatus.Thinking("Steeping matcha…"), VoiceMode.InApp))
    VoiceStatusLine(voiceLine(VoicePhase.Speaking, AgentStatus.Replying, VoiceMode.InApp))
    VoiceStatusLine(voiceLine(VoicePhase.Approval, AgentStatus.Approval, VoiceMode.InApp))
    VoiceStatusLine(voiceLine(VoicePhase.Muted, AgentStatus.Idle, VoiceMode.InApp))
    VoiceStatusLine(voiceLine(VoicePhase.Idle, AgentStatus.Idle, VoiceMode.Assist), fontSize = 14.sp)
    VoiceStatusLine(voiceLine(VoicePhase.Idle, AgentStatus.Offline, VoiceMode.InApp))
}

@Preview(name = "VoiceStatusLine light")
@Composable
private fun VoiceStatusLineLightPreview() = VoiceStatusLinePreview(dark = false)

@Preview(name = "VoiceStatusLine dark")
@Composable
private fun VoiceStatusLineDarkPreview() = VoiceStatusLinePreview(dark = true)
