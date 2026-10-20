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
import labs.suchattai.tsukaima.data.api.ImageOrigin
import labs.suchattai.tsukaima.data.api.ImageTooLargeException
import labs.suchattai.tsukaima.data.api.userMessage
import java.io.IOException
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

    /** The parent directory, shortened to its last two segments: `…/output/session-x`, `~/out`, `/tmp`. */
    fun parentLabel(path: String): String {
        val parent = path.trimEnd('/').substringBeforeLast('/', "")
        if (parent.isEmpty()) return if (path.startsWith("/")) "/" else ""
        val segments = parent.split('/').filter { it.isNotEmpty() }
        return if (segments.size <= 2) parent else "…/" + segments.takeLast(2).joinToString("/")
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
    private val imageLoading = mutableMapOf<String, Deferred<ImageBitmap?>>()

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
        if (images.containsKey(url)) return images[url]
        return imageLoading.getOrPut(url) { scope.async { fetchImage(url) } }.await()
    }

    private suspend fun fetchImage(url: String): ImageBitmap? {
        try {
            val bytes = backend.fetchImage(url).getOrThrow()
            val bitmap = withContext(io) { backend.decodePreview(bytes) }
            images[url] = bitmap
            return bitmap
        } catch (c: CancellationException) {
            throw c
        } catch (e: OutOfMemoryError) {
            images[url] = null
            return null
        } catch (e: Exception) {
            if (!transientImageFailure(e)) images[url] = null
            return null
        } finally {
            imageLoading.remove(url)
        }
    }

    /** A failure worth retrying: a dropped connection or timeout, or a 5xx / 429 answer (busy, not a verdict). */
    private fun transientImageFailure(e: Exception): Boolean = when (e) {
        is ApiException -> e.code >= 500 || e.code == 429
        is ImageTooLargeException -> false
        else -> e is IOException
    }

    private fun set(path: String, value: MediaState?) = state.update {
        it.copy(mediaState = if (value == null) it.mediaState - path else it.mediaState + (path to value))
    }

    private companion object {
        const val PREVIEW_CACHE = 8
        const val IMAGE_CACHE = 8

        /** How long a card shows "Saved" before it is back to idle. */
        const val SAVED_MS = 3_000L
        val BUSY = setOf(MediaStatus.Downloading, MediaStatus.Saved)
    }
}
