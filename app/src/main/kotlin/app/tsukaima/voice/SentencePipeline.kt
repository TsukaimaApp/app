package app.tsukaima.voice

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive

/**
 * Synthesises sentences for playback one ahead: [take] produces (or picks up) the sentence about to play, and
 * [prefetch] starts producing the next one while the current one plays. At most one sentence is produced ahead; a
 * prefetched sentence whose text is not the next one taken is cancelled and its result [discard]ed.
 *
 * [cancel] (a stop) cancels the sentence being produced for [take] and the prefetched one, and discards the
 * prefetched result if it already arrived; a [take] interrupted that way returns null. [produce] must clean up after
 * itself when cancelled; a result it returns once its entry was cancelled is discarded here.
 *
 * Pure (no Android): [scope] runs the productions (the view model's main-thread scope in the app, a test scope in
 * tests). Call from one thread at a time, like the voice controller does.
 */
class SentencePipeline<T : Any>(
    private val scope: CoroutineScope,
    private val produce: suspend (String) -> T?,
    private val discard: (T) -> Unit,
) {
    private class Entry<T : Any>(val text: String) {
        lateinit var job: Deferred<T?>

        /** Cancelled before its result was taken: a result arriving later is discarded. */
        var cancelled = false

        /** The result, once produced and until taken or discarded. */
        var result: T? = null
    }

    /** The sentence produced for the [take] in progress. */
    private var current: Entry<T>? = null

    /** The next sentence, produced ahead. */
    private var ahead: Entry<T>? = null

    /** The text produced ahead, if any (for tests and logs). */
    val prefetched: String? get() = ahead?.text

    /**
     * The sentence [text], produced (or taken from the prefetch when it is that sentence); then [next], if given, is
     * prefetched. Null when [produce] gave nothing or [cancel] interrupted it. Cancelling the caller cancels this
     * call's own sentence, and the prefetch only if this call started it and it is still the one ahead: a newer [take]
     * that began before this one unwound keeps its sentence and its prefetch.
     */
    suspend fun take(text: String, next: String? = null): T? {
        val entry = ahead?.takeIf { it.text == text }?.also { ahead = null } ?: run {
            dropAhead()
            start(text)
        }
        current = entry
        val aheadBefore = ahead
        if (next != null) prefetch(next)
        val ownAhead = ahead?.takeIf { it !== aheadBefore }
        return try {
            entry.job.await()
            if (entry.cancelled) null else entry.result.also { entry.result = null }
        } catch (e: CancellationException) {
            if (!currentCoroutineContext().isActive) {
                // The caller was cancelled: what this call started may not play or linger (and nothing else is touched).
                cancelEntry(entry)
                if (ownAhead != null && ahead === ownAhead) dropAhead()
                throw e
            }
            null // [cancel] stopped this sentence
        } finally {
            if (current === entry) current = null
        }
    }

    /**
     * Starts producing [text] ahead (one sentence at most: an earlier prefetch of other text is dropped); null = no
     * sentence follows any more, so a prefetch is dropped.
     */
    fun prefetch(text: String?) {
        if (ahead?.text == text) return
        dropAhead()
        if (text != null) ahead = start(text)
    }

    /** Stop: cancels the sentence being produced and the prefetched one, discarding its result. */
    fun cancel() {
        dropAhead()
        current?.let(::cancelEntry)
        current = null
    }

    private fun start(text: String): Entry<T> {
        val entry = Entry<T>(text)
        entry.job = scope.async(start = CoroutineStart.LAZY) {
            val r = produce(text)
            if (r != null) {
                if (entry.cancelled) discard(r) else entry.result = r
            }
            r
        }
        entry.job.start()
        return entry
    }

    private fun dropAhead() {
        ahead?.let(::cancelEntry)
        ahead = null
    }

    private fun cancelEntry(entry: Entry<T>) {
        entry.cancelled = true
        entry.result?.let(discard)
        entry.result = null
        entry.job.cancel()
    }
}
