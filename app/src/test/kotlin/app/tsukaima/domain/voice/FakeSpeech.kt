package app.tsukaima.domain.voice

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * A scripted recogniser. Each [listen] run reads its own channel: the test [emit]s events into the current run and
 * [endRun] completes it; the controller cancelling its collection also ends it. [listenCount] counts runs started,
 * [active] says whether a run is being collected right now.
 */
internal class FakeSpeechInput(override val available: Boolean = true) : SpeechInput {
    var listenCount = 0
        private set
    var active = false
        private set
    private var current: Channel<SpeechEvent>? = null

    override fun listen(): Flow<SpeechEvent> = flow {
        val run = Channel<SpeechEvent>(Channel.UNLIMITED)
        current = run
        listenCount++
        active = true
        try {
            emitAll(run.receiveAsFlow())
        } finally {
            active = false
            if (current === run) current = null
        }
    }

    /** Sends [event] to the run being collected; fails when nothing listens (advance the scheduler first). */
    fun emit(event: SpeechEvent) {
        val run = checkNotNull(current) { "no listen() run is being collected" }
        run.trySend(event)
    }

    /** Completes the current run as a recogniser that ended without a result. */
    fun endRun() {
        current?.close()
    }
}

/**
 * A recorder for spoken text. With [instant] false (the default) each [speak] suspends until the test calls
 * [finishSpeaking], so mid-speech state can be asserted; [speaking] says whether a [speak] call is in progress.
 */
internal class FakeSpeechOutput(var instant: Boolean = false, override val available: Boolean = true) : SpeechOutput {
    val spoken = mutableListOf<String>()
    var stopped = 0
        private set
    var speaking = false
        private set
    private var done: CompletableDeferred<Unit>? = null

    /** Next-sentence hints: `speak`'s `next` (non-null ones) and [prefetch] calls, in order. */
    val hints = mutableListOf<String>()

    override suspend fun speak(text: String, next: String?) {
        next?.let { hints += it }
        speak(text)
    }

    override fun prefetch(text: String) {
        hints += text
    }

    override suspend fun speak(text: String) {
        spoken += text
        if (instant) return
        val d = CompletableDeferred<Unit>()
        done = d
        speaking = true
        try {
            d.await()
        } finally {
            speaking = false
            if (done === d) done = null
        }
    }

    /** Ends the [speak] call in progress (no-op when none). */
    fun finishSpeaking() {
        done?.complete(Unit)
    }

    override fun stop() {
        stopped++
    }

    override fun languages(): Set<String> = emptySet()
}
