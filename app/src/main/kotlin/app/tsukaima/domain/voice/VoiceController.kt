package app.tsukaima.domain.voice

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
import app.tsukaima.domain.chat.AgentStatus
import app.tsukaima.domain.chat.ChatItem
import app.tsukaima.domain.chat.ChatState

/**
 * Voice mode's phase machine: listens while the chat could take a message, sends what was said, speaks the reply
 * sentence by sentence while revealing it, and listens again ([VoiceMode.InApp]) or rests ([VoiceMode.Assist]).
 *
 * It only reads [chat] and calls [send] (`ChatViewModel.sendText`; `interrupted` = the user barged in since the
 * last send, see [interruptedSinceLastSend]; returns false = not sent, then the barge-in stays pending). Everything runs in [scope],
 * whose dispatcher must be single-threaded (the chat's Main-immediate in the app): the fields below are unguarded.
 * [onApprovalNeeded] (Assist only) fires once per request card, after the hand-off line was spoken.
 *
 * Recogniser failures: silence (no match, speech timeout) listens again after [RETRY_DELAY_MS]; other retryable
 * errors back off from [RETRY_DELAY_MS] doubling up to [ERROR_BACKOFF_MAX_MS], and [MAX_CONSECUTIVE_ERRORS] in a
 * row (no Partial or Final between) are fatal until [tapIndicator]. In-app, [LISTEN_WINDOW_MS] of listening without
 * a Final rests in Idle ("Tap to speak"); [now] is the clock for it (milliseconds, monotonic).
 */
