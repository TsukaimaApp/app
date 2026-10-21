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
import labs.suchattai.tsukaima.data.api.AuthException
import labs.suchattai.tsukaima.data.api.HermesApi
import labs.suchattai.tsukaima.data.api.ImageTooLargeException
import labs.suchattai.tsukaima.data.prefs.Settings
import labs.suchattai.tsukaima.domain.chat.ChatItem
import labs.suchattai.tsukaima.domain.chat.ToolStatus
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

        override fun decodePreview(bytes: ByteArray): ImageBitmap? = Bitmap.also { decoded++; decodedBytes += bytes.toList() }
        val decodedBytes = mutableListOf<List<Byte>>()

        val media = mutableListOf<String>()
        var mediaResult: (String) -> Result<String> = { Result.success("data:image/png;base64,AQID") }
        override suspend fun media(path: String): Result<String> {
            media += path
            return mediaResult(path)
        }
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
    fun authFailureIsCachedNotRetried() = runTest {
        // A 401 on an image is final: retrying on every recomposition would only repeat it.
        val b = Backend().apply { result = { Result.failure(AuthException()) } }
        val h = Harness(this, b)
        assertNull(h.media.imagePreview(url))
        b.result = { Result.success(byteArrayOf(1)) }
        assertNull(h.media.imagePreview(url))
        assertEquals(1, b.fetched.size)
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

    private fun vision(imageUrl: String?) = ChatItem.ToolCall(
        id = "v1", name = "vision_analyze", context = "what is this", args = null, status = ToolStatus.Running,
        output = null, exitCode = null, durationS = null, imageUrl = imageUrl,
    )

    @Test
    fun toolImageSourcesAreClassified() {
        assertEquals(ToolImageSource.Local("~/.hermes/screenshots/a.png"), ToolImageSource.of("~/.hermes/screenshots/a.png"))
        assertEquals(ToolImageSource.Local("/root/.hermes/images/a.png"), ToolImageSource.of("/root/.hermes/images/a.png"))
        assertEquals(ToolImageSource.Local("/root/.hermes/images/a.png"), ToolImageSource.of("file:///root/.hermes/images/a.png"))
        assertEquals(ToolImageSource.Web(url), ToolImageSource.of(url))
        assertEquals(ToolImageSource.Web("HTTP://x.example/a.png"), ToolImageSource.of("HTTP://x.example/a.png"))
        assertEquals(ToolImageSource.Inline("data:image/png;base64,AQID"), ToolImageSource.of("data:image/png;base64,AQID"))
        for (other in listOf("ftp://x.example/a.png", "content://media/1", "relative/a.png", "", "  ", "file://")) {
            assertNull(other, ToolImageSource.of(other))
        }
    }

    @Test
    fun toolImageSourceOfALargeUrlMakesNoCopy() {
        // Classified on the main thread: a multi-megabyte inline image must not be lowercased or trimmed whole.
        val big = "DATA:image/png;base64," + "A".repeat(8 shl 20)
        // The JVM's per-thread allocation counter (not on the Android compile classpath, hence reflection).
        val threads = Class.forName("java.lang.management.ManagementFactory").getMethod("getThreadMXBean").invoke(null)
        val counter = Class.forName("com.sun.management.ThreadMXBean").getMethod("getThreadAllocatedBytes", Long::class.javaPrimitiveType)
        @Suppress("DEPRECATION") val id = Thread.currentThread().id
        fun allocatedSoFar() = counter.invoke(threads, id) as Long
        ToolImageSource.of("data:x") // warm up class loading
        val before = allocatedSoFar()
        val source = ToolImageSource.of(big)
        val allocated = allocatedSoFar() - before
        assertTrue(source is ToolImageSource.Inline)
        assertTrue("allocated $allocated bytes", allocated < (1L shl 20))
        assertEquals(ToolImageSource.Web("HTTPS://x.example/a.png"), ToolImageSource.of(" \tHTTPS://x.example/a.png \n"))
        assertEquals(ToolImageSource.Local("/a.png"), ToolImageSource.of("  FILE:///a.png"))
    }

    @Test
    fun localMediaIsFetchedThroughTheMediaRoute() = runTest {
        val b = Backend()
        val h = Harness(this, b)
        assertEquals(ImageResult.Loaded(Bitmap), h.media.toolImage(vision("~/.hermes/screenshots/a.png")))
        assertEquals(ImageResult.Loaded(Bitmap), h.media.toolImage(vision("~/.hermes/screenshots/a.png"))) // cached
        assertEquals(listOf("~/.hermes/screenshots/a.png"), b.media)
        assertEquals(listOf(listOf<Byte>(1, 2, 3)), b.decodedBytes) // the data URL's bytes, decoded once

        // file:// is the same server path.
        assertEquals(ImageResult.Loaded(Bitmap), h.media.toolImage(vision("file:///root/.hermes/images/b.png")))
        assertEquals("/root/.hermes/images/b.png", b.media.last())

        // An inline data: URL decodes directly; nothing is fetched.
        assertEquals(ImageResult.Loaded(Bitmap), h.media.toolImage(vision("data:image/png;base64,BAU=")))
        assertEquals(2, b.media.size)
        assertEquals(listOf<Byte>(4, 5), b.decodedBytes.last())

        // http(s) follows the reply-image rules: same origin loads, foreign waits for a tap.
        assertEquals(ImageResult.Loaded(Bitmap), h.media.toolImage(vision(url)))
        assertEquals(ImageResult.Unavailable, h.media.toolImage(vision(foreign)))
        assertEquals(listOf(url), b.fetched)

        // No image, or a scheme it cannot fetch: nothing.
        assertEquals(ImageResult.Unavailable, h.media.toolImage(vision(null)))
        assertEquals(ImageResult.Unavailable, h.media.toolImage(vision("ftp://x.example/a.png")))
        assertEquals(2, b.media.size)
        assertEquals(1, b.fetched.size)
    }

    @Test
    fun mediaForbiddenLeavesTheToolRowIntact() = runTest {
        for (code in listOf(403, 404, 413, 415)) {
            val b = Backend().apply { mediaResult = { Result.failure(ApiException(code, "no")) } }
            val h = Harness(this, b)
            val tool = vision("/etc/shadow.png").copy(status = ToolStatus.Done, output = "it is a file")
            val before = tool.copy()
            assertEquals(ImageResult.Unavailable, h.media.toolImage(tool))
            assertEquals(ImageResult.Unavailable, h.media.toolImage(tool)) // final: cached, not refetched
            assertEquals(1, b.media.size)
            assertEquals("$code leaves the row as it was", before, tool)
        }
        // A malformed data URL and an undecodable image are final too.
        val bad = Backend().apply { mediaResult = { Result.success("not a data url") } }
        val hb = Harness(this, bad)
        assertEquals(ImageResult.Unavailable, hb.media.toolImage(vision("~/.hermes/images/a.png")))
        assertEquals(ImageResult.Unavailable, hb.media.toolImage(vision("~/.hermes/images/a.png")))
        assertEquals(1, bad.media.size)
        assertEquals(ImageResult.Unavailable, hb.media.toolImage(vision("data:image/png;base64,@@@")))

        // A dropped connection is transient: a later call retries.
        val flaky = Backend().apply { mediaResult = { Result.failure(IOException("reset")) } }
        val hf = Harness(this, flaky)
        assertEquals(ImageResult.Retry, hf.media.toolImage(vision("~/.hermes/images/a.png")))
        flaky.mediaResult = { Result.success("data:image/png;base64,AQID") }
        assertEquals(ImageResult.Loaded(Bitmap), hf.media.toolImage(vision("~/.hermes/images/a.png")))
        assertEquals(2, flaky.media.size)
    }

    @Test
    fun dataUrlFitsByPayloadLengthAlone() {
        // ceil(3 * 4 / 3) + 4 = 8 base64 chars for 3 bytes.
        assertTrue(Media.dataUrlFits("data:image/png;base64," + "A".repeat(8), 3))
        assertFalse(Media.dataUrlFits("data:image/png;base64," + "A".repeat(9), 3))
        assertTrue("no comma: left to the decoder", Media.dataUrlFits("data:image/png", 3))
        val max = 25L * 1024 * 1024
        assertTrue(Media.dataUrlFits("data:,"+ "A".repeat(((max * 4 + 2) / 3 + 4).toInt()), max))
    }

    @Test
    fun anOversizedInlineImageIsRefusedBeforeDecoding() = runTest {
        val b = Backend()
        val h = Harness(this, b)
        // One char over the base64 length of 25 MiB: refused by length, nothing decoded or fetched.
        val payload = (HermesApi.MEDIA_MAX_IMAGE_BYTES * 4 + 2) / 3 + 5
        val huge = "data:image/png;base64," + "A".repeat(payload.toInt())
        assertEquals(ImageResult.Unavailable, h.media.toolImage(vision(huge)))
        assertEquals(ImageResult.Unavailable, h.media.toolImage(vision(huge)))
        assertEquals(0, b.decoded)
        assertEquals(emptyList<String>(), b.media)

        // The server's data URL is held to the same rule.
        b.mediaResult = { Result.success(huge) }
        assertEquals(ImageResult.Unavailable, h.media.toolImage(vision("~/.hermes/images/huge.png")))
        assertEquals(ImageResult.Unavailable, h.media.toolImage(vision("~/.hermes/images/huge.png"))) // final: cached
        assertEquals(1, b.media.size)
        assertEquals(0, b.decoded)
    }

    @Test
    fun inlineImagesAreCachedByDigest() = runTest {
        val b = Backend()
        val h = Harness(this, b)
        assertEquals(ImageResult.Loaded(Bitmap), h.media.toolImage(vision("data:image/png;base64,AQID")))
        assertEquals(ImageResult.Loaded(Bitmap), h.media.toolImage(vision("data:image/png;base64,AQID")))
        assertEquals(1, b.decoded)
        assertEquals(ImageResult.Loaded(Bitmap), h.media.toolImage(vision("data:image/png;base64,BAU=")))
        assertEquals(2, b.decoded)
    }

    private companion object {
        fun unexpected(): ChatBackend =
            Proxy.newProxyInstance(ChatBackend::class.java.classLoader, arrayOf(ChatBackend::class.java)) { _, m, _ ->
                error("unexpected ChatBackend.${m.name}")
            } as ChatBackend
    }
}
