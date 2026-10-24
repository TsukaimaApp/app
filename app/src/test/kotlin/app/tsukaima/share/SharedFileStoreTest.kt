package app.tsukaima.share

import app.tsukaima.ui.chat.Attachments
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.io.InputStream

class SharedFileStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun copyKeepsNameMimeAndSizeAndSkipsOversize() {
        val root = File(tmp.root, "shared")
        val store = SharedFileStore(root)
        val f = store.copy("a.txt", "text/plain", 5) { "hello".byteInputStream() }
        assertNotNull(f)
        assertEquals("a.txt", f!!.name)
        assertEquals("text/plain", f.mime)
        assertEquals(5L, f.size)
        assertTrue(File(f.path).isFile)
        assertEquals("hello", File(f.path).readText())

        val before = root.walkTopDown().count()
        assertNull(store.copy("big.bin", null, Attachments.MAX_BYTES + 1L) { "x".byteInputStream() })
        assertEquals(before, root.walkTopDown().count())
    }

    @Test
    fun copyCapsWhileStreamingAndSanitisesName() {
        val root = File(tmp.root, "shared")
        val store = SharedFileStore(root)
        val endless = object : InputStream() {
            override fun read() = 1
            override fun read(b: ByteArray, off: Int, len: Int): Int { b.fill(1, off, off + len); return len }
        }
        assertNull(store.copy("x", null, null) { endless })
        assertEquals(0, root.walkTopDown().count { it.isFile })
        val f = store.copy("../../evil.txt", null, null) { "a".byteInputStream() }
        assertEquals("evil.txt", f?.name)
        assertEquals("attachment", store.copy("  ", null, null) { "a".byteInputStream() }?.name)
    }

    @Test
    fun pruneDeletesOldEntriesOnly() {
        val root = File(tmp.root, "shared")
        val h = 3_600_000L
        val t = 100 * h
        val old = SharedFileStore(root) { t - 25 * h }.copy("old.txt", null, 1) { "a".byteInputStream() }!!
        val fresh = SharedFileStore(root) { t }.copy("new.txt", null, 1) { "a".byteInputStream() }!!
        SharedFileStore(root) { t }.prune()
        assertFalse(File(old.path).exists())
        assertTrue(File(fresh.path).exists())
    }

    @Test
    fun copyWithUnreadableStreamReturnsNull() {
        val store = SharedFileStore(File(tmp.root, "shared"))
        assertNull(store.copy("a", null, null) { throw IOException("gone") })
        val bad = object : InputStream() { override fun read(): Int = throw IOException("boom") }
        assertNull(store.copy("a", null, null) { bad })
    }

    @Test
    fun deleteRemovesTheFile() {
        val store = SharedFileStore(File(tmp.root, "shared"))
        val f = store.copy("a", null, 1) { "a".byteInputStream() }!!
        store.delete(f)
        assertFalse(File(f.path).exists())
    }
}
