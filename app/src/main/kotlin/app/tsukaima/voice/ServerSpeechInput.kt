package app.tsukaima.voice

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import app.tsukaima.data.api.ApiException
import app.tsukaima.data.api.AuthException
import app.tsukaima.data.api.TranscriptionDto
import app.tsukaima.domain.voice.Pcm
import app.tsukaima.domain.voice.SpeechEvent
import app.tsukaima.domain.voice.SpeechInput
import app.tsukaima.domain.voice.UtteranceDetector
import app.tsukaima.domain.voice.VadEvent
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * [SpeechInput] through Hermes' speech-to-text relay: each [listen] run records 16 kHz mono PCM from the microphone,
 * lets an [UtteranceDetector] decide where the utterance ends, then uploads it ([transcribe], the
 * `/api/audio/transcribe` call, given the bytes and their MIME type) as Opus from the encoder, or as a WAV when
 * encoding fails, and reports the server's text as the [SpeechEvent.Final]. There are no live partials: the provider
 * does not stream, so the run reports [SpeechEvent.Transcribing] while the upload is in flight.
 *
 * The audio stays in memory for one utterance only (trimmed to [LEAD_IN_MS] before the first speech frame) and is
 * never logged; neither is the transcript. The only copy on disk is the Opus encoder's temporary file, deleted right
 * after muxing and swept when the encoder is created (in case the process died mid-encode).
 * The voice language setting does not apply: the request carries no language, so the server's STT setting (Whisper
 * auto-detection) decides.
 *
 * RECORD_AUDIO is granted before voice mode opens; a recorder that cannot start is reported as a fatal error.
 */
class ServerSpeechInput(
    private val context: Context,
    transcribe: suspend (ByteArray, String) -> Result<TranscriptionDto>,
    private val detector: () -> UtteranceDetector = { UtteranceDetector() },
    encoder: AudioEncoder = OpusEncoder(context.cacheDir),
) : SpeechInput {
    override val available: Boolean = true
    private val uploader = Uploader(encoder, transcribe)

    @SuppressLint("MissingPermission") // granted before voice mode opens; a refusal fails initialisation below
    override fun listen(): Flow<SpeechEvent> = callbackFlow {
        val vad = detector()
        val frameSamples = SAMPLE_RATE * vad.config.frameMs / 1000
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuffer, frameSamples * 2) * 4,
            )
        } catch (e: RuntimeException) { // IllegalArgumentException, SecurityException
            Log.w(TAG, "Server speech: recorder unavailable", e)
            null
        }
        if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
            record?.release()
            send(SpeechEvent.Error(MIC_UNAVAILABLE, retry = false))
            close()
            return@callbackFlow
        }
        val pcm = ByteArrayOutputStream()
        var event: VadEvent = VadEvent.Listening
        try {
            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) throw IllegalStateException("not recording")
            val frame = ShortArray(frameSamples)
            val bytes = ByteBuffer.allocate(frameSamples * 2).order(ByteOrder.LITTLE_ENDIAN)
            while (isActive && (event == VadEvent.Listening || event == VadEvent.Speech)) {
                var filled = 0
                while (filled < frameSamples) {
                    val n = record.read(frame, filled, frameSamples - filled)
                    if (n <= 0) throw IllegalStateException("read returned $n")
                    filled += n
                }
                bytes.clear()
                bytes.asShortBuffer().put(frame, 0, frameSamples)
                pcm.write(bytes.array(), 0, frameSamples * 2)
                event = vad.feed(Pcm.rms(frame, frameSamples))
            }
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Server speech: recording failed: ${e.message}")
            event = VadEvent.Listening
            trySend(SpeechEvent.Error(MIC_UNAVAILABLE, retry = true))
        } finally {
            runCatching { record.stop() }
            record.release()
        }

        when (event) {
            VadEvent.NoSpeech -> send(SpeechEvent.Error(NO_SPEECH, retry = true, silence = true))
            VadEvent.EndOfUtterance -> {
                val leadFrames = LEAD_IN_MS / vad.config.frameMs
                val from = ((vad.speechStartFrame ?: 0) - leadFrames).coerceAtLeast(0) * frameSamples * 2
                val all = pcm.toByteArray()
                pcm.reset()
                val utterance = all.copyOfRange(from, all.size)
                send(SpeechEvent.Transcribing)
                val maxLength = utterance.size / 2 * 1000L / SAMPLE_RATE >= vad.config.maxUtteranceMs - vad.config.frameMs
                send(uploader.upload(utterance, maxLength))
            }
            else -> Unit // cancelled, or the recorder failed (reported above)
        }
        close()
    }.flowOn(Dispatchers.IO)

    private companion object {
        /** Audio kept before the first speech frame, so a soft onset is not clipped. */
        const val LEAD_IN_MS = 300

        const val MIC_UNAVAILABLE = "Microphone isn’t available"
        const val NO_SPEECH = "No speech heard"
    }
}

