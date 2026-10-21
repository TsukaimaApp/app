package app.tsukaima.diagnostics

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** The [FileProvider] authority for shared files (`AndroidManifest.xml`, `res/xml/file_paths.xml`). */
fun fileProviderAuthority(ctx: Context): String = "${ctx.packageName}.files"

/**
 * Shares a problem report: writes [text] to `cache/reports/tsukaima-report-<ts>.txt` (off the main thread; earlier
 * reports are deleted) and opens the share sheet with it as a `text/plain` attachment through the [FileProvider].
 */
suspend fun shareReport(ctx: Context, text: String) {
    val file = withContext(Dispatchers.IO) { writeReport(ctx.cacheDir, text, System.currentTimeMillis()) }
    val uri = FileProvider.getUriForFile(ctx, fileProviderAuthority(ctx), file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, "Tsukaima report")
        clipData = ClipData.newRawUri(file.name, uri) // carries the read grant through the chooser
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val chooser = Intent.createChooser(send, "Report a problem")
    if (ctx !is Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    ctx.startActivity(chooser)
}

/** Writes [text] to `<cacheDir>/reports/tsukaima-report-<at>.txt`, removing earlier reports first. Blocking. */
internal fun writeReport(cacheDir: File, text: String, at: Long): File {
    val dir = File(cacheDir, "reports").apply { mkdirs() }
    dir.listFiles()?.forEach { it.delete() }
    return File(dir, "tsukaima-report-$at.txt").apply { writeText(text) }
}
