package app.tsukaima.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import app.tsukaima.data.api.ApiException
import app.tsukaima.data.api.SpeechDto
import app.tsukaima.domain.voice.DataUrl
import app.tsukaima.domain.voice.SpeechOutput
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * [SpeechOutput] through Hermes' text-to-speech relay: each [speak] has the server synthesise the sentence
 * ([synthesize], the `/api/audio/speak` call), writes the returned audio to `cache/tts/<uuid>.<ext>` (the extension
 * from its MIME type, so the player can tell the container) and plays it with a [MediaPlayer] (assistant usage, speech
 * content), suspending until playback ends. The file is deleted when the sentence finishes, fails or is cancelled;
 * files left by an earlier process are cleared on the first sentence's write (off the main thread).
 *
 * Failures never surface: a refused or failed synthesis, unreadable audio or a playback error is logged at warn (never
 * with the text) and [speak] returns, so the voice controller reveals the text and moves on. Cancelling [speak] stops
 * playback (or aborts the request); [stop] stops the sentence playing and drops any sentence still being synthesised,
 * so it does not play once its audio arrives. Audio focus is held across a run of sentences like
 * [AndroidSpeechOutput]'s; losing it to another app stops the sentence playing (and drops one being synthesised).
 *
 * The voice language setting does not apply: the server's TTS voice and language settings decide, so [languages] is
 * empty. One instance per voice session (it plays one sentence at a time).
 */
class ServerSpeechOutput(
    context: Context,
    private val synthesize: suspend (String) -> Result<SpeechDto>,
) : SpeechOutput {
    private val appContext = context.applicationContext
    private val dir = File(appContext.cacheDir, CACHE_DIR)
    private val focus = AudioFocus(appContext, onLoss = ::stop)
    private val main = Handler(Looper.getMainLooper())
    private val releaseFocus = Runnable { if (inFlight.get() == 0) focus.release() }

    /** [speak] calls in progress (synthesising or playing); focus goes when this stays 0 [FOCUS_RELEASE_DELAY_MS]. */
    private val inFlight = AtomicInteger()

    /** The sentence playing now, for [stop]. */
    @Volatile
    private var playback: Playback? = null

    /** Bumped by [stop]: a [speak] that started before the bump does not play. */
    private val stops = AtomicInteger()

    /** The stale `cache/tts` files (a process that died mid-sentence) are cleared, once, on the first write. */
    private val swept = AtomicBoolean()

    override val available: Boolean = true

    override suspend fun speak(text: String) {
        if (text.isBlank()) return
        // Counted from the request on: focus held from the previous sentence survives this one's synthesis.
        inFlight.incrementAndGet()
        main.removeCallbacks(releaseFocus)
        val epoch = stops.get()
        try {
            val file = synthesizeToFile(text) ?: return
            try {
                if (stops.get() != epoch) return // stopped while synthesising
                focus.request()
                play(file)
            } finally {
                file.delete()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            Log.w(TAG, "Server speech output: playback failed (${e.javaClass.simpleName})")
        } catch (e: RuntimeException) { // IllegalStateException / IllegalArgumentException from MediaPlayer
            Log.w(TAG, "Server speech output: playback failed (${e.javaClass.simpleName})")
        } finally {
            if (inFlight.decrementAndGet() == 0) main.postDelayed(releaseFocus, FOCUS_RELEASE_DELAY_MS)
        }
    }

    /** [text]'s audio from the server, written to the cache; null (logged) when there is none to play. */
    private suspend fun synthesizeToFile(text: String): File? {
        val speech = synthesize(text).getOrElse { e ->
            val code = (e as? ApiException)?.code?.let { " $it" }.orEmpty()
            Log.w(TAG, "Server speech output: synthesis failed (${e.javaClass.simpleName}$code)")
            return null
        }
        if (!speech.ok) {
            Log.w(TAG, "Server speech output: no audio (ok=false)")
            return null
        }
        var file: File? = null
        try {
            withContext(Dispatchers.IO) {
                if (swept.compareAndSet(false, true)) dir.listFiles()?.forEach { it.delete() }
                val (mime, audio) = DataUrl.decode(speech.dataUrl)
                if (audio.isEmpty()) return@withContext
                val f = File(dir, "${UUID.randomUUID()}.${DataUrl.audioExtension(speech.mimeType ?: mime)}")
                file = f
                dir.mkdirs()
                f.writeBytes(audio)
            }
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Server speech output: unreadable audio (${e.message})")
            return null
        } catch (e: Throwable) { // cancelled, or the write failed (IOException, logged by speak)
            file?.delete()
            throw e
        }
        if (file == null) Log.w(TAG, "Server speech output: no audio (0 bytes)")
        return file
    }

    /** Plays [file] to its end (or an error, or [stop]); cancellation stops it. The player is always released. */
    private suspend fun play(file: File) {
        val p = Playback(MediaPlayer())
        playback = p
        try {
            p.player.setAudioAttributes(SPEECH_ATTRIBUTES)
            // Callbacks arrive on this thread's Looper (main, from the view model), else on the main thread's.
            p.player.setOnCompletionListener { p.done.complete(Unit) }
            p.player.setOnErrorListener { _, what, extra ->
                Log.w(TAG, "Server speech output: player error $what/$extra")
                p.done.complete(Unit)
                true
            }
            withContext(Dispatchers.IO) {
                p.player.setDataSource(file.path)
                p.player.prepare()
            }
            if (p.done.isCompleted) return // stopped while preparing
            p.player.start()
            p.done.await()
        } finally {
            if (playback === p) playback = null
            p.release()
        }
    }

    override fun stop() {
        stops.incrementAndGet()
        playback?.let {
            it.done.complete(Unit)
            it.release()
        }
    }

    override fun languages(): Set<String> = emptySet()

    /** One sentence's player; [release] runs once, from [stop] or from the [speak] that owns it. */
    private class Playback(val player: MediaPlayer) {
        val done = CompletableDeferred<Unit>()
        private val released = AtomicBoolean()

        fun release() {
            if (released.compareAndSet(false, true)) player.release()
        }
    }

    private companion object {
        const val TAG = "Tsukaima"

        /** Under the app's cache dir; holds at most the sentence playing. */
        const val CACHE_DIR = "tts"

        /** How long the focus outlives the last sentence, so the next one does not re-raise the music in between. */
        const val FOCUS_RELEASE_DELAY_MS = 500L

        val SPEECH_ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
    }
}
