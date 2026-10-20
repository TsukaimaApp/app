package labs.suchattai.tsukaima.ui.voice

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
import labs.suchattai.tsukaima.domain.chat.AgentStatus
import labs.suchattai.tsukaima.domain.chat.StatusTone
import labs.suchattai.tsukaima.domain.voice.VoiceMode
import labs.suchattai.tsukaima.domain.voice.VoicePhase
import labs.suchattai.tsukaima.ui.chat.components.PreviewPage
import labs.suchattai.tsukaima.ui.chat.components.StatusDot
import labs.suchattai.tsukaima.ui.chat.components.statusVisual
import labs.suchattai.tsukaima.ui.theme.Motion
import labs.suchattai.tsukaima.ui.theme.TsukaimaType
import labs.suchattai.tsukaima.ui.theme.tsukaima

/** The voice status line's content: [label], dot [tone] and whether the dot [pulsing]. */
data class VoiceLine(val label: String, val tone: StatusTone, val pulsing: Boolean)

/**
 * The status line for a voice [phase]. Thinking/Approval defer to the chat [status] label (the thinking flair, the
 * running tool, the waiting request); Idle keeps Offline/Stopped red and otherwise invites speech per [mode].
 */
fun voiceLine(phase: VoicePhase, status: AgentStatus, mode: VoiceMode): VoiceLine = when (phase) {
    VoicePhase.Listening -> VoiceLine("Listening…", StatusTone.Accent, true)
    VoicePhase.Thinking -> VoiceLine(status.label, StatusTone.Accent, true)
    VoicePhase.Speaking -> VoiceLine("Speaking", StatusTone.Accent, true)
    VoicePhase.Approval -> VoiceLine(status.label, StatusTone.Amber, false)
    VoicePhase.Muted -> VoiceLine("Muted", StatusTone.Grey, false)
    VoicePhase.Idle -> when {
        status == AgentStatus.Offline || status == AgentStatus.Stopped -> VoiceLine(status.label, StatusTone.Red, false)
        mode == VoiceMode.Assist -> VoiceLine("Tap to speak", StatusTone.Grey, false)
        else -> VoiceLine("Hermes is listening", StatusTone.Grey, false)
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
