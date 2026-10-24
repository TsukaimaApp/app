package app.tsukaima.ui.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import app.tsukaima.data.gateway.GatewaySession
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.Base64

/** [Pdf] pages are rendered by the server and queued like images; [File] lands in the workspace. */
enum class AttachmentKind { Image, File, Pdf }
enum class AttachmentStatus { Uploading, Ready, Failed }

/**
 * A picked file on its way into the session. Images are queued server-side for the next turn ([serverPath] is
 * what `image.detach` takes, in the live session [liveSessionId] that holds the queue); a PDF's [pages] are
 * queued the same way, one image per page ([pagePaths]), with the server's [note] (e.g. the page cap); files land
 * in the workspace and the prompt carries their [refText] (`@file:…`).
 */
data class PendingAttachment(
    val localId: String,
    val name: String,
    val kind: AttachmentKind,
    val sizeBytes: Long,
    val status: AttachmentStatus,
    val serverPath: String? = null,
    val refText: String? = null,
    val liveSessionId: String? = null,
    val pages: Int = 0,
    val note: String? = null,
    val pagePaths: List<String> = emptyList(),
) {
    /** Queued on the server for the next turn (image or PDF pages), so tied to [liveSessionId]. */
    val queued: Boolean get() = kind == AttachmentKind.Image || kind == AttachmentKind.Pdf
}

/** Name, MIME type and size (null when unknown) of a picked file. */
data class FileInfo(val name: String, val mime: String?, val size: Long?)

/** A document the user picked; both calls block and run on the IO dispatcher. */
interface PickedFile {
    fun describe(): FileInfo
    fun open(): InputStream
}

/** Pure rules for attachments. */
object Attachments {
    /**
     * 12 MB, not 16: the bytes travel base64-encoded inside one WebSocket text frame, and OkHttp refuses (and
     * closes the socket on) a frame that would push its send queue past 16 MiB; 12 MB → 16.0 MB of base64.
     */
    const val MAX_BYTES = 12_000_000
    const val TOO_LARGE = "File too large (max 12 MB)"

    /** Image types `image.attach_bytes` accepts (Hermes `_IMAGE_EXTENSIONS`), by MIME type → extension. */
    private val IMAGE_TYPES = mapOf(
        "image/png" to "png", "image/jpeg" to "jpg", "image/gif" to "gif", "image/webp" to "webp", "image/bmp" to "bmp",
    )
    private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp")

    /** Hermes `_PDF_ATTACH_MAX_PAGES`: `pdf.attach` renders at most this many pages per call. */
    const val PDF_MAX_PAGES = 25

    fun tooLarge(size: Long?): Boolean = size != null && size > MAX_BYTES

    /** "820 B", "12 KB", "1.4 MB" (decimal units, like the 12 MB cap). */
    fun humanSize(bytes: Long): String = when {
        bytes < 1_000 -> "$bytes B"
        bytes < 1_000_000 -> "${(bytes + 500) / 1_000} KB"
        else -> String.format(java.util.Locale.ROOT, "%.1f MB", bytes / 1_000_000.0)
    }

    /**
     * Images Hermes can queue for the model; PDFs (by MIME type, or a `.pdf` [name] when the type is missing or
     * generic) are rendered to page images; everything else (HEIC included) is uploaded as a file.
     */
    fun kindFor(mime: String?, name: String): AttachmentKind {
        val type = mime?.lowercase()?.takeUnless { it.isBlank() || it == "application/octet-stream" }
        return when {
            type in IMAGE_TYPES -> AttachmentKind.Image
            type == "application/pdf" || type == "application/x-pdf" -> AttachmentKind.Pdf
            type == null && name.lowercase().endsWith(".pdf") -> AttachmentKind.Pdf
            else -> AttachmentKind.File
        }
    }

    /** "1 page", "3 pages". */
    fun pageCount(pages: Int): String = if (pages == 1) "1 page" else "$pages pages"

    private val STOCK_PDF_TEXT = Regex("""^\[User attached PDF: .*\(\d+ page\(s\)\)]$""")

    /**
     * The chip's second line for a `pdf.attach` result, or null: at the [PDF_MAX_PAGES] cap the server silently
     * drops any further pages (it does not report the total), so say how many went; otherwise the server's [text] when it is more than its stock
     * `[User attached PDF: name (N page(s))]` line.
     */
    fun pdfPageCapNote(pagesAttached: Int, text: String?): String? {
        if (pagesAttached >= PDF_MAX_PAGES) return "First $PDF_MAX_PAGES pages attached"
        val t = text?.trim().orEmpty()
        return t.takeIf { it.isNotEmpty() && !STOCK_PDF_TEXT.matches(it) }
    }

