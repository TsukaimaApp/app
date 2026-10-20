package labs.suchattai.tsukaima.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaTest {
    @Test
    fun kindFromExtension() {
        assertEquals(MediaKind.Image, Media.kind("/a/b.PNG"))
        assertEquals(MediaKind.Video, Media.kind("/a/b.mp4"))
        assertEquals(MediaKind.Audio, Media.kind("/a/b.opus"))
        assertEquals(MediaKind.Document, Media.kind("/a/x.debug.txt"))
        assertEquals(MediaKind.Archive, Media.kind("/a/b.tar.gz"))
        assertEquals(MediaKind.Other, Media.kind("/a/Makefile"))
        assertTrue(Media.previewable("~/x.webp"))
        assertFalse(Media.previewable("/x.svg"))
    }

    @Test
    fun parentLabelKeepsTheLastTwoSegments() {
        assertEquals(
            "…/output/session-20261020_075645_44ae73",
            Media.parentLabel("/home/hermes/workspace/output/session-20261020_075645_44ae73/x.debug.txt"),
        )
        assertEquals("/tmp", Media.parentLabel("/tmp/a.txt"))
        assertEquals("/srv/out", Media.parentLabel("/srv/out/a.txt"))
        assertEquals("~/out", Media.parentLabel("~/out/a.txt"))
        assertEquals("…/b/c", Media.parentLabel("~/a/b/c/d.txt"))
        assertEquals("/", Media.parentLabel("/a.txt"))
        assertEquals("a.txt", Media.basename("/srv/out/a.txt"))
    }
}
