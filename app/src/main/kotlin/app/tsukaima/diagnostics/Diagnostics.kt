package app.tsukaima.diagnostics

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Process
import app.tsukaima.BuildConfig
import app.tsukaima.data.prefs.Settings
import java.io.File
import java.net.URI
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** What a problem report and a crash record say about the app and the phone; never anything about the user. */
data class DeviceInfo(
    val versionName: String,
    val versionCode: Int,
    val androidRelease: String,
    val sdk: Int,
    val manufacturer: String,
    val model: String,
) {
    /** "0.8.0 (18)". */
    val appVersion: String get() = "$versionName ($versionCode)"

    /** "Android 15 (SDK 35)". */
    val android: String get() = "Android $androidRelease (SDK $sdk)"

    /** "Google Pixel 8"; the manufacturer is not repeated when the model already starts with it. */
    val device: String
        get() = if (model.startsWith(manufacturer, ignoreCase = true)) model else "$manufacturer $model".trim()

    companion object {
        /** This build on this phone. */
        fun current(versionName: String = BuildConfig.VERSION_NAME, versionCode: Int = BuildConfig.VERSION_CODE) = DeviceInfo(
            versionName = versionName,
            versionCode = versionCode,
            androidRelease = Build.VERSION.RELEASE.orEmpty(),
            sdk = Build.VERSION.SDK_INT,
            manufacturer = Build.MANUFACTURER.orEmpty(),
            model = Build.MODEL.orEmpty(),
        )
    }
}

/** The last uncaught exception, as recorded by [Diagnostics.install] for the next launch. */
data class CrashRecord(
    /** Epoch millis. */
    val at: Long,
    val thread: String,
    val appVersion: String,
    val android: String,
    val device: String,
    /** The full stack trace, causes included. */
    val stack: String,
) {
    companion object {
        /** A record of [error] on [thread], now ([at]) on [device]. */
        fun of(error: Throwable, thread: String, device: DeviceInfo, at: Long) = CrashRecord(
            at = at,
            thread = thread,
            appVersion = device.appVersion,
            android = device.android,
            device = device.device,
            stack = error.stackTraceToString().take(MAX_STACK_CHARS),
        )

        private const val MAX_STACK_CHARS = 64 * 1024
    }
}

/**
 * Problem reporting: an uncaught-exception handler that records the crash in `files/crash-latest.txt` for the next
 * launch, and [buildReport], the text the user shares from Settings → Support. A report carries the app and Android
 * versions, the phone model, the server's host (never its credentials, path or query, nor the username), the last
 * crash, and this process's own recent logcat.
 *
 * The Android entry points take a [Context]; the pure overloads take the files dir, the logcat runner and the clock so
 * `DiagnosticsTest` runs on the JVM.
 */
object Diagnostics {
    /** The crash record's file name in the app's files dir. */
    const val CRASH_FILE = "crash-latest.txt"

    /** Lines of logcat a report carries. */
    const val LOGCAT_LINES = 600

    private const val MAGIC = "tsukaima-crash 1"
    private const val LOGCAT_TIMEOUT_S = 3L

    @Volatile
    private var installed = false

    /**
     * Installs the crash recorder as the default uncaught-exception handler, chained to the previous one (which still
     * shows the system's crash dialog and kills the process). Call first thing in `Application.onCreate`; idempotent.
     */
    fun install(app: Application) {
        if (installed) return
        installed = true
        Thread.setDefaultUncaughtExceptionHandler(
            handler(Thread.getDefaultUncaughtExceptionHandler(), app.filesDir, DeviceInfo.current(), System::currentTimeMillis),
        )
    }

    /** The handler [install] sets: records the crash in [filesDir] (best effort), then hands it to [previous]. */
    internal fun handler(
        previous: Thread.UncaughtExceptionHandler?,
        filesDir: File,
        device: DeviceInfo,
        clock: () -> Long,
    ) = Thread.UncaughtExceptionHandler { thread, error ->
        try {
            writeCrash(filesDir, CrashRecord.of(error, thread.name, device, clock()))
        } catch (_: Throwable) {
            // Recording is best effort: never mask the original crash.
        }
        previous?.uncaughtException(thread, error)
    }

    /** The crash recorded on an earlier run, or null. Blocking file I/O: call off the main thread. */
    fun lastCrash(ctx: Context): CrashRecord? = readCrash(ctx.filesDir)

    /** Forgets the recorded crash (the Settings banner was dismissed or reported). Blocking file I/O. */
    fun clearCrash(ctx: Context) = clearCrash(ctx.filesDir)

    /**
     * The problem report for this process: header, the last crash, then logcat (`--pid` of this process only).
     * Blocking (runs `logcat`, up to 3 s): call off the main thread.
     */
    fun buildReport(ctx: Context, settings: Settings?, versionName: String, versionCode: Int): String = buildReport(
        settings = settings,
        device = DeviceInfo.current(versionName, versionCode),
        crash = lastCrash(ctx),
        pid = Process.myPid(),
        logcat = ::runLogcat,
        clock = System::currentTimeMillis,
    )

