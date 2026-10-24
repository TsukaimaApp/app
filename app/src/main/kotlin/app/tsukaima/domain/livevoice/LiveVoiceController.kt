package app.tsukaima.domain.livevoice

import app.tsukaima.domain.chat.AgentStatus
import app.tsukaima.domain.chat.ChatItem
import app.tsukaima.domain.chat.ChatState
import app.tsukaima.domain.chat.running
import app.tsukaima.domain.chat.turnLive
import app.tsukaima.domain.voice.LiveInfo
import app.tsukaima.domain.voice.SentenceChunker
import app.tsukaima.domain.voice.SpeechText
import app.tsukaima.domain.voice.VoicePhase
import app.tsukaima.domain.voice.VoiceState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.roundToInt

/**
 * The GPT-Live engine: the voice model owns speech (it talks and listens over [transport]); whenever it delegates
 * (`session.delegation.created`) this controller submits a Hermes turn through [send] (`ChatViewModel.sendLive`:
 * the user's last words plus the recent spoken exchange as `voice_context`) and feeds the turn back as it runs, tool
 * starts as quiet `session.thinking.append`, the reply sentence by sentence as `session.commentary.append`. Port of
 * the desktop's `use-voice-live-conversation.ts`.
 *
 * One delegation is active at a time; a newer one supersedes it at once (its tail is never spoken) and [interrupt]s
 * the chat's turn first when one runs. The reply is every [ChatItem.AssistantMessage] after the submit's user bubble,
 * in order (a tool-using turn shows several bubbles, often empty ones before its tools), each spoken as it streams.
 * A spoken stop phrase (judged on the whole user utterance after [UTTERANCE_SETTLE_MS] of quiet, or as a delegation's
 * prompt) ends the session instead.
 *
 * Everything runs in [scope], whose dispatcher must be single-threaded (the chat's Main-immediate in the app): the
 * fields below are unguarded. [onEnded] fires exactly once per controller, with the end reason (`start_failed`,
 * `stop_phrase`, the server's `session.closed` reason, `connection_lost`, or what [stop] was given) and the session's
 * seconds (the server's `usage.seconds` when it sent them). [now] is the clock (milliseconds, monotonic).
 */
