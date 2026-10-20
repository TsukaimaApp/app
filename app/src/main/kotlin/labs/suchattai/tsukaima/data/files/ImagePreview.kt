package labs.suchattai.tsukaima.data.files

import android.graphics.Bitmap
import android.graphics.BitmapFactory

/** Downsampled decoding for inline image previews. */
object ImagePreview {
    /** About 2 MP: plenty for a chat-width preview, ~8 MB as ARGB. */
    const val MAX_PIXELS = 2_000_000L

    /** The power-of-two `inSampleSize` that brings [width]×[height] down to at most [maxPixels]. */
    fun sampleSize(width: Int, height: Int, maxPixels: Long = MAX_PIXELS): Int {
        var sample = 1
        while ((width.toLong() / sample) * (height.toLong() / sample) > maxPixels) sample *= 2
        return sample
    }

    /** [bytes] decoded at most [MAX_PIXELS]; null when they are not an image Android can decode. */
    fun decode(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight) }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }
}
