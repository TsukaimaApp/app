package app.tsukaima.domain.voice

import app.tsukaima.domain.chat.AgentStatus
import app.tsukaima.domain.chat.running

/** What the voice indicator shows. */
enum class VoicePhase { Listening, Thinking, Speaking, Approval, Muted, Idle }

/** [InApp]: the voice screen inside a chat, a continuous conversation. [Assist]: one exchange from the assistant gesture. */
enum class VoiceMode { InApp, Assist }

/**
 * The voice-mode UI state.
 * [heard]: partial recognition shown as a live user bubble; cleared on Final.
 * [revealed]: assistant item id → chars of its text spoken so far (the transcript reveal cursor).
 * [startIndex]: chat items before this index are not shown in voice mode.
 * [transcribing]: the recorded utterance is being recognised on the server; cleared by the run's result or when the
 * mic closes.
 * [error]: why voice cannot listen ("Speech recognition isn’t available on this device", a recogniser failure).
 */
data class VoiceState(
    val phase: VoicePhase = VoicePhase.Listening,
    val muted: Boolean = false,
    val heard: String = "",
    val revealed: Map<String, Int> = emptyMap(),
    val startIndex: Int = 0,
    val error: String? = null,
    val transcribing: Boolean = false,
)

/**
 * The phase for the voice indicator. [listening] means listen-eligible (voice started and the chat could take a new
 * message), whether or not the mic is open: muted makes it [VoicePhase.Muted]. A waiting request wins over speech,
 * speech over a running turn; Offline/Stopped rest in [VoicePhase.Idle]. [mode] does not change the mapping (the
 * controller keeps InApp listen-eligible whenever the chat is), it is passed for the callers' symmetry.
 */
@Suppress("UNUSED_PARAMETER")
fun voicePhase(mode: VoiceMode, muted: Boolean, listening: Boolean, speaking: Boolean, status: AgentStatus): VoicePhase = when {
    status.waiting -> VoicePhase.Approval
    speaking -> VoicePhase.Speaking
    status.running || status == AgentStatus.Compacting -> VoicePhase.Thinking
    status == AgentStatus.Offline || status == AgentStatus.Stopped -> VoicePhase.Idle
    listening && muted -> VoicePhase.Muted
    listening -> VoicePhase.Listening
    else -> VoicePhase.Idle
}

/** A server request waits for the user (approval, clarify, secret). */
internal val AgentStatus.waiting: Boolean
    get() = this == AgentStatus.Approval || this == AgentStatus.Question || this is AgentStatus.Secret
