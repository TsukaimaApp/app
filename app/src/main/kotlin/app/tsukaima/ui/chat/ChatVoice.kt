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
import app.tsukaima.domain.livevoice.LiveText
import app.tsukaima.domain.livevoice.LiveTransport
import app.tsukaima.domain.livevoice.LiveVoiceController
import app.tsukaima.domain.voice.LiveInfo
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

/** Which engine a voice-mode open runs: the phone's (recogniser + speech output) or GPT-Live with [Live.voice]. */
sealed interface VoiceEngine {
    /** The phone engine ([VoiceController]): turn by turn, speech recognised and spoken on the device or relayed. */
    data object Chained : VoiceEngine

    /** The GPT-Live engine ([LiveVoiceController]): full duplex with the server's [voice]. */
    data class Live(val voice: String) : VoiceEngine
}

/**
 * Voice mode for one [ChatViewModel]: owns the open engine's controller (the phone's [VoiceController] or the
 * GPT-Live [LiveVoiceController]) while the voice screen is open and mirrors its state into [ChatUiState.voice].
 * The controllers read the chat through a synchronous view of [state] (a send turns the status to Thinking and adds
 * the user bubble before it returns, which they rely on). The phone engine sends through [send]
 * (`ChatViewModel.sendText` as a voice turn; [send]'s `interrupted` is its barge-in flag), the live engine through
 * [sendLive] (`ChatViewModel.sendLive`) and stops the running turn with [interrupt]. Driven from the VM's
 * single-threaded [scope]; [now] is the live meter's clock (milliseconds, monotonic).
 */