    /** [name] with an extension Hermes accepts: the server picks the image type from the filename suffix. */
    fun imageFilename(name: String, mime: String?): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext in IMAGE_EXTENSIONS) return name
        return "$name.${IMAGE_TYPES[mime?.lowercase()] ?: "png"}"
    }

    fun dataUrl(mime: String?, base64: String): String = "data:${mime ?: "application/octet-stream"};base64,$base64"

    /**
     * The prompt text: file refs joined by "\n", a blank line, then the typed text (Hermes desktop
     * `buildContextText`). With no text and no file, a stock line for the queued image(s) or PDF(s); PDFs add no
     * ref (their pages ride as queued images).
     */
    fun promptText(text: String, sent: List<PendingAttachment>): String {
        val refs = sent.mapNotNull { it.refText?.takeIf(String::isNotBlank) }.joinToString("\n")
        val joined = listOf(refs, text).filter { it.isNotBlank() }.joinToString("\n\n")
        if (joined.isNotEmpty()) return joined
        val images = sent.count { it.kind == AttachmentKind.Image }
        val pdfs = sent.count { it.kind == AttachmentKind.Pdf }
        return when {
            images > 0 && pdfs > 0 -> "See the attachments."
            pdfs > 1 -> "See the attached PDFs."
            pdfs == 1 -> "See the attached PDF."
            images > 1 -> "See the attached images."
            else -> "See the attached image."
        }
    }

    /** Reads at most [MAX_BYTES]; null when the stream holds more. */
    fun readCapped(input: InputStream): ByteArray? {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) return out.toByteArray()
            if (out.size() + n > MAX_BYTES) return null
            out.write(buf, 0, n)
        }
    }
}

/**
 * The composer's attachments for one [ChatViewModel]: upload on pick, retry, remove, and hand-off to send.
 * Driven from the VM's single-threaded [scope]; blocking reads and base64 run on [io]. The picked file is kept
 * for a retry (re-read, never held in memory) until the attachment is sent or removed, then handed to [onReleased]
 * (also for one never attached: unreadable or too large).
 */
