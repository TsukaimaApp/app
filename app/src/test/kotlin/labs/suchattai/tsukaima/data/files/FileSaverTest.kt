package labs.suchattai.tsukaima.data.files

import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream
import kotlin.random.Random

class FileSaverTest {
    @Test
    fun copyStreamsEveryByte() {
        val bytes = Random(1).nextBytes(200_000) // several 64 KB chunks
        val out = ByteArrayOutputStream()
        assertEquals(200_000L, MediaStoreFileSaver.copy(Buffer().write(bytes), out))
        assertArrayEquals(bytes, out.toByteArray())
    }

    @Test
    fun previewSampleSizeKeepsUnderTwoMegapixels() {
        assertEquals(1, ImagePreview.sampleSize(1920, 1040))
        assertEquals(2, ImagePreview.sampleSize(2000, 1500))
        assertEquals(4, ImagePreview.sampleSize(4032, 3024))
        assertEquals(8, ImagePreview.sampleSize(8000, 6000))
        assertEquals(1, ImagePreview.sampleSize(1, 1))
    }
}
