package app.tsukaima.domain.voice

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PcmTest {
    @Test fun rmsOfASquareWaveIsItsAmplitude() {
        val frame = ShortArray(320) { if (it % 2 == 0) 1000 else -1000 }
        assertEquals(1000.0, Pcm.rms(frame, 320), 1e-9)
    }

    @Test fun rmsReadsOnlyTheFirstNSamples() {
        val frame = ShortArray(8) { if (it < 4) 3 else 30000 }
        assertEquals(3.0, Pcm.rms(frame, 4), 1e-9)
        assertEquals(0.0, Pcm.rms(frame, 0), 0.0)
    }

    @Test fun wavHasA44ByteMonoSixteenBitHeader() {
        val pcm = ByteArray(640) { it.toByte() }
        val wav = Pcm.wav(pcm)
        assertEquals(44 + 640, wav.size)
        val b = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        fun tag(at: Int) = String(wav, at, 4, Charsets.US_ASCII)
        assertEquals("RIFF", tag(0))
        assertEquals(36 + 640, b.getInt(4))
        assertEquals("WAVE", tag(8))
        assertEquals("fmt ", tag(12))
        assertEquals(16, b.getInt(16))
        assertEquals(1, b.getShort(20).toInt()) // PCM
        assertEquals(1, b.getShort(22).toInt()) // mono
        assertEquals(16000, b.getInt(24))
        assertEquals(32000, b.getInt(28)) // byte rate
        assertEquals(2, b.getShort(32).toInt()) // block align
        assertEquals(16, b.getShort(34).toInt()) // bits
        assertEquals("data", tag(36))
        assertEquals(640, b.getInt(40))
        assertArrayEquals(pcm, wav.copyOfRange(44, wav.size))
    }

    @Test fun wavCarriesTheGivenSampleRate() {
        val b = ByteBuffer.wrap(Pcm.wav(ByteArray(2), sampleRate = 8000)).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(8000, b.getInt(24))
        assertEquals(16000, b.getInt(28))
    }
}