class VoiceController(
    private val input: SpeechInput,
    private val output: SpeechOutput,
    private val scope: CoroutineScope,
    private val chat: StateFlow<ChatState>,
    private val send: (text: String, interrupted: Boolean) -> Boolean,
    private val mode: VoiceMode,
    private val onApprovalNeeded: () -> Unit = {},
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
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

    /**
     * Assist: the exchange is over (or the chat went offline); in-app: listened too long in silence. Idle until
     * [tapIndicator].
     */
    private var resting = false

    /** Non-silence recogniser errors in a row (reset by a Partial, a Final or a tap). */
    private var consecutiveErrors = 0

    /** When the mic opened and has stayed wanted since without a Final; null while it is not wanted. */
    private var listeningSince: Long? = null

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
        consecutiveErrors = 0
        listeningSince = null
        listOf(chunkers.keys, finished, silenced, handledRequests).forEach { it.clear() }
        _state.value = VoiceState(
            muted = _state.value.muted,
            startIndex = chat.value.items.size,
            error = if (input.available) null else UNAVAILABLE,
        )
        chatJob = scope.launch { chat.collect(::onChat) }
        refresh()
    }

    /** True from a barge-in (or a rebuild that cut off speech) until the next spoken turn is sent. */
    var interruptedSinceLastSend: Boolean = false
        private set

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
        _state.update { it.copy(phase = VoicePhase.Idle, heard = "", transcribing = false) }
    }

    /** Muting releases the mic (phase Muted while it would listen); unmuting listens again. */
    fun setMuted(muted: Boolean) {
        _state.update { it.copy(muted = muted) }
        refresh()
    }

    /**
     * Ends the recogniser run in progress and listens again with a fresh one (the voice language changed: the next
     * run picks it up). No-op when the mic is not open.
     */
    fun restartListening() {
        if (!started) return
        val job = listenJob ?: return
        job.cancel()
        listenJob = null
        _state.update { it.copy(heard = "", transcribing = false) }
        refresh()
    }

    /** Speaking: stop speech and listen (barge-in). Idle: listen again. Otherwise nothing. */
    fun tapIndicator() {
        if (!started) return
        when (_state.value.phase) {
            VoicePhase.Speaking -> bargeIn()
            VoicePhase.Idle -> if (chat.value.status == AgentStatus.Idle) {
                resting = false
                consecutiveErrors = 0
                if (input.available) _state.update { it.copy(error = null) }
                refresh()
            }
            else -> Unit
        }
    }

    /**
     * The chat's transcript was replaced (a reconnect rebuilt it from stored history, so every item has a new id);
     * [itemsBeforeRebuild] is the transcript just before. Call it right after the rebuild (the reconnector's
     * `onRebuilt`, same thread). Every reply present now counts as heard: revealed in full, also while it keeps
     * streaming, and never spoken. On an immediate dispatcher the chat's collector may already have seen the rebuilt
     * items inside the update and handed one of their sentences to TTS: that is stopped, and its queued rest dropped.
     * The voice transcript keeps as many trailing items as it showed before.
     */
    fun rebased(itemsBeforeRebuild: List<ChatItem>) {
        if (!started) return
        val items = chat.value.items
        val voiceEra = (itemsBeforeRebuild.size - _state.value.startIndex).coerceAtLeast(0)
        val replies = items.filterIsInstance<ChatItem.AssistantMessage>()
        val ids = replies.mapTo(HashSet()) { it.id }
        queue.removeAll { it.id in ids } // queued from the rebuilt transcript before this call: never spoken
        if (current?.id in ids) {
            output.stop()
            interruptedSinceLastSend = true
            speakJob?.cancel()
            speakJob = null
            current = null
            drainQueue() // what is left belongs to replies from before the rebuild
        }
        chunkers.keys.removeAll(ids)
        finished += ids
        silenced += ids
        replies.forEach { reveal(it.id, it.text.length) }
        _state.update { it.copy(startIndex = (items.size - voiceEra).coerceIn(0, items.size)) }
        refresh()
    }

    private fun bargeIn() {
        output.stop()
        interruptedSinceLastSend = true
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
            is ChatItem.Approval, is ChatItem.Clarify, is ChatItem.SecretPrompt, is ChatItem.ConnectionRequest ->
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
            is ChatItem.ConnectionRequest -> settled
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
        // The sentence after the one being spoken arrived: the output may prepare it while that one plays.
        if (u.text != null && current?.text != null && queue.none { it.text != null }) output.prefetch(u.text)
        queue.addLast(u)
        drainQueue()
    }

    /** Starts speaking the queue unless that already runs or there is nothing to say. */
    private fun drainQueue() {
        if (queue.isEmpty() || speakJob?.isActive == true) return
        val job = scope.launch(start = CoroutineStart.LAZY) { drain() }
        speakJob = job
        job.start()
    }

    private suspend fun drain() {
        val self = currentCoroutineContext().job
        try {
            while (true) {
                val u = queue.removeFirstOrNull() ?: break
                current = u
                u.id?.let { reveal(it, u.end) }
                u.text?.let { output.speak(it, next = queue.firstOrNull { q -> q.text != null }?.text) }
                u.then?.invoke()
            }
        } finally {
            // A cancelled run may finish after a newer one started (stop, barge-in and rebase reset these themselves).
            if (speakJob === self) current = null
        }
        if (speakJob === self) speakJob = null
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
        if (!wantMic) listeningSince = null
        if (wantMic && listenJob == null) {
            listenJob = scope.launch(start = CoroutineStart.LAZY) { listenOnce() }.also { it.start() }
        } else if (!wantMic && listenJob != null) {
            listenJob?.cancel()
            listenJob = null
            _state.update { it.copy(heard = "", transcribing = false) }
        }
        val phase = voicePhase(mode, s.muted, eligible, speaking, status).let {
            if (it == VoicePhase.Idle && awaitingTurn && status == AgentStatus.Idle) VoicePhase.Thinking else it
        }
        _state.update { it.copy(phase = phase) }
    }

    /** One recogniser run up to its result; then (unless cancelled meanwhile) refresh, which may listen again. */
    private suspend fun listenOnce() {
        if (listeningSince == null) listeningSince = now()
        val end = input.listen().firstOrNull { e ->
            when (e) {
                is SpeechEvent.Partial -> {
                    consecutiveErrors = 0
                    _state.update { it.copy(heard = e.text) }
                }
                SpeechEvent.Transcribing -> _state.update { it.copy(transcribing = true) }
                else -> Unit
            }
            e !is SpeechEvent.Partial && e !is SpeechEvent.Transcribing
        }
        _state.update { it.copy(heard = "", transcribing = false) }
        when (end) {
            is SpeechEvent.Final -> {
                consecutiveErrors = 0
                val text = end.text.trim()
                if (text.isNotEmpty()) {
                    listeningSince = null
                    awaitingTurn = true // before send: it may update the chat synchronously
                    turnSeen = false
                    val interrupted = interruptedSinceLastSend // snapshot: cleared as the turn goes out
                    interruptedSinceLastSend = false
                    if (send(text, interrupted)) {
                        turnSeen = turnSeen || chat.value.status != AgentStatus.Idle
                    } else {
                        awaitingTurn = false
                        interruptedSinceLastSend = interrupted || interruptedSinceLastSend // not sent: the barge-in still counts
                    }
                } else {
                    listenedTooLong()
                }
            }
            is SpeechEvent.Error -> when {
                !end.retry -> _state.update { it.copy(error = end.message) }
                end.silence -> if (!listenedTooLong()) delay(RETRY_DELAY_MS)
                ++consecutiveErrors >= MAX_CONSECUTIVE_ERRORS -> {
                    consecutiveErrors = 0
                    _state.update { it.copy(error = end.message) }
                }
                else -> if (!listenedTooLong()) delay(backoff(consecutiveErrors))
            }
            is SpeechEvent.Partial, SpeechEvent.Transcribing -> Unit
            // Ended without a result: do not spin on a recogniser that ends at once.
            null -> if (!listenedTooLong()) delay(RETRY_DELAY_MS)
        }
        if (listenJob === currentCoroutineContext().job) listenJob = null
        refresh()
    }

    /** In-app, after [LISTEN_WINDOW_MS] of listening without a Final: rest (true). */
    private fun listenedTooLong(): Boolean {
        val since = listeningSince ?: return false
        if (mode != VoiceMode.InApp || now() - since < LISTEN_WINDOW_MS) return false
        resting = true
        return true
    }

    /** The wait before listening again after the [n]th non-silence error in a row. */
    private fun backoff(n: Int): Long = (RETRY_DELAY_MS shl (n - 1).coerceIn(0, 10)).coerceAtMost(ERROR_BACKOFF_MAX_MS)

    private companion object {
        const val UNAVAILABLE = "Speech recognition isn’t available on this device"
        const val APPROVAL_LINE = "That needs your approval. I’ve opened it in Tsukaima."

        /** The wait before listening again after silence or a recogniser that ended without a result. */
        const val RETRY_DELAY_MS = 300L

        /** The longest wait between retries of a failing recogniser. */
        const val ERROR_BACKOFF_MAX_MS = 5_000L

        /** Non-silence recogniser errors in a row that stop listening until a tap. */
        const val MAX_CONSECUTIVE_ERRORS = 5

        /** In-app: listening this long without a Final rests in Idle. */
        const val LISTEN_WINDOW_MS = 120_000L
    }
}
