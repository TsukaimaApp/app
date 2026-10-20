package labs.suchattai.tsukaima.ui.chat

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import labs.suchattai.tsukaima.data.api.HermesJson
import labs.suchattai.tsukaima.data.gateway.GatewaySession
import labs.suchattai.tsukaima.data.gateway.RpcException
import labs.suchattai.tsukaima.data.gateway.RpcTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy
import java.util.Base64

/** PDFs go through `pdf.attach`: the pages are queued as images, so the chip carries page paths and no text ref. */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatAttachmentsTest {
    private val live = "d4fb0dea"

    private class Transport : RpcTransport {
        val calls = mutableListOf<Pair<String, JsonObject>>()
        val results = mutableMapOf<String, JsonObject>()
        val errors = mutableMapOf<String, RpcException>()
        var gate: CompletableDeferred<Unit>? = null

        fun last(method: String) = calls.last { it.first == method }.second
        fun count(method: String) = calls.count { it.first == method }

        override suspend fun request(method: String, params: JsonObject): JsonObject {
            calls += method to params
            if (method == "pdf.attach") gate?.await()
            errors.remove(method)?.let { throw it }
            return results[method] ?: JsonObject(emptyMap())
        }

        override fun respond(id: String, result: JsonObject) = true
        override fun respondError(id: String, code: Int, message: String) = true
    }

    private class File(val name: String, val mime: String?, val bytes: ByteArray) : PickedFile {
        override fun describe() = FileInfo(name, mime, bytes.size.toLong())
        override fun open() = java.io.ByteArrayInputStream(bytes)
    }

    private val pdfBytes = "%PDF-1.7\n1 0 obj\n".toByteArray()
    private val pdf = File("report.pdf", "application/pdf", pdfBytes)

    private fun obj(s: String): JsonObject = HermesJson.parseToJsonElement(s).jsonObject

    private fun pdfResult(pages: Int) = obj(
        """{"attached":true,"filename":"report.pdf","pages_attached":$pages,
           "pages":[${(1..pages).joinToString(",") { """{"path":"/h/images/pdf_p$it.png","page":$it,"width":1275,"height":1650}""" }}],
           "count":$pages,"text":"[User attached PDF: report.pdf ($pages page(s))]"}""",
    )

    private class Harness(scope: TestScope, live: String) {
        val transport = Transport()
        val toasts = mutableListOf<String>()
        val state = MutableStateFlow(ChatUiState())
        private val backend = Proxy.newProxyInstance(ChatBackend::class.java.classLoader, arrayOf(ChatBackend::class.java)) { _, m, args ->
            if (m.name == "toast") toasts += args[0] as String else error("unexpected ChatBackend.${m.name}")
        } as ChatBackend
        val dispatcher = StandardTestDispatcher(scope.testScheduler)
        val attachments = ChatAttachments(GatewaySession(transport), backend, scope, dispatcher, state) { live }
        val chips get() = state.value.attachments
    }

    @Test
    fun pdfIsRoutedToPdfAttach() = runTest {
        val h = Harness(this, live)
        h.transport.results["pdf.attach"] = pdfResult(3)

        h.attachments.attach(listOf(pdf))
        advanceUntilIdle()

        val params = h.transport.last("pdf.attach")
        assertEquals(setOf("session_id", "content_base64", "filename"), params.keys)
        assertEquals(live, params["session_id"]!!.jsonPrimitive.content)
        assertEquals(Base64.getEncoder().encodeToString(pdfBytes), params["content_base64"]!!.jsonPrimitive.content)
        assertEquals("report.pdf", params["filename"]!!.jsonPrimitive.content)
        assertEquals(0, h.transport.count("file.attach") + h.transport.count("image.attach_bytes"))

        val chip = h.chips.single()
        assertEquals(AttachmentKind.Pdf, chip.kind)
        assertEquals(AttachmentStatus.Ready, chip.status)
        assertEquals(3, chip.pages)
        assertNull("a stock server line is not a note", chip.note)
        assertEquals((1..3).map { "/h/images/pdf_p$it.png" }, chip.pagePaths)
        assertEquals(live, chip.liveSessionId)
        assertNull("the pages ride as queued images", chip.refText)
        assertTrue(chip.queued)
    }

    @Test
    fun pdfKindFromMimeOrName() {
        assertEquals(AttachmentKind.Pdf, Attachments.kindFor("application/pdf", "x"))
        assertEquals(AttachmentKind.Pdf, Attachments.kindFor(null, "Scan.PDF"))
        assertEquals(AttachmentKind.Pdf, Attachments.kindFor("application/octet-stream", "scan.pdf"))
        assertEquals(AttachmentKind.File, Attachments.kindFor("text/plain", "notes.pdf"))
        assertEquals(AttachmentKind.File, Attachments.kindFor(null, "notes.txt"))
        assertEquals(AttachmentKind.Image, Attachments.kindFor("image/png", "shot.png"))
    }

    @Test
    fun pdfPageCapNote() {
        val stock = "[User attached PDF: report.pdf (25 page(s))]"
        assertEquals("Only the first 25 pages", Attachments.pdfPageCapNote(Attachments.PDF_MAX_PAGES, stock))
        assertNull(Attachments.pdfPageCapNote(3, "[User attached PDF: report.pdf (3 page(s))]"))
        assertNull(Attachments.pdfPageCapNote(3, null))
        assertNull(Attachments.pdfPageCapNote(3, " "))
        assertEquals("Pages 2–4 only", Attachments.pdfPageCapNote(3, "Pages 2–4 only"))
        assertEquals("1 page", Attachments.pageCount(1))
        assertEquals("3 pages", Attachments.pageCount(3))
    }

    @Test
    fun pdfAtThePageCapCarriesANote() = runTest {
        val h = Harness(this, live)
        h.transport.results["pdf.attach"] = pdfResult(Attachments.PDF_MAX_PAGES)
        h.attachments.attach(listOf(pdf))
        advanceUntilIdle()
        assertEquals("Only the first 25 pages", h.chips.single().note)
    }

    @Test
    fun removingAPdfDetachesEveryPage() = runTest {
        val h = Harness(this, live)
        h.transport.results["pdf.attach"] = pdfResult(3)
        h.attachments.attach(listOf(pdf))
        advanceUntilIdle()

        h.attachments.remove(h.chips.single().localId)
        advanceUntilIdle()
        val detached = h.transport.calls.filter { it.first == "image.detach" }.map { it.second }
        assertEquals((1..3).map { "/h/images/pdf_p$it.png" }, detached.map { it["path"]!!.jsonPrimitive.content })
        assertTrue(detached.all { it["session_id"]!!.jsonPrimitive.content == live })
        assertTrue(h.chips.isEmpty())
    }

    @Test
    fun promptTextAddsNoRefForAPdf() {
        val chip = PendingAttachment("a1", "report.pdf", AttachmentKind.Pdf, 9, AttachmentStatus.Ready, pages = 2, pagePaths = listOf("/a", "/b"))
        val notes = PendingAttachment("a2", "notes.txt", AttachmentKind.File, 9, AttachmentStatus.Ready, refText = "@file:notes.txt")
        val image = PendingAttachment("a3", "shot.png", AttachmentKind.Image, 9, AttachmentStatus.Ready)
        assertEquals("hi", Attachments.promptText("hi", listOf(chip)))
        assertEquals("@file:notes.txt\n\nhi", Attachments.promptText("hi", listOf(chip, notes)))
        assertEquals("See the attached PDF.", Attachments.promptText("", listOf(chip)))
        assertEquals("See the attached PDFs.", Attachments.promptText("", listOf(chip, chip.copy(localId = "a4"))))
        assertEquals("See the attachments.", Attachments.promptText("", listOf(chip, image)))
        assertEquals("See the attached image.", Attachments.promptText("", listOf(image)))
    }

    @Test
    fun serverErrorMarksThePdfFailedAndRetryReuploads() = runTest {
        val h = Harness(this, live)
        h.transport.errors["pdf.attach"] = RpcException(5028, "pdftoppm produced no pages (corrupt PDF?)")
        h.attachments.attach(listOf(pdf))
        advanceUntilIdle()
        assertEquals(AttachmentStatus.Failed, h.chips.single().status)
        assertEquals(1, h.toasts.size)
        assertTrue(h.toasts.single(), h.toasts.single().startsWith("Couldn’t attach report.pdf"))

        h.transport.results["pdf.attach"] = pdfResult(2)
        h.attachments.retry(h.chips.single().localId)
        advanceUntilIdle()
        assertEquals(AttachmentStatus.Ready, h.chips.single().status)
        assertEquals(2, h.chips.single().pages)
        assertEquals(2, h.transport.count("pdf.attach"))
    }

    @Test
    fun oversizedPdfRejectedWithTheFileCap() = runTest {
        val h = Harness(this, live)
        h.attachments.attach(listOf(File("big.pdf", "application/pdf", ByteArray(Attachments.MAX_BYTES + 1))))
        advanceUntilIdle()
        assertEquals(listOf(Attachments.TOO_LARGE), h.toasts)
        assertTrue(h.chips.isEmpty())
        assertEquals(0, h.transport.count("pdf.attach"))
    }

    @Test
    fun newLiveSessionFailsAQueuedPdf() = runTest {
        val h = Harness(this, live)
        h.transport.results["pdf.attach"] = pdfResult(2)
        h.attachments.attach(listOf(pdf))
        advanceUntilIdle()

        h.attachments.onLiveSession("beef0001")
        val chip = h.chips.single()
        assertEquals(AttachmentStatus.Failed, chip.status)
        assertTrue(chip.pagePaths.isEmpty())
        assertNull(chip.liveSessionId)
    }

    @Test
    fun removedWhileUploadingDetachesThePages() = runTest {
        val h = Harness(this, live)
        h.transport.results["pdf.attach"] = pdfResult(2)
        h.transport.gate = CompletableDeferred()
        h.attachments.attach(listOf(pdf))
        advanceUntilIdle()
        assertEquals(1, h.transport.count("pdf.attach"))
        h.attachments.remove(h.chips.single().localId)
        h.transport.gate!!.complete(Unit)
        advanceUntilIdle()
        assertTrue(h.chips.isEmpty())
        assertEquals(listOf("/h/images/pdf_p1.png", "/h/images/pdf_p2.png"),
            h.transport.calls.filter { it.first == "image.detach" }.map { it.second["path"]!!.jsonPrimitive.content })
        assertFalse(h.transport.calls.any { it.first == "file.attach" })
    }
}
