package app.tsukaima.ui.chat

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import app.tsukaima.domain.chat.ChatItem
import app.tsukaima.domain.chat.ChatState
import app.tsukaima.domain.voice.VoiceController
import app.tsukaima.domain.voice.VoiceMode
import app.tsukaima.domain.voice.VoicePhase
import app.tsukaima.domain.voice.VoiceState

/**
 * Whether a chat's history is in: voice waits for [Loaded]; [Failed] shows the reason until a later open succeeds
 * (then [Loaded]).
 */
internal sealed interface ChatHistory {
    data object Loading : ChatHistory

    data object Loaded : ChatHistory

    data class Failed(val message: String) : ChatHistory
}

/**
 * Voice mode for one [ChatViewModel]: owns the [VoiceController] while the voice screen is open and mirrors its
 * state into [ChatUiState.voice]. The controller reads the chat through a synchronous view of [state] (a send
 * turns the status to Thinking before [send] returns, which the controller relies on) and sends through [send]
 * (`ChatViewModel.sendText` as a voice turn; [send]'s `interrupted` is the controller's barge-in flag). Driven from the VM's single-threaded [scope].
 */
internal class ChatVoice(
    private val backend: ChatBackend,
    private val scope: CoroutineScope,
    private val state: MutableStateFlow<ChatUiState>,
    private val send: (text: String, interrupted: Boolean) -> Boolean,
    private val history: StateFlow<ChatHistory>,
) {
    private var controller: VoiceController? = null
    private var mirror: Job? = null
    private val chat: StateFlow<ChatState> = ChatView(state)

    /**
     * Opens voice mode (no-op while open): a fresh recogniser and speech output (per the voice settings now), listening
     * at once unless [listen] is false (then idle until the indicator is tapped). Until the chat's [history] is in,
     * voice shows idle with no transcript and the controller has not started, so its transcript begins after the
     * resumed history (none of it is spoken, no old request card is handed off). If the chat failed to open, voice shows why and does not listen until a later
     * open succeeds; it then starts as if opened at that moment.
     */
    fun open(mode: VoiceMode, onApprovalNeeded: () -> Unit, listen: Boolean = true) {
        if (controller != null) return
        val c = VoiceController(backend.speechInput(), backend.speechOutput(), scope, chat, send, mode, onApprovalNeeded)
        controller = c
        if (history.value == ChatHistory.Loaded) {
            c.start(listen)
            state.update { it.copy(voice = c.state.value) }
            mirror = scope.launch { c.state.collect { v -> state.update { it.copy(voice = v) } } }
            return
        }
        // startIndex MAX_VALUE: no transcript at all while waiting (bindResumed may already have put history in).
        state.update { it.copy(voice = VoiceState(phase = VoicePhase.Idle, startIndex = Int.MAX_VALUE)) }
        mirror = scope.launch {
            history.first { h ->
                if (h is ChatHistory.Failed) state.update { it.copy(voice = it.voice?.copy(error = h.message)) }
                h == ChatHistory.Loaded
            }
            c.start(listen) // its state (no error) replaces a failed open's
            c.state.collect { v -> state.update { it.copy(voice = v) } }
        }
    }

    /** Stops listening and speaking (the running turn goes on) and leaves voice mode. */
    fun close() {
        val c = controller ?: return
        controller = null
        c.stop()
        mirror?.cancel()
        mirror = null
        state.update { it.copy(voice = null) }
    }

    fun setMuted(muted: Boolean) = controller?.setMuted(muted) ?: Unit

    fun tapIndicator() = controller?.tapIndicator() ?: Unit

    /** The voice language changed: a recogniser run in progress starts over in it ([VoiceController.restartListening]). */
    fun restartListening() = controller?.restartListening() ?: Unit

    /** The transcript was rebuilt from stored history; [itemsBefore] is the one it replaced ([VoiceController.rebased]). */
    fun rebased(itemsBefore: List<ChatItem>) = controller?.rebased(itemsBefore) ?: Unit

    /**
     * [ChatUiState.chat] with the merged [ChatUiState.status] (compacting, gateway Offline/Stopped) as a [StateFlow]
     * whose [value] is always current (no collector in between).
     */
    @OptIn(ExperimentalForInheritanceCoroutinesApi::class)
    private class ChatView(private val source: StateFlow<ChatUiState>) : StateFlow<ChatState> {
        override val value: ChatState get() = source.value.voiceChat()
        override val replayCache: List<ChatState> get() = listOf(value)
        override suspend fun collect(collector: FlowCollector<ChatState>): Nothing {
            source.distinctUntilChanged { a, b -> a.chat === b.chat && a.status == b.status }
                .map { it.voiceChat() }
                .collect(collector)
            awaitCancellation()
        }

        private fun ChatUiState.voiceChat(): ChatState = if (chat.status == status) chat else chat.copy(status = status)
    }
}
