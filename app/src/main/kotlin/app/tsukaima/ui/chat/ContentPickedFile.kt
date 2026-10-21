package app.tsukaima.ui.chat

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import java.io.FileNotFoundException
import java.io.InputStream

/** A document from the system picker, read through [resolver] (callers run both methods on IO). */
class ContentPickedFile(private val resolver: ContentResolver, private val uri: Uri) : PickedFile {
    override fun describe(): FileInfo {
        var name: String? = null
        var size: Long? = null
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val nameCol = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeCol = c.getColumnIndex(OpenableColumns.SIZE)
                if (nameCol >= 0 && !c.isNull(nameCol)) name = c.getString(nameCol)
                if (sizeCol >= 0 && !c.isNull(sizeCol)) size = c.getLong(sizeCol)
            }
        }
        val fallback = uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "attachment"
        return FileInfo(name?.takeIf { it.isNotBlank() } ?: fallback, resolver.getType(uri), size)
    }

    override fun open(): InputStream = resolver.openInputStream(uri) ?: throw FileNotFoundException("cannot open $uri")
}
