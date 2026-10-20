package labs.suchattai.tsukaima.domain.voice

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import labs.suchattai.tsukaima.domain.chat.AgentStatus
import labs.suchattai.tsukaima.domain.chat.ChatItem
import labs.suchattai.tsukaima.domain.chat.ChatState

/**
 * Voice mode's phase machine: listens while the chat could take a message, sends what was said, speaks the reply
 * sentence by sentence while revealing it, and listens again ([VoiceMode.InApp]) or rests ([VoiceMode.Assist]).
 *
 * It only reads [chat] and calls [send] (`ChatViewModel.sendText`; false = not sent). Everything runs in [scope],
 * whose dispatcher must be single-threaded (the chat's Main-immediate in the app): the fields below are unguarded.
 * [onApprovalNeeded] (Assist only) fires once per request card, after the hand-off line was spoken.
 */
class VoiceController(
    private val input: SpeechInput,
    private val output: SpeechOutput,
    private val scope: CoroutineScope,
    private val chat: StateFlow<ChatState>,
    private val send: (String) -> Boolean,
    private val mode: VoiceMode,
    private val onApprovalNeeded: () -> Unit = {},
) {
    private val _state = MutableStateFlow(VoiceState())
    val state: StateFlow<VoiceState> = _state.asStateFlow()

    /** One entry of the speech queue: [text] null = only move [id]'s reveal cursor to [end] (code, blank chunks). */
    private class Utterance(val id: String?, val text: String?, val end: Int, val then: (() -> Unit)? = null)

    private var started = false
    private var chatJob: Job? = null
    private var listenJob: Job? = null
    private var speakJob: Job? = null
    private val queue = ArrayDeque<Utterance>()
    private var current: Utterance? = null
    private val chunkers = HashMap<String, SentenceChunker>()
    private val finished = HashSet<String>() // flushed replies (a flushed chunker starts over, so never feed again)
    private val silenced = HashSet<String>() // replies cut off by barge-in: revealed in full, not spoken
    private val handledRequests = HashSet<String>()

    /** A Final was sent: no listening until the turn ran ([turnSeen]), ended and the speech queue drained. */
    private var awaitingTurn = false
    private var turnSeen = false

    /** Assist: the exchange is over (or the chat went offline); idle until [tapIndicator]. */
    private var resting = false

    private val speaking get() = current != null || queue.any { it.text != null }

    /**
     * Starts voice mode on the chat as it is now: earlier items stay out of the voice transcript. [listen] false
     * starts at rest (Idle, "Tap to speak") until [tapIndicator].
     */
    fun start(listen: Boolean = true) {
        if (started) return
        started = true
        awaitingTurn = false
        turnSeen = false
        resting = !listen
        listOf(chunkers.keys, finished, silenced, handledRequests).forEach { it.clear() }
        _state.value = VoiceState(
            muted = _state.value.muted,
            startIndex = chat.value.items.size,
            error = if (input.available) null else UNAVAILABLE,
        )
        chatJob = scope.launch { chat.collect(::onChat) }
        refresh()
    }

    /** Stops listening and speaking and the loop; the chat's turn keeps running (nothing is interrupted). */
    fun stop() {
        if (!started) return
        started = false
        listOf(chatJob, listenJob, speakJob).forEach { it?.cancel() }
        chatJob = null
        listenJob = null
        speakJob = null
        queue.clear()
        current = null
        output.stop()
        _state.update { it.copy(phase = VoicePhase.Idle, heard = "") }
    }

    /** Muting releases the mic (phase Muted while it would listen); unmuting listens again. */
    fun setMuted(muted: Boolean) {
        _state.update { it.copy(muted = muted) }
        refresh()
    }

    /** Speaking: stop speech and listen (barge-in). Idle: listen again. Otherwise nothing. */
    fun tapIndicator() {
        if (!started) return
        when (_state.value.phase) {
            VoicePhase.Speaking -> bargeIn()
            VoicePhase.Idle -> if (chat.value.status == AgentStatus.Idle) {
                resting = false
                if (input.available) _state.update { it.copy(error = null) }
                refresh()
            }
            else -> Unit
        }
    }

    private fun bargeIn() {
        output.stop()
        speakJob?.cancel()
        speakJob = null
        val cut = (queue.mapNotNull { it.id } + listOfNotNull(current?.id) + chunkers.keys).toSet()
        queue.clear()
        current = null
        silenced += cut
        chat.value.items.forEach { if (it is ChatItem.AssistantMessage && it.id in cut) reveal(it.id, it.text.length) }
        awaitingTurn = false
        resting = false
        refresh()
    }

    // ---- chat -------------------------------------------------------------------------------------

    private fun onChat(c: ChatState) {
        val present = HashSet<String>()
        var handOff = false
        for (item in c.items.drop(_state.value.startIndex)) when (item) {
            is ChatItem.AssistantMessage -> {
                present += item.id
                speakReply(item)
            }
            is ChatItem.Approval, is ChatItem.Clarify, is ChatItem.SecretPrompt ->
                if (mode == VoiceMode.Assist && !handOff && !item.answered && handledRequests.add(item.id)) {
                    handOff = true
                    enqueue(Utterance(null, APPROVAL_LINE, 0) { onApprovalNeeded() })
                }
            else -> Unit
        }
        chunkers.keys.retainAll(present) // a rebuilt transcript dropped the item
        refresh()
    }

    private val ChatItem.answered: Boolean
        get() = when (this) {
            is ChatItem.Approval -> answered
            is ChatItem.Clarify -> answered
            is ChatItem.SecretPrompt -> answered
            else -> true
        }

    /** Queues the new complete sentences of [m] (all of it once it stopped streaming). */
    private fun speakReply(m: ChatItem.AssistantMessage) {
        if (m.id in silenced) {
            reveal(m.id, m.text.length)
            if (!m.streaming) chunkers.remove(m.id)
            return
        }
        if (m.id in finished) return
        val chunker = chunkers.getOrPut(m.id) { SentenceChunker() }
        for (chunk in chunker.feedPositions(m.text)) enqueue(Utterance(m.id, speakable(chunk.text), chunk.end))
        if (!m.streaming) {
            enqueue(Utterance(m.id, chunker.flush()?.let(::speakable), m.text.length))
            chunkers.remove(m.id)
            finished += m.id
        }
    }

    private fun speakable(raw: String): String? = SpeechText.plain(raw).ifBlank { null }

    private fun reveal(id: String, end: Int) =
        _state.update { s -> if ((s.revealed[id] ?: -1) >= end) s else s.copy(revealed = s.revealed + (id to end)) }

    // ---- speech queue -----------------------------------------------------------------------------

    private fun enqueue(u: Utterance) {
        if (u.text == null && current == null && queue.isEmpty()) return u.id?.let { reveal(it, u.end) } ?: Unit
        queue.addLast(u)
        if (speakJob?.isActive == true) return
        val job = scope.launch(start = CoroutineStart.LAZY) { drain() }
        speakJob = job
        job.start()
    }

    private suspend fun drain() {
        try {
            while (true) {
                val u = queue.removeFirstOrNull() ?: break
                current = u
                u.id?.let { reveal(it, u.end) }
                u.text?.let { output.speak(it) }
                u.then?.invoke()
            }
        } finally {
            current = null
        }
        if (speakJob === currentCoroutineContext().job) speakJob = null
        refresh()
    }

    // ---- listening --------------------------------------------------------------------------------

    /** Re-derives turn tracking, whether the mic should be open, and the phase. */
    private fun refresh() {
        if (!started) return
        val status = chat.value.status
        if (awaitingTurn) {
            if (status != AgentStatus.Idle) turnSeen = true
            else if (turnSeen && !speaking) {
                awaitingTurn = false
                if (mode == VoiceMode.Assist) resting = true
            }
        }
        if (mode == VoiceMode.Assist && (status == AgentStatus.Offline || status == AgentStatus.Stopped)) resting = true
        val s = _state.value
        val eligible = s.error == null && !awaitingTurn && !resting && status == AgentStatus.Idle
        val wantMic = eligible && !s.muted && !speaking
        if (wantMic && listenJob == null) {
            listenJob = scope.launch(start = CoroutineStart.LAZY) { listenOnce() }.also { it.start() }
        } else if (!wantMic && listenJob != null) {
            listenJob?.cancel()
            listenJob = null
            _state.update { it.copy(heard = "") }
        }
        val phase = voicePhase(mode, s.muted, eligible, speaking, status).let {
            if (it == VoicePhase.Idle && awaitingTurn && status == AgentStatus.Idle) VoicePhase.Thinking else it
        }
        _state.update { it.copy(phase = phase) }
    }

    /** One recogniser run up to its result; then (unless cancelled meanwhile) refresh, which may listen again. */
    private suspend fun listenOnce() {
        val end = input.listen().firstOrNull { e ->
            if (e is SpeechEvent.Partial) _state.update { it.copy(heard = e.text) }
            e !is SpeechEvent.Partial
        }
        _state.update { it.copy(heard = "") }
        when (end) {
            is SpeechEvent.Final -> {
                val text = end.text.trim()
                if (text.isNotEmpty()) {
                    awaitingTurn = true // before send: it may update the chat synchronously
                    turnSeen = false
                    if (send(text)) turnSeen = turnSeen || chat.value.status != AgentStatus.Idle else awaitingTurn = false
                }
            }
            is SpeechEvent.Error -> if (end.retry) delay(RETRY_DELAY_MS) else _state.update { it.copy(error = end.message) }
            is SpeechEvent.Partial -> Unit
            null -> delay(RETRY_DELAY_MS) // ended without a result: do not spin on a recogniser that ends at once
        }
        if (listenJob === currentCoroutineContext().job) listenJob = null
        refresh()
    }

    private companion object {
        const val UNAVAILABLE = "Speech recognition isn’t available on this device"
        const val APPROVAL_LINE = "That needs your approval. I’ve opened it in Tsukaima."
        const val RETRY_DELAY_MS = 300L
    }
}
