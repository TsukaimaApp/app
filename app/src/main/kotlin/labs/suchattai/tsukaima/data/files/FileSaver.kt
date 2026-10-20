package labs.suchattai.tsukaima.data.files

import android.content.ActivityNotFoundException
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.provider.MediaStore
import androidx.core.net.toUri
import okio.BufferedSource
import java.io.IOException
import java.io.OutputStream

/** Puts a downloaded file where the user finds it. Blocking: callers run it on the IO dispatcher. */
interface FileSaver {
    /** Writes [source] as [name] ([mime]) and returns a content uri a viewer can open. Throws [IOException]. */
    fun save(name: String, mime: String, source: BufferedSource): String
}

/**
 * The public Downloads collection through [MediaStore.Downloads] (API 29+, no storage permission). The row
 * stays pending while it is written and is deleted if the copy fails; MediaStore de-duplicates the name.
 */
class MediaStoreFileSaver(private val resolver: ContentResolver) : FileSaver {
    override fun save(name: String, mime: String, source: BufferedSource): String {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Downloads is not available")
        try {
            val out = resolver.openOutputStream(uri) ?: throw IOException("cannot write to Downloads")
            out.use { copy(source, it) }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
        return uri.toString()
    }

    companion object {
        /** Copies [source] into [out] through a 64 KB buffer; returns the byte count. */
        fun copy(source: BufferedSource, out: OutputStream): Long {
            val buf = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val n = source.read(buf)
                if (n < 0) return total
                out.write(buf, 0, n)
                total += n
            }
        }
    }
}

/** Opens [uri] ([mime]) in whatever app handles it; false when none does. */
fun viewFile(context: Context, uri: String, mime: String): Boolean {
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri.toUri(), mime)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    return try {
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}
