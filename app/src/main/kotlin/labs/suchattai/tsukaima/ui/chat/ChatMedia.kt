package labs.suchattai.tsukaima.ui.chat

import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import labs.suchattai.tsukaima.data.api.ApiException
import labs.suchattai.tsukaima.data.api.AuthException
import labs.suchattai.tsukaima.data.api.HermesApi
import labs.suchattai.tsukaima.data.api.ImageOrigin
import labs.suchattai.tsukaima.data.api.ImageTooLargeException
import labs.suchattai.tsukaima.data.api.userMessage
import labs.suchattai.tsukaima.domain.chat.ChatItem
import labs.suchattai.tsukaima.domain.voice.DataUrl
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

enum class MediaKind { Image, Video, Audio, Document, Archive, Other }
enum class MediaStatus { Downloading, Saved, Failed }

/** A `MEDIA:` card's download state; no entry means idle. [error] is the one-line reason of a failure. */
data class MediaState(val status: MediaStatus, val error: String? = null)

/** Pure rules for the files the agent delivers with `MEDIA:` lines. */
object Media {
    /** Previews fetch at most this much; a bigger image shows the card only. */
    const val PREVIEW_MAX_BYTES = 10_000_000L

    /** Image types the inline preview decodes. */
    private val PREVIEWABLE = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp")
    private val KINDS = mapOf(
        MediaKind.Image to setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "tiff", "svg", "heic"),
        MediaKind.Video to setOf("mp4", "mov", "avi", "mkv", "webm", "3gp"),
        MediaKind.Audio to setOf("mp3", "m2a", "wav", "ogg", "opus", "m4a", "flac"),
        MediaKind.Document to setOf(
            "pdf", "docx", "doc", "odt", "rtf", "txt", "md", "epub", "xlsx", "xls", "ods", "csv", "tsv", "json", "xml",
            "yaml", "yml", "kmz", "kml", "geojson", "gpx", "pptx", "ppt", "odp", "key", "html", "htm", "log",
        ),
        MediaKind.Archive to setOf("zip", "tar", "gz", "tgz", "bz2", "xz", "7z", "rar", "apk", "ipa"),
    )

    fun extension(path: String): String = basename(path).substringAfterLast('.', "").lowercase()

    fun kind(path: String): MediaKind = extension(path).let { ext -> KINDS.entries.firstOrNull { ext in it.value }?.key } ?: MediaKind.Other

    fun previewable(path: String): Boolean = extension(path) in PREVIEWABLE

    fun basename(path: String): String = path.trimEnd('/').substringAfterLast('/')

    /**
     * Whether a `data:` URL's payload (after the first comma) is short enough to decode to at most [maxBytes]: base64
     * is 4 chars per 3 bytes, plus 4 of slack for padding. Length only, so an oversized one is refused before it is
     * copied or decoded; a URL without a comma passes (the decoder rejects it).
     */
    fun dataUrlFits(dataUrl: String, maxBytes: Long): Boolean {
        val comma = dataUrl.indexOf(',')
        if (comma < 0) return true
        return dataUrl.length - comma - 1 <= (maxBytes * 4 + 2) / 3 + 4
    }

    /** The parent directory, shortened to its last two segments: `…/output/session-x`, `~/out`, `/tmp`. */
    fun parentLabel(path: String): String {
        val parent = path.trimEnd('/').substringBeforeLast('/', "")
        if (parent.isEmpty()) return if (path.startsWith("/")) "/" else ""
        val segments = parent.split('/').filter { it.isNotEmpty() }
        return if (segments.size <= 2) parent else "…/" + segments.takeLast(2).joinToString("/")
    }
}

/** A decoded image, or why there is none: [Unavailable] is final (cached), [Retry] is worth another try. */
sealed interface ImageResult {
    data class Loaded(val bitmap: ImageBitmap) : ImageResult
    data object Unavailable : ImageResult
    data object Retry : ImageResult
}

