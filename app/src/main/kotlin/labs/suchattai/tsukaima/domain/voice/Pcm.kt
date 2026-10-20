package labs.suchattai.tsukaima.domain.voice

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/** 16-bit PCM helpers for server-side recognition: frame levels for the detector and the WAV upload. */
object Pcm {
    private const val HEADER_BYTES = 44

    /** Root mean square of the first [n] samples of [frame] (0 for none). */
    fun rms(frame: ShortArray, n: Int): Double {
        if (n <= 0) return 0.0
        var sum = 0.0
        for (i in 0 until n) {
            val s = frame[i].toDouble()
            sum += s * s
        }
        return sqrt(sum / n)
    }

    /** [pcm16kMono] (little-endian 16-bit mono samples) as a RIFF/WAVE file: a 44-byte header, then the data. */
    fun wav(pcm16kMono: ByteArray, sampleRate: Int = 16000): ByteArray {
        val b = ByteBuffer.allocate(HEADER_BYTES + pcm16kMono.size).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray(Charsets.US_ASCII))
        b.putInt(36 + pcm16kMono.size)
        b.put("WAVE".toByteArray(Charsets.US_ASCII))
        b.put("fmt ".toByteArray(Charsets.US_ASCII))
        b.putInt(16) // fmt chunk size
        b.putShort(1) // PCM
        b.putShort(1) // mono
        b.putInt(sampleRate)
        b.putInt(sampleRate * 2) // byte rate
        b.putShort(2) // block align
        b.putShort(16) // bits per sample
        b.put("data".toByteArray(Charsets.US_ASCII))
        b.putInt(pcm16kMono.size)
        b.put(pcm16kMono)
        return b.array()
    }
}
