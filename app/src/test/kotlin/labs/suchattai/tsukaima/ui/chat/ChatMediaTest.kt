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
import labs.suchattai.tsukaima.data.api.ApiException
import labs.suchattai.tsukaima.data.api.ImageTooLargeException
import labs.suchattai.tsukaima.data.prefs.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
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

    /** Everything but [settings], [fetchImage] and [decodePreview] is unexpected. */
    private class Backend : ChatBackend by unexpected() {
        override val settings = MutableStateFlow<Settings?>(Settings(serverUrl = "https://hermes.example.com"))
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

    private class Harness(scope: TestScope, backend: ChatBackend = Backend()) {
        val media = ChatMedia(backend, scope, StandardTestDispatcher(scope.testScheduler), MutableStateFlow(ChatUiState()))
    }

    private val url = "https://hermes.example.com/api/files/chart.png"
    private val foreign = "https://tiles.example.org/chart.png"

    @Test
    fun imageLoadIsSharedAndCached() = runTest {
        val b = Backend().apply { gate = CompletableDeferred() }
        val h = Harness(this, b)
        val first = async { h.media.imagePreview(url) }
        val second = async { h.media.imagePreview(url) } // concurrent: shares the fetch
        advanceUntilIdle()
        b.gate!!.complete(Unit)
        advanceUntilIdle()
        assertSame(Bitmap, first.await())
        assertSame(Bitmap, second.await())
        assertSame(Bitmap, h.media.imagePreview(url))
        assertEquals(listOf(url), b.fetched)
        assertEquals(1, b.decoded)
    }

    @Test
    fun imageLoadFailureIsCached() = runTest {
        // The server's answer is final: one fetch.
        val b = Backend().apply { result = { Result.failure(ApiException(404, "gone")) } }
        val h = Harness(this, b)
        assertNull(h.media.imagePreview(url))
        assertNull(h.media.imagePreview(url))
        assertEquals(1, b.fetched.size)

        // Over the cap and undecodable bytes are final too.
        b.result = { Result.failure(ImageTooLargeException("Image over 8 MB")) }
        val big = "$url?big"
        assertNull(h.media.imagePreview(big))
        assertNull(h.media.imagePreview(big))
        assertEquals(2, b.fetched.size)

        val inner = Backend()
        val undecodable = object : ChatBackend by inner {
            override fun decodePreview(bytes: ByteArray): ImageBitmap? = null
        }
        val u = Harness(this, undecodable)
        assertNull(u.media.imagePreview(url))
        assertNull(u.media.imagePreview(url))
        assertEquals(1, inner.fetched.size)
    }

    @Test
    fun transientFailureIsRetriedLater() = runTest {
        val b = Backend().apply { result = { Result.failure(IOException("connection reset")) } }
        val h = Harness(this, b)
        assertNull(h.media.imagePreview(url))
        b.result = { Result.success(byteArrayOf(1)) }
        assertSame(Bitmap, h.media.imagePreview(url))
        assertEquals(2, b.fetched.size)
    }

    @Test
    fun serverErrorsAndRateLimitsAreRetriedLater() = runTest {
        // 5xx and 429 are the server having a bad moment, not its verdict on the image: not cached.
        for (code in listOf(500, 503, 429)) {
            val b = Backend().apply { result = { Result.failure(ApiException(code, "busy")) } }
            val h = Harness(this, b)
            assertNull(h.media.imagePreview(url))
            b.result = { Result.success(byteArrayOf(1)) }
            assertSame("$code is retried", Bitmap, h.media.imagePreview(url))
            assertEquals(2, b.fetched.size)
        }
        // Other 4xx (and a refused redirect) stay cached.
        for (code in listOf(400, 403, 404, 302)) {
            val b = Backend().apply { result = { Result.failure(ApiException(code, "no")) } }
            val h = Harness(this, b)
            assertNull(h.media.imagePreview(url))
            b.result = { Result.success(byteArrayOf(1)) }
            assertNull("$code is final", h.media.imagePreview(url))
            assertEquals(1, b.fetched.size)
        }
    }

    @Test
    fun foreignImageLoadsOnlyAfterATap() = runTest {
        val b = Backend()
        val h = Harness(this, b)
        assertTrue(h.media.imageAllowed(url))
        assertFalse(h.media.imageAllowed(foreign))
        assertNull(h.media.imagePreview(foreign))
        assertEquals(emptyList<String>(), b.fetched) // never fetched unasked

        h.media.allowImage(foreign)
        assertTrue(h.media.imageAllowed(foreign))
        assertSame(Bitmap, h.media.imagePreview(foreign))
        assertEquals(listOf(foreign), b.fetched)

        // No server configured yet: nothing is same-origin.
        b.settings.value = null
        assertFalse(h.media.imageAllowed(url))
    }

    private companion object {
        fun unexpected(): ChatBackend =
            Proxy.newProxyInstance(ChatBackend::class.java.classLoader, arrayOf(ChatBackend::class.java)) { _, m, _ ->
                error("unexpected ChatBackend.${m.name}")
            } as ChatBackend
    }
}