/** Where a `vision_analyze` image ([ChatItem.ToolCall.imageUrl]) comes from. */
sealed interface ToolImageSource {
    /** An http(s) URL: a reply image's rules (same origin loads, foreign waits for a tap). */
    data class Web(val url: String) : ToolImageSource

    /** A path on the server (absolute or `~/…`), fetched through `/api/media`. */
    data class Local(val path: String) : ToolImageSource

    /** An inline `data:` URL. */
    data class Inline(val dataUrl: String) : ToolImageSource

    companion object {
        /**
         * The source of a raw `image_url`: http(s) and `data:` as such; `file://` (stripped), an absolute or a `~`
         * path is on the server; anything else (another scheme, a relative path, blank) is null.
         */
        fun of(raw: String): ToolImageSource? {
            // Classified by prefix in place: an inline image can be megabytes, and this runs on the main thread.
            var start = 0
            while (start < raw.length && raw[start].isWhitespace()) start++
            fun has(prefix: String, at: Int = start) = raw.regionMatches(at, prefix, 0, prefix.length, ignoreCase = true)
            fun value(from: Int = start): String {
                var end = raw.length
                while (end > from && raw[end - 1].isWhitespace()) end--
                return if (from == 0 && end == raw.length) raw else raw.substring(from, end)
            }
            val pathAt = if (has(FILE_SCHEME)) start + FILE_SCHEME.length else start
            return when {
                has("http://") || has("https://") -> Web(value())
                has("data:") -> Inline(value())
                has("/", pathAt) || has("~/", pathAt) -> Local(value(pathAt))
                else -> null
            }
        }

        private const val FILE_SCHEME = "file://"
    }
}

/**
 * Downloads and previews of the chat's `MEDIA:` files for one [ChatViewModel]. A tap downloads the file into
 * Downloads ([ChatBackend.fileSaver]) and offers it to a viewer; one download per path at a time. Image previews
 * are decoded once and kept in a small LRU, so scrolling a card back into view does not refetch it. Images in
 * replies (`![alt](url)`, [imagePreview]) have their own LRU keyed by URL. Driven from the VM's single-threaded
 * [scope]; the body is read, saved and decoded on [io].
 */
