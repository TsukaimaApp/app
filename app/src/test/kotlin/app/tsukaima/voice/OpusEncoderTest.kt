package app.tsukaima.voice

import kotlinx.coroutines.test.runTest
import app.tsukaima.data.api.TranscriptionDto
import app.tsukaima.domain.voice.Pcm
import app.tsukaima.domain.voice.SpeechEvent
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/** The upload step of [ServerSpeechInput]: Opus when the encoder manages, the WAV otherwise. */
class OpusEncoderTest {
    private val pcm = ByteArray(640) { (it % 7).toByte() }

    private class Sent(val bytes: ByteArray, val mime: String)

    private fun uploader(encoder: AudioEncoder, sent: MutableList<Sent>) = Uploader(encoder) { bytes, mime ->
        sent += Sent(bytes, mime)
        Result.success(TranscriptionDto(ok = true, transcript = " hello "))
    }

    @Test
    fun fallbackOnFailure() = runTest {
        val sent = mutableListOf<Sent>()
        val event = uploader(object : AudioEncoder {
            override fun encode(pcm16kMono: ByteArray): EncodedAudio? = null
        }, sent).upload(pcm, maxLength = false)
        assertEquals(SpeechEvent.Final("hello"), event)
        assertEquals(1, sent.size)
        assertEquals("audio/wav", sent[0].mime)
        assertArrayEquals(Pcm.wav(pcm, 16_000), sent[0].bytes)
    }

    @Test
    fun encodedAudioIsSentAsOgg() = runTest {
        val sent = mutableListOf<Sent>()
        val opus = byteArrayOf(79, 103, 103, 83, 1, 2, 3)
        var given: ByteArray? = null
        val event = uploader(object : AudioEncoder {
            override fun encode(pcm16kMono: ByteArray): EncodedAudio {
                given = pcm16kMono
                return EncodedAudio(opus, "audio/ogg")
            }
        }, sent).upload(pcm, maxLength = false)
        assertEquals(SpeechEvent.Final("hello"), event)
        assertArrayEquals(pcm, given)
        assertEquals(1, sent.size)
        assertEquals("audio/ogg", sent[0].mime)
        assertArrayEquals(opus, sent[0].bytes)
    }
}
