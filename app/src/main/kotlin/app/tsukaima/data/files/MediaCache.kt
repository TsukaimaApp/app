package app.tsukaima.data.files

import app.tsukaima.data.api.FileDownload
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okio.buffer
import okio.sink
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * Audio and video the agent delivered, kept in [dir] while they are played. A file is named by the hash of its
 * server path plus its extension (the players pick a decoder by it). A copy younger than [FRESH_MS] is reused, an
 * older one fetched again (the agent may have rewritten the file). The folder is held to [MAX_BYTES], oldest out
 * first. One fetch at a time.
 */
class MediaCache(private val dir: File, private val io: CoroutineDispatcher = Dispatchers.IO, private val now: () -> Long = System::currentTimeMillis) {
    private val lock = Mutex()

    suspend fun file(path: String, download: suspend () -> Result<FileDownload>): Result<File> = withContext(io) {
        lock.withLock {
            try {
                dir.mkdirs()
                val target = File(dir, name(path))
                if (target.isFile && target.length() > 0 && now() - target.lastModified() < FRESH_MS) return@withLock Result.success(target)
                val fetched = download().getOrElse { return@withLock Result.failure(it) }
                val temp = File(dir, target.name + ".part")
                fetched.use { d ->
                    if ((d.size ?: 0) > MAX_BYTES) throw IOException("The file is too large to play here")
                    temp.sink().buffer().use { out -> out.writeAll(d.body) }
                }
                target.delete()
                if (!temp.renameTo(target)) throw IOException("Could not keep the file")
                target.setLastModified(now())
                prune(keep = target)
                Result.success(target)
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    suspend fun clear() = withContext(io) { lock.withLock { dir.deleteRecursively() }; Unit }

    private fun prune(keep: File) {
        val files = dir.listFiles().orEmpty().sortedByDescending { it.lastModified() }
        var total = 0L
        for (f in files) {
            total += f.length()
            if (total > MAX_BYTES && f != keep) f.delete()
        }
    }

    companion object {
        const val FRESH_MS = 60 * 60 * 1000L
        const val MAX_BYTES = 300L * 1024 * 1024

        /** `<hash of the path>.<its extension>`: nothing of the server's path reaches the file system. */
        internal fun name(path: String): String {
            val hash = MessageDigest.getInstance("SHA-256").digest(path.toByteArray()).take(16).joinToString("") { "%02x".format(it) }
            val ext = path.substringAfterLast('/').substringAfterLast('.', "").lowercase().filter { it.isLetterOrDigit() }.take(5)
            return if (ext.isEmpty()) hash else "$hash.$ext"
        }
    }
}
