package labs.suchattai.tsukaima.domain.voice

import kotlinx.coroutines.flow.Flow

/** What one listening run of the recogniser reports. */
sealed interface SpeechEvent {
    /** The words recognised so far (shown live, not sent). */
    data class Partial(val text: String) : SpeechEvent

    /** The utterance is complete; the run ends. */
    data class Final(val text: String) : SpeechEvent

    /** The run failed; [retry] = a transient failure (no match, busy) worth listening again after. */
    data class Error(val message: String, val retry: Boolean) : SpeechEvent
}

/** Speech recognition seam (Android's SpeechRecognizer in the app, a fake in tests). */
interface SpeechInput {
    val available: Boolean

    /** One listening run; completes when the recogniser ends. Cancelling the collection releases the mic. */
    fun listen(): Flow<SpeechEvent>
}

/** Text-to-speech seam. */
interface SpeechOutput {
    val available: Boolean

    /** Speaks [text]; suspends until it was spoken. Cancellation stops it. */
    suspend fun speak(text: String)

    /** Stops whatever is being spoken. */
    fun stop()
}