internal class ChatVoice(
    private val backend: ChatBackend,
    private val scope: CoroutineScope,
    private val state: MutableStateFlow<ChatUiState>,
    private val send: (text: String, interrupted: Boolean) -> Boolean,
    private val history: StateFlow<ChatHistory>,
    private val sendLive: (prompt: String, context: String) -> Boolean = { _, _ -> false },
    private val interrupt: () -> Unit = {},
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private var controller: VoiceController? = null
    private var live: LiveSession? = null

    /** A live session the user closed, kept until its `session.closed` (or the close timeout) arrives. */
    private var closing: LiveSession? = null
    private var mirror: Job? = null
    private val chat: StateFlow<ChatState> = ChatView(state)

    /**
     * One live session: its controller and the transport it drives (closed directly on [dispose]); [started] once
     * the controller was started (an open still waiting for the history has nothing to end).
     */
    private class LiveSession(val controller: LiveVoiceController, val transport: LiveTransport) {
        var started = false

        /** The [ChatBackend.startLiveService] token while this session's service runs. */
        var service: Long? = null

        /** Why the app ended it ([close]); wins over the server's `session.closed` reason. */
        var endReason: String? = null
    }

    /** Stops [session]'s service (once); the live flag drops only when no newer session's service took over. */
    private fun stopService(session: LiveSession) {
        val generation = session.service ?: return
        session.service = null
        if (backend.stopLiveService(generation)) {
            backend.liveVoiceOpen.value = false
            backend.liveVoiceSeconds.value = 0
        }
    }

    /** An engine is open, or a closed live session is still ending: [open] would do nothing. */
    val busy: Boolean get() = controller != null || live != null || closing != null

    /** No engine is open, but a live session the user closed is still ending. */
    val ending: Boolean get() = controller == null && live == null && closing != null

    /** The live engine is open (its screen may show a dropped session's reason line). */
    val liveOpen: Boolean get() = live != null

    /**
     * Opens voice mode (no-op while [busy]) on [engine]. The phone engine: a fresh recogniser and speech output (per
     * the voice settings now), listening at once unless [listen] is false (then idle until the indicator is
     * tapped). The live engine starts a GPT-Live session seeded with the chat's text turns; if it cannot start,
     * [onLiveStartFailed] runs (the VM falls back to the phone engine). Until the chat's [history] is in, voice shows
     * idle with no transcript and the controller has not started, so its transcript begins after the resumed history
     * (none of it is spoken, no old request card is handed off). If the chat failed to open, voice shows why and
     * does not listen until a later open succeeds; it then starts as if opened at that moment.
     */
    fun open(
        mode: VoiceMode,
        onApprovalNeeded: () -> Unit,
        listen: Boolean = true,
        engine: VoiceEngine = VoiceEngine.Chained,
        onLiveStartFailed: () -> Unit = {},
    ) {
        if (busy) return
        val starting: () -> Unit
        val states: StateFlow<VoiceState>
        val stillOpen: () -> Boolean
        var info: LiveInfo? = null
        when (engine) {
            VoiceEngine.Chained -> {
                val c = VoiceController(
                    backend.speechInput(), backend.speechOutput(), scope, chat, send, mode, onApprovalNeeded,
                )
                controller = c
                starting = { c.start(listen) }
                states = c.state
                stillOpen = { controller === c }
            }
            is VoiceEngine.Live -> {
                val session = openLive(engine.voice, onLiveStartFailed)
                live = session
                starting = start@{
                    val service = backend.startLiveService()
                    if (service == null) { // refused: no session without the service
                        session.transport.close()
                        return@start startFailed(session, onLiveStartFailed)
                    }
                    session.service = service
                    backend.liveVoiceOpen.value = true
                    session.started = true
                    // A reply still streaming is not a finished text turn.
                    val items = chat.value.items.filter { it !is ChatItem.AssistantMessage || !it.streaming }
                    session.controller.start(LiveText.liveHistory(items))
                }
                states = session.controller.state
                stillOpen = { live === session }
                info = LiveInfo(engine.voice, 0)
            }
        }
        val show: (VoiceState) -> Unit = { v ->
            state.update { it.copy(voice = v) }
            if (engine is VoiceEngine.Live) {
                backend.liveVoiceSeconds.value = v.live?.takeIf { it.ended == null }?.seconds ?: 0 // 0 once ended
            }
        }
        if (history.value == ChatHistory.Loaded) {
            starting()
            if (!stillOpen()) return // the live start failed at once: the fallback has taken over
            show(states.value)
            mirror = scope.launch { states.collect(show) }
            return
        }
        // startIndex MAX_VALUE: no transcript at all while waiting (bindResumed may already have put history in).
        val waiting = VoiceState(
            phase = VoicePhase.Idle,
            startIndex = Int.MAX_VALUE,
            live = info,
            caption = if (info != null) CONNECTING else "",
        )
        state.update { it.copy(voice = waiting) }
        mirror = scope.launch {
            history.first { h ->
                if (h is ChatHistory.Failed) state.update { it.copy(voice = it.voice?.copy(error = h.message)) }
                h == ChatHistory.Loaded
            }
            starting() // its state (no error) replaces a failed open's
            if (stillOpen()) states.collect(show)
        }
    }

    private fun openLive(voice: String, onStartFailed: () -> Unit): LiveSession {
        val transport = backend.liveTransport()
        lateinit var session: LiveSession
        val c = LiveVoiceController(
            transport, scope, chat, voice, sendLive, interrupt,
            onEnded = { reason, seconds -> onLiveEnded(session, reason, seconds, onStartFailed) },
            now = now,
        )
        session = LiveSession(c, transport)
        return session
    }

    /**
     * Spec §2.5: a start failure hands over to [onStartFailed] (no toast here); a session the user ended toasts its
     * length; one that dropped toasts so and its screen rests on the reason line until End or Keyboard.
     */
    private fun onLiveEnded(session: LiveSession, reason: String, seconds: Int, onStartFailed: () -> Unit) {
        stopService(session)
        val requested = closing === session
        if (requested) closing = null
        if (reason == START_FAILED) return startFailed(session, onStartFailed)
        val length = if (seconds > 0) " · " + "%d:%02d".format(seconds / 60, seconds % 60) else ""
        val why = session.endReason ?: reason
        backend.toast(
            when {
                why == HERMES_UNREACHABLE -> "Live voice ended · Hermes unreachable"
                requested || why == CLOSE_REQUESTED || why == STOP_PHRASE -> "Live voice ended$length"
                why == AUDIO_FOCUS_LOST -> "Live voice ended · another app took the audio"
                else -> "Live voice dropped$length"
            },
        )
    }

    /** [session] could not start: it goes, and [onStartFailed] takes over (the VM falls back to the phone engine). */
    private fun startFailed(session: LiveSession, onStartFailed: () -> Unit) {
        if (live !== session) return
        live = null
        mirror?.cancel()
        mirror = null
        state.update { it.copy(voice = null) }
        onStartFailed()
    }

    /**
     * Leaves voice mode at once. The phone engine stops listening and speaking (the running turn goes on); a live
     * session is ended gracefully (`session.close`), its controller kept until the server confirms. [reason] is why
     * the app ends it (its end toast says so: [HERMES_UNREACHABLE]).
     */
    fun close(reason: String = CLOSE_REQUESTED) {
        val c = controller
        val l = live
        if (c == null && l == null) return
        controller = null
        live = null
        c?.stop()
        mirror?.cancel()
        mirror = null
        state.update { it.copy(voice = null) }
        if (l != null && l.started && l.controller.state.value.live?.ended == null) {
            closing = l
            l.endReason = reason
            l.controller.stop(reason)
        }
    }

    /**
     * The chat is going away (its scope is cancelled, so no close can complete): [close], then tear a live
     * session's transport down now, after `session.close` went out.
     */
    fun dispose() {
        close()
        closing?.let { session ->
            closing = null
            session.transport.close()
            stopService(session)
        }
    }

    fun setMuted(muted: Boolean) {
        controller?.setMuted(muted)
        live?.controller?.setMuted(muted)
    }

    fun tapIndicator() {
        controller?.tapIndicator()
        live?.controller?.tapIndicator()
    }

    /** The voice language changed: a recogniser run in progress starts over in it ([VoiceController.restartListening]). */
    fun restartListening() = controller?.restartListening() ?: Unit

    /** The transcript is about to be rebuilt from stored history ([LiveVoiceController.beginRebuild]). */
    fun beginRebuild() = live?.controller?.beginRebuild() ?: Unit

    /** The transcript was rebuilt from stored history; [itemsBefore] is the one it replaced ([VoiceController.rebased]). */
    fun rebased(itemsBefore: List<ChatItem>) {
        controller?.rebased(itemsBefore)
        live?.controller?.rebased(itemsBefore)
    }

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

    companion object {
        /** [close] reason: Hermes stayed unreachable while the live session ran. */
        const val HERMES_UNREACHABLE = "hermes_unreachable"
        private const val START_FAILED = "start_failed"
        private const val CLOSE_REQUESTED = "close_requested"
        private const val STOP_PHRASE = "stop_phrase"
        private const val AUDIO_FOCUS_LOST = "audio_focus_lost"
        private const val CONNECTING = "Connecting…"
    }
}
