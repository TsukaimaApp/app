package labs.suchattai.tsukaima.voice

import kotlinx.coroutines.test.runTest
import labs.suchattai.tsukaima.data.api.TranscriptionDto
import labs.suchattai.tsukaima.domain.voice.SpeechEvent
import org.junit.Assert.assertEquals
import org.junit.Test

/** The loud-room guard of [Uploader]: repeated empty transcripts of max-length utterances stop the listening loop. */
class UploaderTest {
    private object NoEncoder : AudioEncoder {
        override fun encode(pcm16kMono: ByteArray): EncodedAudio? = null
    }

    private val pcm = ByteArray(640)
    private val silent = SpeechEvent.Error("Nothing recognised", retry = true, silence = true)
    private val noisy = SpeechEvent.Error("Too noisy to hear you — tap to retry", retry = false)

    private fun uploader(vararg transcripts: String): Uploader {
        val queue = ArrayDeque(transcripts.toList())
        return Uploader(NoEncoder) { _, _ -> Result.success(TranscriptionDto(ok = true, transcript = queue.removeFirst())) }
    }

    @Test
    fun twoMaxLengthEmptyUtterancesStopListening() = runTest {
        val u = uploader("", "")
        assertEquals(silent, u.upload(pcm, maxLength = true))
        assertEquals(noisy, u.upload(pcm, maxLength = true))
    }

    @Test
    fun tooNoisyResetsTheCountForTheRetry() = runTest {
        // "Tap to retry" gets two fresh chances, not an immediate second "too noisy".
        val u = uploader("", "", "", "")
        assertEquals(silent, u.upload(pcm, maxLength = true))
        assertEquals(noisy, u.upload(pcm, maxLength = true))
        assertEquals(silent, u.upload(pcm, maxLength = true))
        assertEquals(noisy, u.upload(pcm, maxLength = true))
    }

    @Test
    fun aTranscriptBetweenResetsTheCount() = runTest {
        val u = uploader("", "hello", "")
        assertEquals(silent, u.upload(pcm, maxLength = true))
        assertEquals(SpeechEvent.Final("hello"), u.upload(pcm, maxLength = true))
        assertEquals(silent, u.upload(pcm, maxLength = true))
    }

    @Test
    fun shortEmptyUtterancesDoNotCount() = runTest {
        val u = uploader("", "", "")
        assertEquals(silent, u.upload(pcm, maxLength = true))
        assertEquals(silent, u.upload(pcm, maxLength = false))
        assertEquals(silent, u.upload(pcm, maxLength = false))
    }

    @Test
    fun failedUploadNeitherCountsNorResets() = runTest {
        var fail = false
        val u = Uploader(NoEncoder) { _, _ ->
            if (fail) Result.failure(java.io.IOException("down")) else Result.success(TranscriptionDto(ok = true, transcript = ""))
        }
        assertEquals(silent, u.upload(pcm, maxLength = true))
        fail = true
        u.upload(pcm, maxLength = true)
        fail = false
        assertEquals(noisy, u.upload(pcm, maxLength = true))
    }
}
