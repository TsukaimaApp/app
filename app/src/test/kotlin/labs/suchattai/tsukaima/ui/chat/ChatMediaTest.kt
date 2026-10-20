package labs.suchattai.tsukaima.ui.chat

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import java.io.IOException
import java.lang.reflect.Proxy

/** Images in replies (`![alt](https://…)` lines): fetched through [ChatBackend.fetchImage], decoded, cached per URL. */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatMediaTest {
    private object Bitmap : ImageBitmap {
        override val width = 2
        override val height = 2
        override val colorSpace get() = error("unused")
        override val hasAlpha = false
        override val config = ImageBitmapConfig.Argb8888
        override fun readPixels(buffer: IntArray, startX: Int, startY: Int, width: Int, height: Int, bufferOffset: Int, stride: Int) = Unit
        override fun prepareToDraw() = Unit
    }

    /** Everything but [fetchImage] and [decodePreview] is unexpected. */
    private class Backend : ChatBackend by unexpected() {
        val fetched = mutableListOf<String>()
        var gate: CompletableDeferred<Unit>? = null
        var result: (String) -> Result<ByteArray> = { Result.success(byteArrayOf(1, 2, 3)) }
        var decoded = 0

        override suspend fun fetchImage(url: String): Result<ByteArray> {
            fetched += url
            gate?.await()
            return result(url)
        }

        override fun decodePreview(bytes: ByteArray): ImageBitmap? = Bitmap.also { decoded++ }
    }

    private class Harness(scope: TestScope) {
        val backend = Backend()
        val media = ChatMedia(backend, scope, StandardTestDispatcher(scope.testScheduler), MutableStateFlow(ChatUiState()))
    }

    private val url = "https://example.com/chart.png"

    @Test
    fun imageLoadIsSharedAndCached() = runTest {
        val h = Harness(this)
        h.backend.gate = CompletableDeferred()
        val first = async { h.media.imagePreview(url) }
        val second = async { h.media.imagePreview(url) } // concurrent: shares the fetch
        advanceUntilIdle()
        h.backend.gate!!.complete(Unit)
        advanceUntilIdle()
        assertSame(Bitmap, first.await())
        assertSame(Bitmap, second.await())
        assertSame(Bitmap, h.media.imagePreview(url))
        assertEquals(listOf(url), h.backend.fetched)
        assertEquals(1, h.backend.decoded)
    }

    @Test
    fun imageLoadFailureIsCached() = runTest {
        val h = Harness(this)
        h.backend.result = { Result.failure(IOException("connection reset")) }
        assertNull(h.media.imagePreview(url))
        assertNull(h.media.imagePreview(url)) // no retry storm: even a network failure stays failed for the session
        assertEquals(1, h.backend.fetched.size)
        assertEquals(0, h.backend.decoded)

        // Undecodable bytes are a failure too, and another URL is its own entry.
        val other = "https://example.com/not-an-image.png"
        val h2 = Harness(this)
        h2.backend.result = { Result.success(byteArrayOf(0)) }
        val undecodable = object : ChatBackend by h2.backend {
            override fun decodePreview(bytes: ByteArray): ImageBitmap? = null
        }
        val media = ChatMedia(undecodable, this, StandardTestDispatcher(testScheduler), MutableStateFlow(ChatUiState()))
        assertNull(media.imagePreview(other))
        assertNull(media.imagePreview(other))
        assertEquals(listOf(other), h2.backend.fetched)
    }

    private companion object {
        fun unexpected(): ChatBackend =
            Proxy.newProxyInstance(ChatBackend::class.java.classLoader, arrayOf(ChatBackend::class.java)) { _, m, _ ->
                error("unexpected ChatBackend.${m.name}")
            } as ChatBackend
    }
}