internal class ChatMedia(
    private val backend: ChatBackend,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val state: MutableStateFlow<ChatUiState>,
) {
    private val previews = object : LinkedHashMap<String, ImageBitmap?>(PREVIEW_CACHE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap?>) = size > PREVIEW_CACHE
    }
    private val loading = mutableMapOf<String, Deferred<ImageBitmap?>>()
    private val images = object : LinkedHashMap<String, ImageBitmap?>(IMAGE_CACHE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap?>) = size > IMAGE_CACHE
    }
    private val imageLoading = mutableMapOf<String, Deferred<ImageResult>>()

    /** Downloads [path] into Downloads and opens it; a re-tap while it downloads or just saved is ignored, a failed one retries. */
    fun open(path: String) {
        if (state.value.mediaState[path]?.status in BUSY) return
        set(path, MediaState(MediaStatus.Downloading))
        scope.launch {
            try {
                val download = backend.download(path).getOrThrow()
                val mime = download.mime
                // `use` first: a cancellation at the dispatcher hop would otherwise discard the download unclosed.
                val uri = download.use { d -> withContext(io) { backend.fileSaver.save(d.name, d.mime, d.body) } }
                set(path, MediaState(MediaStatus.Saved))
                backend.toast("Saved to Downloads")
                backend.openFile(uri, mime)
                delay(SAVED_MS)
                if (state.value.mediaState[path]?.status == MediaStatus.Saved) set(path, null)
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                val reason = e.userMessage() ?: "download failed"
                set(path, MediaState(MediaStatus.Failed, reason))
                backend.toast("Couldn’t download ${Media.basename(path)}: $reason")
            }
        }
    }

    /**
     * The decoded preview of image [path], or null when there is none (not an image, over [Media.PREVIEW_MAX_BYTES],
     * undecodable, or the fetch failed). Concurrent callers share one fetch; outcomes are cached, except a network
     * failure, which a later call retries.
     */
    suspend fun preview(path: String): ImageBitmap? {
        if (!Media.previewable(path)) return null
        if (previews.containsKey(path)) return previews[path]
        return loading.getOrPut(path) { scope.async { fetchPreview(path) } }.await()
    }

    private suspend fun fetchPreview(path: String): ImageBitmap? {
        try {
            val download = backend.download(path).getOrThrow()
            val bitmap = download.use { d ->
                withContext(io) {
                    val tooBig = (d.size ?: 0) > Media.PREVIEW_MAX_BYTES || d.body.request(Media.PREVIEW_MAX_BYTES + 1)
                    if (tooBig) null else backend.decodePreview(d.body.readByteArray())
                }
            }
            previews[path] = bitmap
            return bitmap
        } catch (c: CancellationException) {
            throw c
        } catch (e: OutOfMemoryError) {
            // A 10 MB read plus its decode on a tight heap: give up on this preview rather than the app.
            previews[path] = null
            return null
        } catch (e: Exception) {
            // The server's answer is final (gone, refused, too large); a dropped connection is worth another try.
            if (e is ApiException || e !is IOException) previews[path] = null
            return null
        } finally {
            loading.remove(path)
        }
    }

    /** Foreign-origin image URLs the user tapped to load (this chat's lifetime). */
    private val allowedImages: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * Whether the image at [url] loads by itself: it is on the server's own origin, or the user tapped to load it.
     * A foreign image waits for a tap: fetching it unasked would let a prompt-injected reply leak data in its URL.
     */
    fun imageAllowed(url: String): Boolean =
        url in allowedImages || ImageOrigin.sameOrigin(url, backend.settings.value?.serverUrl.orEmpty())

    /** The user tapped to load the foreign image at [url]; it loads from now on (see [imageAllowed]). */
    fun allowImage(url: String) {
        allowedImages += url
    }

    /**
     * The decoded image at [url] (a reply's `![alt](url)`), or null when it is not [imageAllowed] or failed.
     * Concurrent callers share one fetch. Final failures are cached (the server's answer, over the 8 MB cap, not
     * http(s), undecodable), so a broken image is not refetched on every recomposition or scroll; a transient
     * one (connection, timeout, a 5xx or 429 answer) is not, and a later call retries.
     */
    suspend fun imagePreview(url: String): ImageBitmap? {
        if (!imageAllowed(url)) return null
        return (cachedImage(url) { backend.fetchImage(url).getOrThrow() } as? ImageResult.Loaded)?.bitmap
    }

    /**
     * The image a `vision_analyze` row looked at ([ChatItem.ToolCall.imageUrl]), decoded. http(s) follows the
     * reply-image rules ([imagePreview]: a foreign one waits for [allowImage]); a path on the server comes through
     * `/api/media` and an inline `data:` URL decodes directly, either at most [HermesApi.MEDIA_MAX_IMAGE_BYTES]; any
     * other scheme has no image. Shares [imagePreview]'s cache (`media:<path>` for a server path, `media:sha256:<hex>`
     * for an inline one) and its failure rules: the server's refusal (403/404/413/415), a malformed or oversized image
     * are [ImageResult.Unavailable] (cached), a dropped connection is [ImageResult.Retry] (not cached). Never touches
     * [tool].
     */
    suspend fun toolImage(tool: ChatItem.ToolCall): ImageResult =
        when (val source = tool.imageUrl?.let(ToolImageSource::of)) {
            null -> ImageResult.Unavailable
            is ToolImageSource.Web ->
                if (!imageAllowed(source.url)) ImageResult.Unavailable
                else cachedImage(source.url) { backend.fetchImage(source.url).getOrThrow() }
            is ToolImageSource.Local -> cachedImage(MEDIA_KEY + source.path) {
                val dataUrl = backend.media(source.path).getOrThrow()
                withContext(io) { dataUrlBytes(dataUrl) }
            }
            is ToolImageSource.Inline ->
                // Over the cap by its length alone (O(1), the same verdict every time): neither hashed nor decoded.
                if (!Media.dataUrlFits(source.dataUrl, HermesApi.MEDIA_MAX_IMAGE_BYTES)) ImageResult.Unavailable
                else {
                    // The URL itself may be megabytes: keyed by its digest, computed off the caller's thread.
                    val key = withContext(io) { MEDIA_KEY + "sha256:" + sha256(source.dataUrl) }
                    cachedImage(key) { withContext(io) { dataUrlBytes(source.dataUrl) } }
                }
        }

    /**
     * The image bytes of a `data:` URL: a payload too long for [HermesApi.MEDIA_MAX_IMAGE_BYTES] → too large, before
     * anything is copied or decoded; not an image or not base64 → [IllegalArgumentException]; decoded over the cap →
     * too large.
     */
    private fun dataUrlBytes(dataUrl: String): ByteArray {
        if (!Media.dataUrlFits(dataUrl, HermesApi.MEDIA_MAX_IMAGE_BYTES)) throw ImageTooLargeException(TOO_LARGE)
        val (mime, bytes) = DataUrl.decode(dataUrl)
        require(mime.startsWith("image/")) { "not an image" }
        if (bytes.size > HermesApi.MEDIA_MAX_IMAGE_BYTES) throw ImageTooLargeException(TOO_LARGE)
        return bytes
    }

    /** The image under [key] from the LRU, else [load]ed and decoded once (concurrent callers share it). */
    private suspend fun cachedImage(key: String, load: suspend () -> ByteArray): ImageResult {
        if (images.containsKey(key)) return images[key]?.let { ImageResult.Loaded(it) } ?: ImageResult.Unavailable
        return imageLoading.getOrPut(key) { scope.async { fetchImage(key, load) } }.await()
    }

    private suspend fun fetchImage(key: String, load: suspend () -> ByteArray): ImageResult {
        try {
            val bytes = load()
            val bitmap = withContext(io) { backend.decodePreview(bytes) }
            images[key] = bitmap
            return bitmap?.let { ImageResult.Loaded(it) } ?: ImageResult.Unavailable
        } catch (c: CancellationException) {
            throw c
        } catch (e: OutOfMemoryError) {
            images[key] = null
            return ImageResult.Unavailable
        } catch (e: Exception) {
            if (transientImageFailure(e)) return ImageResult.Retry
            images[key] = null
            return ImageResult.Unavailable
        } finally {
            imageLoading.remove(key)
        }
    }

    /**
     * A failure worth retrying: a dropped connection or timeout, or a 5xx / 429 answer (busy, not a verdict). An
     * [AuthException] (an [IOException]) is final: retrying it on every recomposition would only repeat it.
     */
    private fun transientImageFailure(e: Exception): Boolean = when (e) {
        is ApiException -> e.code >= 500 || e.code == 429
        is ImageTooLargeException, is AuthException -> false
        else -> e is IOException
    }

    private fun set(path: String, value: MediaState?) = state.update {
        it.copy(mediaState = if (value == null) it.mediaState - path else it.mediaState + (path to value))
    }

    private companion object {
        const val PREVIEW_CACHE = 8
        const val IMAGE_CACHE = 8

        /** Cache-key prefix of a [toolImage] that is not an http(s) URL. */
        const val MEDIA_KEY = "media:"
        const val TOO_LARGE = "Image over 25 MiB"

        /** Hex SHA-256 of an ASCII (base64 `data:`) [text], fed in slices rather than one full copy. */
        fun sha256(text: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
            var i = 0
            while (i < text.length) {
                val end = minOf(text.length, i + DIGEST_SLICE)
                digest.update(text.substring(i, end).toByteArray(Charsets.ISO_8859_1))
                i = end
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        const val DIGEST_SLICE = 64 * 1024

        /** How long a card shows "Saved" before it is back to idle. */
        const val SAVED_MS = 3_000L
        val BUSY = setOf(MediaStatus.Downloading, MediaStatus.Saved)
    }
}
