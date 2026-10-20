package labs.suchattai.tsukaima.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import labs.suchattai.tsukaima.domain.voice.SpeechOutput
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

/**
 * [SpeechOutput] on the platform [TextToSpeech]; one engine per process, created on first use. [speak] never
 * hangs: it returns at once when the engine failed to initialise or refuses the utterance. Cancelling [speak]
 * stops the engine and releases audio focus. Spoken text is never logged.
 */
class AndroidSpeechOutput(context: Context) : SpeechOutput {
    private val appContext = context.applicationContext
    private val focus = AudioFocus(appContext)
    private val initResult = CompletableDeferred<Boolean>()

    @Volatile
    private var initOk = false

    /** Utterance id to the continuation waiting for it; the listener (binder thread) resumes and removes. */
    private val pending = ConcurrentHashMap<String, (Unit) -> Unit>()

    private val tts: TextToSpeech by lazy {
        TextToSpeech(appContext) { status ->
            if (status != TextToSpeech.SUCCESS) Log.w(TAG, "TextToSpeech init failed: $status")
            initOk = status == TextToSpeech.SUCCESS
            initResult.complete(initOk)
        }.also { it.setOnUtteranceProgressListener(listener) }
    }

    private val listener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) = Unit
        override fun onDone(utteranceId: String?) = finish(utteranceId)

        @Suppress("OVERRIDE_DEPRECATION")
        override fun onError(utteranceId: String?) {
            Log.w(TAG, "TextToSpeech utterance error")
            finish(utteranceId)
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            Log.w(TAG, "TextToSpeech utterance error $errorCode")
            finish(utteranceId)
        }

        override fun onStop(utteranceId: String?, interrupted: Boolean) = finish(utteranceId)
    }

    private fun finish(utteranceId: String?) {
        utteranceId?.let { pending.remove(it) }?.invoke(Unit)
    }

    override val available: Boolean
        get() {
            tts // start the engine if nobody has yet
            return initOk
        }

    override suspend fun speak(text: String) {
        if (text.isBlank()) return
        val engine = tts
        if (!initResult.await()) return
        focus.request()
        try {
            for (chunk in text.chunked(TextToSpeech.getMaxSpeechInputLength())) speakChunk(engine, chunk)
        } catch (e: CancellationException) {
            engine.stop()
            throw e
        } finally {
            focus.release()
        }
    }

    private suspend fun speakChunk(engine: TextToSpeech, chunk: String) = suspendCancellableCoroutine { cont ->
        val id = UUID.randomUUID().toString()
        pending[id] = { cont.resume(Unit) }
        cont.invokeOnCancellation {
            pending.remove(id)
            engine.stop()
        }
        if (engine.speak(chunk, TextToSpeech.QUEUE_ADD, null, id) == TextToSpeech.ERROR) {
            Log.w(TAG, "TextToSpeech refused an utterance")
            finish(id)
        }
    }

    override fun stop() {
        if (initResult.isCompleted) tts.stop()
    }

    private companion object {
        const val TAG = "Tsukaima"
    }
}
