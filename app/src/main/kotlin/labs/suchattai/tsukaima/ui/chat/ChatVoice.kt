package labs.suchattai.tsukaima.ui.chat

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
import labs.suchattai.tsukaima.domain.chat.ChatState
import labs.suchattai.tsukaima.domain.voice.VoiceController
import labs.suchattai.tsukaima.domain.voice.VoiceMode
import labs.suchattai.tsukaima.domain.voice.VoicePhase
import labs.suchattai.tsukaima.domain.voice.VoiceState

/**
 * Voice mode for one [ChatViewModel]: owns the [VoiceController] while the voice screen is open and mirrors its
 * state into [ChatUiState.voice]. The controller reads the chat through a synchronous view of [state] (a send
 * turns the status to Thinking before [send] returns, which the controller relies on) and sends through [send]
 * (`ChatViewModel.sendText`). Driven from the VM's single-threaded [scope].
 */
internal class ChatVoice(
    private val backend: ChatBackend,
    private val scope: CoroutineScope,
    private val state: MutableStateFlow<ChatUiState>,
    private val send: (String) -> Boolean,
    private val historyLoaded: StateFlow<Boolean>,
) {
    private var controller: VoiceController? = null
    private var mirror: Job? = null
    private val chat: StateFlow<ChatState> = ChatView(state)

    /**
     * Opens voice mode (no-op while open): a fresh recogniser, listening at once unless [listen] is false (then idle
     * until the indicator is tapped). Until [historyLoaded], voice shows idle and the controller has not started, so
     * its transcript begins after the resumed history (none of it is spoken, no old request card is handed off).
     */
    fun open(mode: VoiceMode, onApprovalNeeded: () -> Unit, listen: Boolean = true) {
        if (controller != null) return
        val c = VoiceController(backend.speechInput(), backend.speechOutput, scope, chat, send, mode, onApprovalNeeded)
        controller = c
        if (historyLoaded.value) {
            c.start(listen)
            state.update { it.copy(voice = c.state.value) }
            mirror = scope.launch { c.state.collect { v -> state.update { it.copy(voice = v) } } }
        } else {
            state.update { it.copy(voice = VoiceState(phase = VoicePhase.Idle)) }
            mirror = scope.launch {
                historyLoaded.first { it }
                c.start(listen)
                c.state.collect { v -> state.update { it.copy(voice = v) } }
            }
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

    /** [ChatUiState.chat] as a [StateFlow] whose [value] is always current (no collector in between). */
    @OptIn(ExperimentalForInheritanceCoroutinesApi::class)
    private class ChatView(private val source: StateFlow<ChatUiState>) : StateFlow<ChatState> {
        override val value: ChatState get() = source.value.chat
        override val replayCache: List<ChatState> get() = listOf(value)
        override suspend fun collect(collector: FlowCollector<ChatState>): Nothing {
            source.map { it.chat }.distinctUntilChanged { a, b -> a === b }.collect(collector)
            awaitCancellation()
        }
    }
}
