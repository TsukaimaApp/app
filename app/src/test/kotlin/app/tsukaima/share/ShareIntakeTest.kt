package app.tsukaima.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareIntakeTest {
    private fun file(name: String, mime: String) = SharedFile("/c/$name", name, mime, 1)

    @Test
    fun textOnlySendIsAccepted() {
        val r = ShareIntake.parse(ShareIntake.ACTION_SEND, "text/plain", "hi", emptyList())
        assertEquals(ShareRequest("hi", emptyList()), r)
    }

    @Test
    fun imageStreamWithoutTextIsAccepted() {
        val r = ShareIntake.parse(ShareIntake.ACTION_SEND, "image/png", null, listOf("content://a/1"))
        assertEquals(ShareRequest("", listOf("content://a/1")), r)
    }

    @Test
    fun sendMultiplePdfsIsAccepted() {
        val u = listOf("content://a/1", "content://a/2")
        val r = ShareIntake.parse(ShareIntake.ACTION_SEND_MULTIPLE, "application/pdf", null, u)
        assertEquals(listOf("content://a/1", "content://a/2"), r?.streams)
    }

    @Test
    fun blankTextAndNoStreamIsRejected() {
        assertNull(ShareIntake.parse(ShareIntake.ACTION_SEND, "text/plain", "   ", emptyList()))
    }

    @Test
    fun unknownActionIsRejected() {
        assertNull(ShareIntake.parse("android.intent.action.VIEW", "text/plain", "x", emptyList()))
    }

    @Test
    fun summaryNamesLinksImagesFilesAndTruncates() {
        assertEquals("Link", ShareIntake.summary("https://x.y/z", emptyList()))
        val long = ShareIntake.summary("a".repeat(80), emptyList())
        assertTrue(long.endsWith("…"))
        assertEquals(61, long.length)
        assertEquals("3 images", ShareIntake.summary("", List(3) { file("$it.png", "image/png") }))
        assertEquals("report.pdf", ShareIntake.summary("", listOf(file("report.pdf", "application/pdf"))))
        val mixed = listOf(file("a.png", "image/png"), file("r.pdf", "application/pdf"))
        assertEquals("2 files", ShareIntake.summary("", mixed))
    }
}
