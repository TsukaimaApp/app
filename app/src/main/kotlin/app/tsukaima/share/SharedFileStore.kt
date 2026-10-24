package app.tsukaima.share

import app.tsukaima.ui.chat.Attachments
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.UUID

/** Cached copies of shared streams under [root] (`cacheDir/shared`), one `<uuid>/<name>` per file. */
class SharedFileStore(private val root: File, private val now: () -> Long = System::currentTimeMillis) {
    /**
     * Copies one stream into a fresh `<uuid>/<name>` under root. Returns null (nothing written) when size >
     * MAX_BYTES, the stream outgrows it, or open() throws.
     */
    fun copy(name: String, mime: String?, size: Long?, open: () -> InputStream): SharedFile? {
        if (Attachments.tooLarge(size)) return null
        val safe = File(name).name.takeIf { it.isNotBlank() && it != ".." } ?: "attachment"
        val dir = File(root, UUID.randomUUID().toString())
        val out = File(dir, safe)
        try {
            if (!dir.mkdirs()) throw IOException("cannot create $dir")
            open().use { input ->
                out.outputStream().use { sink ->
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > Attachments.MAX_BYTES) throw IOException("too large")
                        sink.write(buf, 0, n)
                    }
                }
            }
            dir.setLastModified(now())
            return SharedFile(out.path, safe, mime, out.length())
        } catch (e: Exception) {
            dir.deleteRecursively()
            return null
        }
    }

    /** Deletes entries older than 24 h. */
    fun prune() {
        val cutoff = now() - MAX_AGE_MS
        root.listFiles()?.forEach { if (it.lastModified() < cutoff) it.deleteRecursively() }
    }

    fun delete(file: SharedFile) {
        File(file.path).parentFile?.takeIf { it.parentFile == root }?.deleteRecursively()
    }

    private companion object {
        const val MAX_AGE_MS = 24 * 3_600_000L
    }
}
