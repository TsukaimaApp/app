package app.tsukaima.diagnostics

import app.tsukaima.data.prefs.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DiagnosticsTest {
    @get:Rule
    val tempDir = TemporaryFolder()

    private val device = DeviceInfo(
        versionName = "0.8.0",
        versionCode = 18,
        androidRelease = "15",
        sdk = 35,
        manufacturer = "Google",
        model = "Pixel 8",
    )

    private val clock = { 1_760_000_000_000L }

    @Test
    fun reportHeaderNeverCarriesCredentials() {
        val settings = Settings(
            serverUrl = "https://mario:hunter2-secret@hermes.example.net:9119/api?token=tok-abc123#frag",
            username = "mario-the-user",
        )
        val report = Diagnostics.buildReport(settings, device, crash = null, pid = 42, logcat = { "" }, clock = clock)

        assertTrue(report, report.contains("hermes.example.net"))
        listOf("mario", "hunter2", "secret", "tok-abc123", "token", "9119/api", "frag").forEach {
            assertFalse("report leaks \"$it\":\n$report", report.contains(it))
        }
        assertTrue(report.contains("0.8.0 (18)"))
        assertTrue(report.contains("Android 15 (SDK 35)"))
        assertTrue(report.contains("Google Pixel 8"))
    }

    @Test
    fun serverHostIsTheHostOnly() {
        assertEquals("100.69.91.116", Diagnostics.serverHost("http://100.69.91.116:9119"))
        assertEquals("hermes.example.net", Diagnostics.serverHost("https://u:p@hermes.example.net/x?token=1"))
        assertEquals("hermes", Diagnostics.serverHost("hermes:9119")) // no scheme
        assertNull(Diagnostics.serverHost(""))
        assertNull(Diagnostics.serverHost("   "))
    }

    @Test
    fun noSettingsSaysNotSignedIn() {
        val report = Diagnostics.buildReport(null, device, crash = null, pid = 42, logcat = { "" }, clock = clock)
        assertTrue(report, report.contains("Server: not signed in"))
    }

    @Test
    fun crashRecordRoundTrips() {
        val dir = tempDir.newFolder("files")
        assertNull(Diagnostics.readCrash(dir))
        val error = IllegalStateException("boom")
        val record = CrashRecord.of(error, thread = "main", device = device, at = clock())
        Diagnostics.writeCrash(dir, record)

        val read = Diagnostics.readCrash(dir)
        assertEquals(record, read)
        assertTrue(read!!.stack.contains("IllegalStateException: boom"))
        assertTrue(read.stack.contains("DiagnosticsTest"))

        Diagnostics.clearCrash(dir)
        assertNull(Diagnostics.readCrash(dir))
    }

    @Test
    fun uncaughtHandlerRecordsTheCrashAndChains() {
        val dir = tempDir.newFolder("files")
        var chained: Throwable? = null
        val handler = Diagnostics.handler(
            previous = { _, e -> chained = e },
            filesDir = dir,
            device = device,
            clock = clock,
        )
        val error = RuntimeException("kaput")
        handler.uncaughtException(Thread.currentThread(), error)

        assertEquals(error, chained)
        val record = Diagnostics.readCrash(dir)
        assertNotNull(record)
        record!!
        assertEquals(Thread.currentThread().name, record.thread)
        assertEquals("0.8.0 (18)", record.appVersion)
        assertTrue(record.stack.contains("RuntimeException: kaput"))
    }

    @Test
    fun aGarbledCrashFileReadsAsNoCrash() {
        val dir = tempDir.newFolder("files")
        java.io.File(dir, Diagnostics.CRASH_FILE).writeText("not a crash record")
        assertNull(Diagnostics.readCrash(dir))
    }

    @Test
    fun throwingLogcatStillYieldsHeaderAndCrash() {
        val crash = CrashRecord.of(IllegalArgumentException("bad"), thread = "worker-1", device = device, at = clock())
        val report = Diagnostics.buildReport(
            Settings(serverUrl = "http://hermes:9119"),
            device,
            crash = crash,
            pid = 42,
            logcat = { throw java.io.IOException("no logcat here") },
            clock = clock,
        )
        assertTrue(report, report.contains("Tsukaima 0.8.0 (18)"))
        assertTrue(report, report.contains("Server: hermes"))
        assertTrue(report, report.contains("IllegalArgumentException: bad"))
        assertTrue(report, report.contains("worker-1"))
        assertTrue(report, report.contains("Logcat unavailable: IOException"))
    }

    @Test
    fun reportOrderIsHeaderCrashLogcat() {
        val crash = CrashRecord.of(IllegalStateException("x"), thread = "main", device = device, at = clock())
        var askedPid = -1
        val report = Diagnostics.buildReport(null, device, crash, pid = 4242, logcat = { askedPid = it; "LOGCAT-LINE" }, clock = clock)
        assertEquals(4242, askedPid)
        val header = report.indexOf("Tsukaima 0.8.0")
        val crashAt = report.indexOf("IllegalStateException: x")
        val logAt = report.indexOf("LOGCAT-LINE")
        assertTrue(report, header in 0 until crashAt && crashAt < logAt)
    }
}
