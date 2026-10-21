package app.tsukaima.domain.voice

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DataUrlTest {
    @Test
    fun decodesTheMimeTypeAndTheBase64Payload() {
        val (mime, bytes) = DataUrl.decode("data:audio/mpeg;base64,SUQzBA==")
        assertEquals("audio/mpeg", mime)
        assertArrayEquals(byteArrayOf(0x49, 0x44, 0x33, 0x04), bytes)
    }

    @Test
    fun dropsMediaTypeParameters() {
        val (mime, bytes) = DataUrl.decode("data:audio/ogg;codecs=opus;base64,AAE=")
        assertEquals("audio/ogg", mime)
        assertArrayEquals(byteArrayOf(0, 1), bytes)
    }

    @Test
    fun anEmptyPayloadDecodesToNoBytes() {
        assertEquals(0, DataUrl.decode("data:audio/wav;base64,").second.size)
    }

    @Test
    fun rejectsWhatIsNotABase64DataUrl() {
        listOf(
            "https://example.com/a.mp3",
            "data:audio/mpeg,plain", // not base64
            "data:audio/mpeg;base64", // no comma
            "data:audio/mpeg;base64,***", // not base64 text
        ).forEach { url ->
            assertThrows(url, IllegalArgumentException::class.java) { DataUrl.decode(url) }
        }
    }

    @Test
    fun audioExtensionFollowsTheMimeType() {
        assertEquals("mp3", DataUrl.audioExtension("audio/mpeg"))
        assertEquals("mp3", DataUrl.audioExtension("audio/mp3"))
        assertEquals("wav", DataUrl.audioExtension("audio/wav"))
        assertEquals("wav", DataUrl.audioExtension("audio/x-wav"))
        assertEquals("ogg", DataUrl.audioExtension("audio/ogg"))
        assertEquals("ogg", DataUrl.audioExtension("Audio/OGG"))
        assertEquals("bin", DataUrl.audioExtension("audio/flac"))
        assertEquals("bin", DataUrl.audioExtension(""))
    }
}
