package app.tsukaima.ui.chat

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.location.LocationManagerCompat
import app.tsukaima.diagnostics.fileProviderAuthority
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.coroutines.resume

/**
 * Files the composer makes itself, a photo from the camera or a recorded clip, kept in `cache/captures` until they
 * are uploaded (an attachment is uploaded when it is added). Older ones are deleted when a new one is made.
 */
object Captures {
    private const val KEEP_MS = 24 * 60 * 60 * 1000L

    /** A new empty file `<prefix>-<time>.<ext>`; blocking (touches the disk). */
    fun newFile(context: Context, prefix: String, ext: String, now: Long = System.currentTimeMillis()): File {
        val dir = File(context.cacheDir, "captures").apply { mkdirs() }
        dir.listFiles()?.filter { now - it.lastModified() > KEEP_MS }?.forEach { it.delete() }
        return File(dir, "$prefix-$now.$ext")
    }

    /** The `content://` address the camera app writes [file] through. */
    fun uri(context: Context, file: File): Uri = FileProvider.getUriForFile(context, fileProviderAuthority(context), file)
}

/** Records one audio clip (AAC in an `.m4a` file) from the microphone. Needs `RECORD_AUDIO`. */
class AudioClipRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var file: File? = null

    /** Starts recording into a new capture file; false when the microphone could not be opened. */
    fun start(): Boolean {
        cancel()
        val target = Captures.newFile(context, "audio", "m4a")
        @Suppress("DEPRECATION")
        val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else MediaRecorder()
        return try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioEncodingBitRate(BIT_RATE)
            r.setAudioSamplingRate(SAMPLE_RATE)
            r.setOutputFile(target.path)
            r.prepare()
            r.start()
            recorder = r
            file = target
            true
        } catch (e: Exception) { // IOException, IllegalStateException, RuntimeException from the media server
            Log.w("Tsukaima", "audio clip: could not start", e)
            r.release()
            target.delete()
            false
        }
    }

    /** Ends the recording and returns its file; null when nothing usable was recorded (stopped at once). */
    fun stop(): File? {
        val r = recorder ?: return null
        val target = file
        recorder = null
        file = null
        return try {
            r.stop()
            target?.takeIf { it.length() > 0 }
        } catch (e: RuntimeException) { // stop() right after start(): no data
            target?.delete()
            null
        } finally {
            r.release()
        }
    }

    /** Ends the recording and deletes it. */
    fun cancel() {
        stop()?.delete()
    }

    val recording: Boolean get() = recorder != null

    private companion object {
        const val BIT_RATE = 64_000
        const val SAMPLE_RATE = 44_100
    }
}

/**
 * Where the phone is now: one fresh fix from the best provider that is switched on (within [timeoutMs]), else the
 * last position the system knows. Null when location is off or nothing is known. The caller holds a location
 * permission.
 */
@SuppressLint("MissingPermission")
suspend fun currentLocation(context: Context, timeoutMs: Long = 12_000): Location? {
    val manager = context.getSystemService(LocationManager::class.java) ?: return null
    if (!LocationManagerCompat.isLocationEnabled(manager)) return null
    val providers = listOf("fused", LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
        .filter { p -> runCatching { manager.isProviderEnabled(p) }.getOrDefault(false) }
    val fresh = providers.firstOrNull()?.let { provider ->
        withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<Location?> { cont ->
                val signal = CancellationSignal()
                cont.invokeOnCancellation { signal.cancel() }
                try {
                    LocationManagerCompat.getCurrentLocation(manager, provider, signal, ContextCompat.getMainExecutor(context)) { fix ->
                        if (cont.isActive) cont.resume(fix)
                    }
                } catch (e: RuntimeException) { // SecurityException (permission revoked meanwhile), unknown provider
                    if (cont.isActive) cont.resume(null)
                }
            }
        }
    }
    return fresh ?: providers.firstNotNullOfOrNull { p -> runCatching { manager.getLastKnownLocation(p) }.getOrNull() }
}