/**
 * One utterance from PCM to the run's last event: encode it with [encoder] (falling back to [Pcm.wav] as `audio/wav`
 * when that yields null), upload it through [transcribe] with its MIME type, and map the relay's answer.
 */
internal class Uploader(
    private val encoder: AudioEncoder,
    private val transcribe: suspend (ByteArray, String) -> Result<TranscriptionDto>,
) {
    /** Max-length utterances in a row (across this uploader's runs) that came back empty: the room is too loud. */
    private var emptyMaxLength = 0

    /**
     * [pcm16kMono] transcribed: a [SpeechEvent.Final], or the [SpeechEvent.Error] that ends the run. [maxLength] says
     * the utterance ran to the VAD's limit (it never saw silence); the second such utterance in a row to come back
     * empty is reported as a fatal "too noisy" error instead of letting the run loop on 30-second uploads.
     */
    suspend fun upload(pcm16kMono: ByteArray, maxLength: Boolean): SpeechEvent {
        val audio = encoder.encode(pcm16kMono) ?: EncodedAudio(Pcm.wav(pcm16kMono, SAMPLE_RATE), WAV)
        val event = outcome(transcribe(audio.bytes, audio.mime))
        when {
            event is SpeechEvent.Final -> emptyMaxLength = 0
            event is SpeechEvent.Error && event.silence && maxLength -> {
                if (++emptyMaxLength >= 2) {
                    emptyMaxLength = 0 // "tap to retry" starts with two fresh chances
                    return SpeechEvent.Error(TOO_NOISY, retry = false)
                }
            }
        }
        return event
    }

    /** The run's result from the relay's answer; nothing of the transcript is logged. */
    private fun outcome(result: Result<TranscriptionDto>): SpeechEvent {
        result.onSuccess { dto ->
            val text = dto.transcript.trim()
            return if (text.isNotEmpty()) SpeechEvent.Final(text) else SpeechEvent.Error(NOTHING, retry = true, silence = true)
        }
        val e = result.exceptionOrNull()
        return when {
            e is ApiException -> {
                val detail = e.detail ?: REFUSED
                Log.w(TAG, "Server speech: relay answered ${e.code}")
                val fatal = e.code in 400..499 && FATAL_HINTS.any { detail.contains(it, ignoreCase = true) }
                SpeechEvent.Error(detail, retry = !fatal)
            }
            e is AuthException -> SpeechEvent.Error("Sign in to Hermes again to use voice", retry = false)
            else -> {
                Log.w(TAG, "Server speech: transcription failed (${e?.javaClass?.simpleName})")
                SpeechEvent.Error(UNREACHABLE, retry = true)
            }
        }
    }

    private companion object {
        const val WAV = "audio/wav"
        const val NOTHING = "Nothing recognised"
        const val TOO_NOISY = "Too noisy to hear you — tap to retry"
        const val UNREACHABLE = "Couldn’t reach Hermes to transcribe"
        const val REFUSED = "Hermes couldn’t transcribe that"

        /** A 4xx `detail` with one of these means the server has no usable STT: retrying would not help. */
        val FATAL_HINTS = listOf("not available", "disabled")
    }
}

/** Log tag of the server speech-input path ([ServerSpeechInput], [Uploader], [OpusEncoder]). */
internal const val TAG = "Tsukaima"

/** Sample rate of recorded utterances (16-bit mono PCM), as recorded, encoded and uploaded. */
internal const val SAMPLE_RATE = 16_000
