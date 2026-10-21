package app.tsukaima.data.files

import android.graphics.Bitmap
import android.graphics.BitmapFactory

/** Downsampled decoding for inline image previews. */
object ImagePreview {
    /** About 2 MP: plenty for a chat-width preview, ~8 MB as ARGB. */
    const val MAX_PIXELS = 2_000_000L

    /**
     * The longest side a tool image keeps: a 16000×120 strip would pass [MAX_PIXELS] but still be a 16000 px texture.
     * Reply and `MEDIA:` previews are held to [MAX_PIXELS] alone (a tall reply image stays sharp).
     */
    const val MAX_SIDE = 2048

    /**
     * The power-of-two `inSampleSize` that brings [width]×[height] down to at most [maxPixels] and, when [maxSide] is
     * given, its longer side to at most [maxSide].
     */
    fun sampleSize(width: Int, height: Int, maxSide: Int? = null, maxPixels: Long = MAX_PIXELS): Int {
        var sample = 1
        while (
            (width.toLong() / sample) * (height.toLong() / sample) > maxPixels ||
            (maxSide != null && maxOf(width, height) / sample > maxSide)
        ) sample *= 2
        return sample
    }

    /**
     * [bytes] decoded at most [MAX_PIXELS] (and at most [maxSide] on the long side, when given); null when they are not
     * an image Android can decode.
     */
    fun decode(bytes: ByteArray, maxSide: Int? = null): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxSide) }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }
}
