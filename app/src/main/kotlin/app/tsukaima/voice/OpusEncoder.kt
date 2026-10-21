package app.tsukaima.voice

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File

/** Compresses one recorded utterance for upload; null when it cannot (the caller then sends the WAV). */
interface AudioEncoder {
    /** [pcm16kMono] (little-endian 16-bit mono samples at 16 kHz) encoded, or null on any failure. */
    fun encode(pcm16kMono: ByteArray): EncodedAudio?
}

/** Encoded audio and the MIME type the upload labels it with. */
data class EncodedAudio(val bytes: ByteArray, val mime: String)

/**
 * [AudioEncoder] to Opus in an Ogg container (`audio/ogg`) with the platform's `MediaCodec` and `MediaMuxer`: 16 kHz
 * mono at 24 kbps, about a twentieth of the WAV's size. The muxer needs a file, so the utterance passes through a
 * temporary file in [cacheDir] that is deleted before [encode] returns; files a killed process left behind are swept
 * when the encoder is created. Never throws: a phone without an Opus encoder, any codec failure, an empty result or
 * running out of memory yields null, logged once per instance (without any audio).
 */
class OpusEncoder(private val cacheDir: File) : AudioEncoder {
    @Volatile private var warned = false

    init {
        runCatching {
            cacheDir.listFiles { f -> f.name.startsWith(PREFIX) && f.name.endsWith(SUFFIX) }?.forEach { it.delete() }
        }
    }

    override fun encode(pcm16kMono: ByteArray): EncodedAudio? {
        var file: File? = null
        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null
        return try {
            val out = File.createTempFile(PREFIX, SUFFIX, cacheDir).also { file = it }
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, SAMPLE_RATE, 1).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
            }
            // Assigned before configure/start, so the finally block releases it when either throws.
            val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS).also { codec = it }
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()
            val ogg = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG).also { muxer = it }
            drain(encoder, ogg, pcm16kMono)
            ogg.stop()
            ogg.release()
            muxer = null
            val bytes = out.readBytes()
            check(bytes.isNotEmpty()) { "empty output" }
            EncodedAudio(bytes, MIME)
        } catch (e: Exception) {
            fallback(e)
        } catch (e: OutOfMemoryError) {
            fallback(e)
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { muxer?.release() }
            runCatching { file?.delete() }
        }
    }

    private fun fallback(e: Throwable): EncodedAudio? {
        if (!warned) {
            warned = true
            Log.w(TAG, "Opus encoding failed, uploading WAV (${e.javaClass.simpleName})")
        }
        return null
    }

    /** Feeds [pcm] through [codec] in input-buffer-sized chunks, then writes every encoded packet until end of stream. */
    private fun drain(codec: MediaCodec, muxer: MediaMuxer, pcm: ByteArray) {
        val info = MediaCodec.BufferInfo()
        val deadline = System.nanoTime() + STALL_NS
        var offset = 0
        var inputDone = false
        var track = -1
        while (true) {
            if (!inputDone) {
                val index = codec.dequeueInputBuffer(TIMEOUT_US)
                if (index >= 0) {
                    val buffer = checkNotNull(codec.getInputBuffer(index)) { "no input buffer" }
                    buffer.clear()
                    val n = minOf(buffer.remaining() and 1.inv(), pcm.size - offset) // whole samples only
                    buffer.put(pcm, offset, n)
                    val ptsUs = offset / 2 * 1_000_000L / SAMPLE_RATE
                    offset += n
                    inputDone = offset >= pcm.size
                    codec.queueInputBuffer(index, 0, n, ptsUs, if (inputDone) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                }
            }
            val index = codec.dequeueOutputBuffer(info, TIMEOUT_US)
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                check(track < 0) { "format changed twice" }
                track = muxer.addTrack(codec.outputFormat) // carries the codec-specific data the Ogg header needs
                muxer.start()
            } else if (index >= 0) {
                val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                if (!isConfig && info.size > 0) {
                    check(track >= 0) { "data before format" }
                    val buffer = checkNotNull(codec.getOutputBuffer(index)) { "no output buffer" }
                    buffer.position(info.offset).limit(info.offset + info.size)
                    muxer.writeSampleData(track, buffer, info)
                }
                codec.releaseOutputBuffer(index, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
            }
            check(System.nanoTime() < deadline) { "encoder stalled" }
        }
    }

    private companion object {
        const val MIME = "audio/ogg"
        const val PREFIX = "utterance"
        const val SUFFIX = ".ogg"
        const val BIT_RATE = 24_000
        const val TIMEOUT_US = 10_000L

        /** A codec that has not reached end of stream by then is treated as failed (WAV fallback). */
        const val STALL_NS = 15_000_000_000L
    }
}