internal class ChatAttachments(
    private val session: GatewaySession,
    private val backend: ChatBackend,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val state: MutableStateFlow<ChatUiState>,
    /** A picked file the composer is done with (e.g. to delete a shared file's cached copy). */
    private val onReleased: (PickedFile) -> Unit = {},
    /** The live session id, creating or resuming the session first if needed; null if that failed. */
    private val liveSession: suspend () -> String?,
) {
    private var counter = 0L
    private val sources = mutableMapOf<String, Pair<PickedFile, FileInfo>>()

    /**
     * One upload at a time: each one is a single WebSocket frame of up to ~16 MB, and OkHttp closes the socket
     * when queued frames exceed 16 MiB, so two in flight on a slow uplink would drop the connection. Also bounds
     * peak heap to one file's bytes plus its base64.
     */
    private val uploads = Mutex()

    fun attach(files: List<PickedFile>) {
        scope.launch {
            for (file in files) {
                val info = try {
                    withContext(io) { file.describe() }
                } catch (c: CancellationException) {
                    throw c
                } catch (e: Exception) {
                    backend.toast("Couldn’t read the file: ${e.message ?: "unknown error"}")
                    onReleased(file)
                    continue
                }
                if (Attachments.tooLarge(info.size)) {
                    backend.toast(Attachments.TOO_LARGE)
                    onReleased(file)
                    continue
                }
                val id = "att-${++counter}"
                sources[id] = file to info
                val chip = PendingAttachment(id, info.name, Attachments.kindFor(info.mime, info.name), info.size ?: 0, AttachmentStatus.Uploading)
                state.update { it.copy(attachments = it.attachments + chip) }
                upload(id)
            }
        }
    }

    fun retry(localId: String) {
        val chip = find(localId) ?: return
        if (chip.status != AttachmentStatus.Failed) return
        set(localId) { it.copy(status = AttachmentStatus.Uploading) }
        upload(localId)
    }

    /**
     * Images and PDF pages are detached from the next turn; files stay in the workspace, so they are only dropped
     * here.
     */
    fun remove(localId: String) {
        val chip = find(localId) ?: return
        drop(localId)
        // One still uploading is detached when its upload lands (see [upload]).
        if (chip.queued && chip.status == AttachmentStatus.Ready) detach(chip)
    }

    /**
     * A resume that came back with a different live session id left queued images (and PDF pages) behind in the
     * old one: mark them failed so a retry re-queues them where the next turn will run. Files stay valid
     * (workspace paths).
     */
    fun onLiveSession(live: String) {
        state.update { s ->
            s.copy(
                attachments = s.attachments.map {
                    if (it.queued && it.status == AttachmentStatus.Ready && it.liveSessionId != live) {
                        it.copy(status = AttachmentStatus.Failed, serverPath = null, liveSessionId = null, pagePaths = emptyList())
                    } else {
                        it
                    }
                },
            )
        }
    }

    /** Detaches [chip]'s queued image, or every page of a PDF. */
    private fun detach(chip: PendingAttachment) {
        val live = chip.liveSessionId ?: return
        val paths = if (chip.kind == AttachmentKind.Pdf) chip.pagePaths else listOfNotNull(chip.serverPath)
        if (paths.isEmpty()) return
        scope.launch {
            // Every page is attempted: one that stays queued would ride along with the next turn.
            var error: Exception? = null
            var failed = 0
            for (path in paths) {
                try {
                    session.detachImage(live, path)
                } catch (c: CancellationException) {
                    throw c
                } catch (e: Exception) {
                    failed++
                    error = error ?: e
                }
            }
            when {
                failed == 0 -> Unit
                chip.kind == AttachmentKind.Pdf -> backend.toast("Couldn’t remove ${Attachments.pageCount(failed)} of ${chip.name}")
                else -> backend.toast("Couldn’t remove ${chip.name}: ${error?.message ?: "gateway error"}")
            }
        }
    }

    /** Takes the ready attachments out of the composer for a send; [restore] puts them back if it fails. */
    fun takeReady(): List<PendingAttachment> {
        val ready = state.value.attachments.filter { it.status == AttachmentStatus.Ready }
        if (ready.isEmpty()) return ready
        val ids = ready.map { it.localId }.toSet()
        state.update { s -> s.copy(attachments = s.attachments.filterNot { it.localId in ids }) }
        return ready
    }

    fun restore(sent: List<PendingAttachment>) {
        if (sent.isNotEmpty()) state.update { it.copy(attachments = sent + it.attachments) }
    }

    /** The send went through: forget the sources of [sent]. */
    fun sent(sent: List<PendingAttachment>) = sent.forEach { release(it.localId) }

    private fun upload(localId: String) {
        val (file, info) = sources[localId] ?: return
        scope.launch {
            uploads.withLock {
                val chip = find(localId) ?: return@launch
                uploadLocked(localId, chip, file, info)
            }
        }
    }

    private suspend fun uploadLocked(localId: String, chip: PendingAttachment, file: PickedFile, info: FileInfo) {
        try {
            val bytes = withContext(io) { file.open().use(Attachments::readCapped) }
            if (bytes == null) {
                drop(localId)
                return backend.toast(Attachments.TOO_LARGE)
            }
            val base64 = withContext(io) { Base64.getEncoder().encodeToString(bytes) }
            val live = liveSession() ?: throw IOException("no chat session yet")
            val done = when (chip.kind) {
                AttachmentKind.Image -> {
                    val r = session.attachImage(live, base64, Attachments.imageFilename(info.name, info.mime))
                    if (!r.attached) throw IOException(r.message ?: "not attached")
                    chip.copy(status = AttachmentStatus.Ready, serverPath = r.path, liveSessionId = live)
                }
                AttachmentKind.Pdf -> {
                    val r = session.attachPdf(live, base64, info.name)
                    if (!r.attached || r.pagePaths.isEmpty()) throw IOException("no pages attached")
                    chip.copy(
                        status = AttachmentStatus.Ready, liveSessionId = live, pagePaths = r.pagePaths,
                        pages = r.pagesAttached.takeIf { it > 0 } ?: r.pagePaths.size,
                        note = Attachments.pdfPageCapNote(r.pagesAttached, r.text),
                    )
                }
                AttachmentKind.File -> {
                    val r = session.attachFile(live, Attachments.dataUrl(info.mime, base64), info.name)
                    if (!r.attached || r.refText.isBlank()) throw IOException("not attached")
                    chip.copy(status = AttachmentStatus.Ready, serverPath = r.path, refText = r.refText)
                }
            }
            if (find(localId) == null) {
                // Removed while uploading: an image (or PDF page) would otherwise ride along with the next turn.
                if (done.queued) detach(done)
            } else {
                set(localId) { done }
            }
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            if (find(localId) != null) {
                set(localId) { it.copy(status = AttachmentStatus.Failed) }
                backend.toast("Couldn’t attach ${chip.name}: ${e.message ?: "gateway error"}")
            }
        }
    }

    private fun release(localId: String) {
        sources.remove(localId)?.let { (file, _) -> onReleased(file) }
    }

    private fun drop(localId: String) {
        release(localId)
        state.update { s -> s.copy(attachments = s.attachments.filterNot { it.localId == localId }) }
    }

    private fun find(localId: String) = state.value.attachments.firstOrNull { it.localId == localId }

    private fun set(localId: String, block: (PendingAttachment) -> PendingAttachment) =
        state.update { s -> s.copy(attachments = s.attachments.map { if (it.localId == localId) block(it) else it }) }
}