class LiveVoiceController(
    private val transport: LiveTransport,
    private val scope: CoroutineScope,
    private val chat: StateFlow<ChatState>,
    private val voiceName: String,
    private val send: (prompt: String, context: String) -> Boolean,
    private val interrupt: () -> Unit,
    private val onEnded: (reason: String, seconds: Int) -> Unit,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private val _state = MutableStateFlow(VoiceState(phase = VoicePhase.Idle))

    /** The voice screen's state; after the end it keeps [VoiceState.live] (with `ended`) for the reason line. */
    val state: StateFlow<VoiceState> = _state.asStateFlow()

    private var launched = false
    private var up = false // session.started seen, not ended
    private var closing = false // session.close sent, waiting for session.closed
    private var ended = false
    private val startedSignal = CompletableDeferred<Unit>()

    private var startJob: Job? = null
    private var eventsJob: Job? = null
    private var speakingJob: Job? = null
    private var chatJob: Job? = null
    private var meterJob: Job? = null
    private var tickJob: Job? = null
    private var utteranceJob: Job? = null
    private var closeJob: Job? = null

    private var eventCounter = 0
    private val transcript = LiveTranscript()
    private val utterance = StringBuilder() // user words since the last settle: the stop-phrase judgement
    private var heard = "" // user words since the last delegation or voice reply: VoiceState.heard
    private var voiceLine = "" // voice words since the last user fragment: VoiceState.caption
    private var lastSpeaker: LiveSpeaker? = null

    private var delegationId: String? = null
    private var itemsAtSubmit = 0
    private var submittedAt = 0L
    private var turnObserved = false
    private var lastToolLabel: String? = null
    private var spokeAny = false // a reply sentence of the active delegation reached the voice
    private val cursors = ArrayList<ReplyCursor>() // by ordinal: the k-th assistant message since the submit
    private val handledRequests = HashSet<String>() // request card ids already announced
    private var startedAt = 0L

    /**
     * How far one assistant message of the active delegation was spoken. Keyed by ordinal, not id, so a history
     * rebuild (new ids, same messages) does not speak it again.
     */
    private class ReplyCursor {
        val chunker = SentenceChunker()
        var flushed = false
    }

    private val seconds get() = _state.value.live?.seconds ?: 0

    /**
     * Opens the session seeded with [history] (`LiveText.liveHistory`): `transport.start`, then `session.started`
     * within [START_TIMEOUT_MS], else it ends with `start_failed`. Earlier chat items stay out of the voice transcript.
     * Once per controller.
     */
    fun start(history: List<LiveHistoryMessage>) {
        if (launched || ended) return
        launched = true
        _state.value = VoiceState(
            phase = VoicePhase.Idle,
            startIndex = chat.value.items.size,
            caption = CONNECTING,
            live = LiveInfo(voiceName, 0),
        )
        // Subscribed before the transport starts: a session.started arriving during start() must not be missed.
        eventsJob = scope.launch {
            transport.events.collect(::onEvent)
            finish(CONNECTION_LOST, seconds) // the transport went away without session.closed
        }
        speakingJob = scope.launch { transport.speaking.collect { refresh() } }
        chatJob = scope.launch { chat.collect(::onChat) }
        startJob = scope.launch {
            val ok = try {
                transport.start(history)
                withTimeoutOrNull(START_TIMEOUT_MS) { startedSignal.await() } != null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                false
            }
            if (!ok) finish(START_FAILED, 0)
        }
    }

    /**
     * Ends the session: sends `session.close` and waits up to [CLOSE_TIMEOUT_MS] for `session.closed` (whose reason
     * and usage then win), else ends with [reason] at once. Idempotent.
     */
    fun stop(reason: String = CLOSE_REQUESTED) {
        if (ended || closing) return
        if (!launched) return finish(reason, 0)
        closing = true
        startJob?.cancel() // a stop during the start window ends with [reason], not start_failed
        clearDelegation()
        utteranceJob?.cancel()
        if (transport.send(buildJsonObject { put("type", "session.close") })) {
            closeJob = scope.launch {
                delay(CLOSE_TIMEOUT_MS)
                finish(reason, seconds)
            }
            refresh()
        } else {
            finish(reason, seconds)
        }
    }

    /** Mutes or unmutes the mic (track and server event); phase Muted while nothing else shows. */
    fun setMuted(muted: Boolean) {
        if (ended) return
        transport.setMicMuted(muted)
        val kind = if (muted) "mute" else "unmute"
        transport.send(
            buildJsonObject {
                put("type", "session.input_audio.$kind")
                put("event_id", nextEventId(kind))
            },
        )
        _state.update { it.copy(muted = muted) }
        refresh()
    }

    /** Full duplex has no turn boundary: nudges the voice to answer what was said now. */
    fun tapIndicator() {
        if (!up || closing) return
        val event = buildJsonObject {
            put("type", "session.instructions.append")
            put("event_id", nextEventId("instr"))
            put("delegation_id", JsonNull)
            put("content", NUDGE)
        }
        transport.send(event)
    }

    /**
     * The chat's transcript was replaced (a reconnect rebuilt it from stored history, so every item has a new id);
     * [itemsBeforeRebuild] is the transcript just before. Call it right after the rebuild (same thread). The voice
     * transcript keeps as many trailing items as it showed before, the active delegation's submit point moves the
     * same way; reply cursors are per ordinal, so a reply already spoken is not spoken again.
     */
    fun rebased(itemsBeforeRebuild: List<ChatItem>) {
        if (!launched || ended) return
        val items = chat.value.items
        fun moved(index: Int) =
            (items.size - (itemsBeforeRebuild.size - index).coerceAtLeast(0)).coerceIn(0, items.size)
        val startIndex = moved(_state.value.startIndex)
        if (delegationId != null) itemsAtSubmit = moved(itemsAtSubmit)
        _state.update { it.copy(startIndex = startIndex) }
        onChat(chat.value)
    }

    // ---- server events ----------------------------------------------------------------------------

    private fun onEvent(e: LiveEvent) {
        if (ended) return
        when (e) {
            is LiveEvent.Started -> onStarted()
            is LiveEvent.Transcript -> onTranscript(e.fragment)
            is LiveEvent.Delegation -> onDelegation(e.id)
            is LiveEvent.Error -> if (e.code != IGNORED_ERROR) {
                _state.update { it.copy(error = "Live voice: ${e.message.take(MAX_ERROR_CHARS)}") }
            }
            is LiveEvent.Closed -> {
                val usage = e.usageSeconds?.takeIf { it.isFinite() }?.roundToInt()?.coerceAtLeast(0)
                finish(e.reason, usage ?: seconds)
            }
            is LiveEvent.Unknown -> Unit
        }
    }

    private fun onStarted() {
        startedSignal.complete(Unit)
        if (up || closing) return
        up = true
        startedAt = now()
        _state.update { it.copy(caption = "") }
        meterJob = scope.launch {
            while (true) {
                delay(1_000)
                val elapsed = ((now() - startedAt) / 1_000).toInt().coerceAtLeast(0)
                _state.update { s -> s.copy(live = s.live?.copy(seconds = elapsed)) }
            }
        }
        refresh()
    }

    private fun onTranscript(f: LiveFragment) {
        transcript.append(f)
        when (f.speaker) {
            LiveSpeaker.User -> {
                utterance.append(f.text)
                heard = (heard + f.text).takeLast(MAX_HEARD_CHARS)
                voiceLine = ""
                utteranceJob?.cancel()
                utteranceJob = scope.launch {
                    delay(UTTERANCE_SETTLE_MS)
                    val said = utterance.toString()
                    utterance.clear()
                    if (StopPhrases.isStopCommand(said)) stop(STOP_PHRASE)
                }
            }
            LiveSpeaker.Assistant -> {
                if (lastSpeaker == LiveSpeaker.User) heard = ""
                voiceLine = (voiceLine + f.text).takeLast(CAPTION_CHARS)
            }
        }
        lastSpeaker = f.speaker
        _state.update { it.copy(heard = heard.trim(), caption = voiceLine.trim()) }
    }

    private fun onDelegation(id: String) {
        if (!up || closing) return
        val (prompt, context) = LiveText.delegationPrompt(transcript.contextWindow())
        if (prompt.isNotEmpty() && StopPhrases.isStopCommand(prompt)) return stop(STOP_PHRASE)
        val c = chat.value
        if (c.status.running) interrupt() // the answer spoken must be for what the user asked last
        delegationId = id
        itemsAtSubmit = Int.MAX_VALUE // nothing is this turn's until send appended its user bubble
        submittedAt = now()
        turnObserved = false
        lastToolLabel = null
        spokeAny = false
        cursors.clear()
        heard = ""
        _state.update { it.copy(heard = "") }
        if (!send(prompt, context)) {
            speak(id, SUBMIT_FAILED)
            clearDelegation()
            refresh()
            return
        }
        if (delegationId != id) return // superseded or stopped from inside send
        itemsAtSubmit = chat.value.items.size // send appends the user bubble synchronously: what follows is the turn
        tickJob?.cancel()
        tickJob = scope.launch {
            while (true) {
                delay(TICK_MS)
                feed()
                refresh()
            }
        }
        feed()
        refresh()
    }

    // ---- feeding the reply ------------------------------------------------------------------------

    private fun onChat(c: ChatState) {
        // Live mode shows replies in full: the voice paraphrases them, so there is no reveal cursor to follow.
        val s = _state.value
        var revealed: MutableMap<String, Int>? = null
        for (i in s.startIndex until c.items.size) {
            val m = c.items[i] as? ChatItem.AssistantMessage ?: continue
            if (s.revealed[m.id] != m.text.length) {
                (revealed ?: s.revealed.toMutableMap().also { revealed = it })[m.id] = m.text.length
            }
        }
        revealed?.let { r -> _state.update { it.copy(revealed = r) } }
        feed()
        refresh()
    }

    /** One step of the delegation loop; idempotent (called on every chat change and every [TICK_MS]). */
    private fun feed() {
        val id = delegationId ?: return
        if (ended) return
        val c = chat.value
        val status = c.status
        // turnLive, not running: a turn paused on a request card is not finished.
        val live = status.turnLive
        if (live) turnObserved = true
        if (status is AgentStatus.Tool && status.name != lastToolLabel) {
            lastToolLabel = status.name
            think(id, "Hermes is working: ${status.name}. Not done yet.")
        }
        var ordinal = 0
        var streaming = false
        for (i in itemsAtSubmit.coerceAtMost(c.items.size) until c.items.size) {
            val item = c.items[i]
            if (item is ChatItem.AssistantMessage) {
                turnObserved = true
                streaming = streaming || item.streaming
                feedReply(id, cursorAt(ordinal++), item)
            } else if (item.pendingRequest && handledRequests.add(item.id)) {
                speak(id, WAITING_SAY)
                think(id, WAITING_THINK)
            }
        }
        if (!live && !streaming && (turnObserved || now() - submittedAt > SUBMIT_SETTLE_GRACE_MS)) {
            // Settled; nothing spoken means a tool-only, failed or interrupted turn.
            if (!spokeAny) think(id, NO_RESULT)
            clearDelegation()
        }
    }

    private fun cursorAt(ordinal: Int): ReplyCursor {
        while (cursors.size <= ordinal) cursors.add(ReplyCursor())
        return cursors[ordinal]
    }

    /** Speaks [m]'s new complete sentences (raw text chunked, then made plain), and its tail once it stopped. */
    private fun feedReply(id: String, cursor: ReplyCursor, m: ChatItem.AssistantMessage) {
        if (cursor.flushed) return
        for (chunk in cursor.chunker.feed(m.text)) speakReply(id, chunk)
        if (!m.streaming) {
            cursor.chunker.flush()?.let { speakReply(id, it) }
            cursor.flushed = true
        }
    }

    private fun speakReply(id: String, raw: String) {
        // A sentence boundary inside a bold span leaves its markers unpaired in each chunk, which plain() keeps.
        val text = SpeechText.plain(raw).replace(STRAY_EMPHASIS, "").trim()
        if (text.isEmpty()) return
        spokeAny = true
        speak(id, text)
    }

    /** A request card still waiting for the user (approval, clarify, masked prompt, connection). */
    private val ChatItem.pendingRequest: Boolean
        get() = when (this) {
            is ChatItem.Approval -> !answered
            is ChatItem.Clarify -> !answered
            is ChatItem.SecretPrompt -> !answered
            is ChatItem.ConnectionRequest -> !settled
            else -> false
        }

    /** The chat waits for the user; unlike the phone engine's helper, a connection card counts too. */
    private val AgentStatus.awaitingUser: Boolean
        get() = this == AgentStatus.Approval || this == AgentStatus.Question || this is AgentStatus.Secret ||
            this == AgentStatus.Connection

    private fun clearDelegation() {
        delegationId = null
        tickJob?.cancel()
        tickJob = null
    }

    // ---- outgoing ---------------------------------------------------------------------------------

    private fun nextEventId(prefix: String) = "${prefix}_${++eventCounter}"

    /** Said aloud (paraphrased) by the voice, in chunks it can take. */
    private fun speak(delegation: String?, content: String) {
        for (chunk in LiveText.chunkForCommentary(content)) {
            transport.send(
                buildJsonObject {
                    put("type", "session.commentary.append")
                    put("event_id", nextEventId("say"))
                    put("delegation_id", delegation)
                    put("content", chunk)
                },
            )
        }
    }

    /** Quiet progress for the voice model, one short clause. */
    private fun think(delegation: String?, content: String) {
        val text = content.replace(WHITESPACE, " ").trim().take(APPEND_CHAR_LIMIT)
        if (text.isEmpty()) return
        transport.send(
            buildJsonObject {
                put("type", "session.thinking.append")
                put("event_id", nextEventId("think"))
                put("delegation_id", delegation)
                put("content", text)
            },
        )
    }

    // ---- lifecycle --------------------------------------------------------------------------------

    private fun finish(reason: String, seconds: Int) {
        if (ended) return
        ended = true
        up = false
        closing = false
        delegationId = null
        listOf(startJob, eventsJob, speakingJob, chatJob, meterJob, tickJob, utteranceJob, closeJob)
            .forEach { it?.cancel() }
        transport.close()
        _state.update {
            it.copy(phase = VoicePhase.Idle, heard = "", caption = "", live = LiveInfo(voiceName, seconds, reason))
        }
        onEnded(reason, seconds)
    }

    private fun refresh() {
        val s = _state.value
        val phase = when {
            ended || !up -> VoicePhase.Idle
            chat.value.status.awaitingUser -> VoicePhase.Approval
            transport.speaking.value -> VoicePhase.Speaking
            delegationId != null -> VoicePhase.Thinking
            s.muted -> VoicePhase.Muted
            else -> VoicePhase.Listening
        }
        if (phase != s.phase) _state.update { it.copy(phase = phase) }
    }

    /** Timeouts and pacing of the loop (milliseconds). */
    companion object {
        /** `transport.start` returned but no `session.started` this long after: the start failed. */
        const val START_TIMEOUT_MS = 20_000L

        /** After `session.close`, the longest wait for `session.closed`. */
        const val CLOSE_TIMEOUT_MS = 15_000L

        /** Quiet after the last user fragment before the utterance is judged as a stop phrase. */
        const val UTTERANCE_SETTLE_MS = 1_500L

        /** How long an accepted delegation may sit before an idle chat without a reply counts as settled. */
        const val SUBMIT_SETTLE_GRACE_MS = 15_000L

        /** The delegation loop's tick while a delegation is active. */
        const val TICK_MS = 200L

        private const val CONNECTING = "Connecting…"
        private const val CLOSE_REQUESTED = "close_requested"
        private const val START_FAILED = "start_failed"
        private const val STOP_PHRASE = "stop_phrase"
        private const val CONNECTION_LOST = "connection_lost"
        private const val IGNORED_ERROR = "context_injection_incomplete"
        private const val SUBMIT_FAILED = "Sorry, I could not reach Hermes for that request."
        private const val WAITING_SAY = "Hermes needs you to answer something on screen."
        private const val WAITING_THINK = "Hermes is waiting for the user to answer a request in the app."
        private const val NO_RESULT = "Hermes finished that request without a spoken result."
        private const val NUDGE = "The user has finished speaking. Respond now to what they said."
        private const val MAX_ERROR_CHARS = 200
        private const val CAPTION_CHARS = 240
        private const val MAX_HEARD_CHARS = 2_000
        private const val APPEND_CHAR_LIMIT = 1_400
        private val WHITESPACE = Regex("\\s+")
        private val STRAY_EMPHASIS = Regex("\\*\\*|__|~~")
    }
}