    /** [buildReport] with every input explicit. A [logcat] that throws leaves a note in place of the log. */
    internal fun buildReport(
        settings: Settings?,
        device: DeviceInfo,
        crash: CrashRecord?,
        pid: Int,
        logcat: (Int) -> String,
        clock: () -> Long,
    ): String = buildString {
        appendLine("Tsukaima problem report")
        appendLine("Generated: ${Instant.ofEpochMilli(clock())}")
        appendLine()
        appendLine("App: Tsukaima ${device.appVersion}")
        appendLine("Android: ${device.android}")
        appendLine("Device: ${device.device}")
        val url = settings?.serverUrl.orEmpty()
        appendLine("Server: ${if (url.isBlank()) "not signed in" else serverHost(url) ?: "unknown host"}")
        appendLine()
        appendLine("== Last crash ==")
        if (crash == null) {
            appendLine("None recorded")
        } else {
            appendLine("At: ${Instant.ofEpochMilli(crash.at)}")
            appendLine("Thread: ${crash.thread}")
            appendLine("App: ${crash.appVersion}")
            appendLine("Android: ${crash.android}")
            appendLine("Device: ${crash.device}")
            appendLine()
            appendLine(crash.stack.trimEnd())
        }
        appendLine()
        appendLine("== Logcat (this process, last $LOGCAT_LINES lines) ==")
        val log = try {
            logcat(pid).trimEnd().ifEmpty { "(empty)" }
        } catch (e: Throwable) {
            "Logcat unavailable: ${e::class.java.simpleName}${e.message?.let { ": $it" }.orEmpty()}"
        }
        appendLine(log)
    }

    /**
     * The host of a server URL ("http://u:p@hermes.example.net:9119/x" → "hermes.example.net"): never the userinfo,
     * port, path or query. Null when [url] is blank or has no host.
     */
    fun serverHost(url: String): String? {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return null
        val withScheme = if ("://" in trimmed) trimmed else "http://$trimmed"
        return try {
            URI(withScheme).host?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    /** Writes [record] to [filesDir]/[CRASH_FILE], replacing any earlier one. */
    internal fun writeCrash(filesDir: File, record: CrashRecord) {
        fun oneLine(s: String) = s.replace('\n', ' ').replace('\r', ' ')
        val text = buildString {
            append(MAGIC).append('\n')
            append("at=").append(record.at).append('\n')
            append("thread=").append(oneLine(record.thread)).append('\n')
            append("app=").append(oneLine(record.appVersion)).append('\n')
            append("android=").append(oneLine(record.android)).append('\n')
            append("device=").append(oneLine(record.device)).append('\n')
            append('\n')
            append(record.stack)
        }
        filesDir.mkdirs()
        val tmp = File(filesDir, "$CRASH_FILE.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(File(filesDir, CRASH_FILE))) {
            File(filesDir, CRASH_FILE).writeText(text)
            tmp.delete()
        }
    }

    /** The record in [filesDir], or null when there is none or it cannot be read. */
    internal fun readCrash(filesDir: File): CrashRecord? {
        val file = File(filesDir, CRASH_FILE)
        val text = try {
            if (!file.isFile) return null
            file.readText()
        } catch (_: Exception) {
            return null
        }
        if (!text.startsWith("$MAGIC\n")) return null
        val split = text.indexOf("\n\n")
        if (split < 0) return null
        val fields = text.substring(MAGIC.length + 1, split).lines()
            .mapNotNull { line -> line.indexOf('=').takeIf { it > 0 }?.let { line.substring(0, it) to line.substring(it + 1) } }
            .toMap()
        return CrashRecord(
            at = fields["at"]?.toLongOrNull() ?: return null,
            thread = fields["thread"].orEmpty(),
            appVersion = fields["app"].orEmpty(),
            android = fields["android"].orEmpty(),
            device = fields["device"].orEmpty(),
            stack = text.substring(split + 2),
        )
    }

    /** Deletes the record in [filesDir], if any. */
    internal fun clearCrash(filesDir: File) {
        File(filesDir, CRASH_FILE).delete()
    }

    /** `logcat -d -v time --pid=<pid> -t 600`, killed after 3 s; stderr is folded into the output. */
    private fun runLogcat(pid: Int): String {
        val process = ProcessBuilder("logcat", "-d", "-v", "time", "--pid=$pid", "-t", "$LOGCAT_LINES")
            .redirectErrorStream(true)
            .start()
        val out = StringBuffer()
        // Drain while waiting: a full pipe would otherwise block logcat until the timeout.
        val reader = thread(isDaemon = true, name = "tsukaima-logcat") {
            try {
                process.inputStream.bufferedReader().use { r -> r.lineSequence().forEach { out.append(it).append('\n') } }
            } catch (_: Exception) {
                // the process was killed
            }
        }
        if (!process.waitFor(LOGCAT_TIMEOUT_S, TimeUnit.SECONDS)) process.destroy()
        reader.join(500)
        return out.toString()
    }
}
