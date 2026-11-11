package app.tsukaima.diagnostics

import android.content.Context
import android.util.Log
import java.io.File
import java.time.Instant

/**
 * What happened to the background connection, one line per event with its time: the service started, Android's
 * time limit ended it, it was destroyed, a start was refused, the app process began. Kept in `filesDir` across
 * process deaths (a "process started" with no "service destroyed" before it is a kill) and printed in the problem
 * report, so a day on a phone shows why notifications stopped. Never holds a message, a title or an address.
 */
object BackgroundLog {
    private const val FILE = "background.log"
    private const val KEPT = 200
    private const val TAG = "Tsukaima"
    private val lock = Any()

    fun note(ctx: Context, event: String) = note(ctx.filesDir, event, System.currentTimeMillis())

    internal fun note(dir: File, event: String, at: Long) {
        synchronized(lock) {
            try {
                val file = File(dir, FILE)
                val lines = (if (file.isFile) file.readLines() else emptyList()) + "${Instant.ofEpochMilli(at)} ${event.replace('\n', ' ')}"
                file.writeText(lines.takeLast(KEPT).joinToString("\n", postfix = "\n"))
            } catch (e: Exception) {
                Log.w(TAG, "background log: not written", e)
            }
        }
    }

    fun read(ctx: Context): String = read(ctx.filesDir)

    internal fun read(dir: File): String = synchronized(lock) {
        try {
            File(dir, FILE).takeIf { it.isFile }?.readText()?.trimEnd().orEmpty()
        } catch (e: Exception) {
            ""
        }
    }
}
