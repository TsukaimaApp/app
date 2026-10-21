package app.tsukaima.voice

import android.content.Context
import android.media.AudioAttributes
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import app.tsukaima.domain.voice.SpeechOutput
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

/**
 * [SpeechOutput] on the platform [TextToSpeech] (assistant usage, speech content); one engine per process, created
 * on first use. [speak] never hangs: it returns at once when the engine failed to initialise or refuses the
 * utterance. Cancelling [speak] stops the engine. Audio focus is held across a run of sentences: asked by the first
 * [speak], given back [FOCUS_RELEASE_DELAY_MS] after the last one ended with no new one. Losing the focus to another
 * app (not a duck) stops the speech; the [speak] in flight then returns. Spoken text is never logged.
 *
 * [language] is the voice language setting (a BCP-47 tag, null = the engine's default), applied before each
 * utterance when it changed. A language the engine lacks (no voice data, not supported — by `isLanguageAvailable` or
 * by `setLanguage`) reverts to the engine's
 * default and is reported to [onUnsupported] once per tag per process, on the main thread.
 */
class AndroidSpeechOutput(
    context: Context,
    private val language: () -> String? = { null },
    private val onUnsupported: (String) -> Unit = {},
) : SpeechOutput {
    private val appContext = context.applicationContext
    private val focus = AudioFocus(appContext, onLoss = ::stop)
    private val initResult = CompletableDeferred<Boolean>()
    private val main = Handler(Looper.getMainLooper())
    private val releaseFocus = Runnable { if (inFlight.get() == 0) focus.release() }

    /** [speak] calls in progress; focus is released only once this stays 0 for [FOCUS_RELEASE_DELAY_MS]. */
    private val inFlight = AtomicInteger()

    @Volatile
    private var initOk = false

    /** The tag last handed to the engine (also when it fell back to the default); null = the engine's default. */
    @Volatile
    private var appliedTag: String? = null

    /** Tags already reported to [onUnsupported]. */
    private val reported = ConcurrentHashMap.newKeySet<String>()

    /** The engine's languages, read once after a successful init. */
    @Volatile
    private var engineLanguages: Set<String>? = null

    /** Utterance id to the continuation waiting for it; the listener (binder thread) resumes and removes. */
    private val pending = ConcurrentHashMap<String, (Unit) -> Unit>()

    private val tts: TextToSpeech by lazy {
        lateinit var engine: TextToSpeech // the init callback comes later, on the main thread
        engine = TextToSpeech(appContext) { status ->
            if (status != TextToSpeech.SUCCESS) Log.w(TAG, "TextToSpeech init failed: $status")
            initOk = status == TextToSpeech.SUCCESS
            if (initOk) engine.setAudioAttributes(SPEECH_ATTRIBUTES)
            initResult.complete(initOk)
        }
        engine.also { it.setOnUtteranceProgressListener(listener) }
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
        applyLanguage(engine)
        inFlight.incrementAndGet()
        main.removeCallbacks(releaseFocus)
        focus.request()
        try {
            for (chunk in text.chunked(TextToSpeech.getMaxSpeechInputLength())) speakChunk(engine, chunk)
        } catch (e: CancellationException) {
            engine.stop()
            throw e
        } finally {
            if (inFlight.decrementAndGet() == 0) main.postDelayed(releaseFocus, FOCUS_RELEASE_DELAY_MS)
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

    override fun languages(): Set<String> {
        val engine = tts // start the engine if nobody has yet; never wait for it
        if (!initOk) return emptySet()
        engineLanguages?.let { return it }
        val tags = try {
            engine.availableLanguages.orEmpty().mapTo(LinkedHashSet()) { it.toLanguageTag() }
        } catch (e: RuntimeException) { // some engines throw from availableLanguages
            Log.w(TAG, "TextToSpeech languages unavailable", e)
            return emptySet()
        }
        return tags.also { engineLanguages = it }
    }

    /** Hands the [language] setting to the engine when it changed since the last utterance. */
    private fun applyLanguage(engine: TextToSpeech) {
        val tag = language()
        if (tag == appliedTag) return
        appliedTag = tag
        if (tag == null) return restoreDefault(engine)
        val locale = Locale.forLanguageTag(tag)
        // Checked first: some engines accept setLanguage for a locale they then cannot speak.
        val availability = engine.isLanguageAvailable(locale)
        val result = if (availability < 0) availability else engine.setLanguage(locale)
        if (result < 0) { // LANG_MISSING_DATA, LANG_NOT_SUPPORTED (ERROR shares LANG_MISSING_DATA's value)
            Log.w(TAG, "TextToSpeech language unavailable: $tag ($result)")
            restoreDefault(engine)
            if (reported.add(tag)) main.post { onUnsupported(tag) }
        }
    }

    private fun restoreDefault(engine: TextToSpeech) {
        val voice = engine.defaultVoice
        if (voice == null || engine.setVoice(voice) == TextToSpeech.ERROR) engine.setLanguage(Locale.getDefault())
    }

    private companion object {
        const val TAG = "Tsukaima"

        /** How long the focus outlives the last sentence, so the next one does not re-raise the music in between. */
        const val FOCUS_RELEASE_DELAY_MS = 500L

        val SPEECH_ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
    }
}
