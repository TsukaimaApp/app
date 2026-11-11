package app.tsukaima.widget

import app.tsukaima.data.api.FileDownload
import app.tsukaima.data.api.HermesJson
import app.tsukaima.data.files.MediaCache
import app.tsukaima.data.gateway.Frame
import app.tsukaima.domain.chat.LocationText
import app.tsukaima.security.AppLock
import app.tsukaima.ui.chat.ChatActions
import app.tsukaima.ui.chat.ChatUiState
import app.tsukaima.ui.chat.attachItems
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

/** 0.15: the pure parts of the widget, the app lock, the attach sheet and the media cache. */
@OptIn(ExperimentalCoroutinesApi::class)
class BeyondChatTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun ev(type: String, payload: String = "{}", session: String = "live") =
        Frame.Event(type, session, 1, HermesJson.parseToJsonElement(payload).jsonObject)

    @Test
    fun theWidgetFollowsATurnFromStartToReply() {
        val titles = mapOf("live" to "Backup cron")
        var s = WidgetState(connected = true)
        assertEquals("Hermes awaits", s.line)
        s = WidgetState.onEvent(s, ev("message.start"), titles)
        assertEquals(WidgetStatus.Working, s.status)
        assertEquals("Backup cron", s.title)
        assertEquals("Hermes is working…", s.line)
        s = WidgetState.onEvent(s, ev("tool.start", """{"name":"terminal","context":"journalctl -u backup"}"""), titles)
        assertEquals("terminal · journalctl -u backup", s.detail)
        s = WidgetState.onApproval(s, "Backup cron", "sudo systemctl restart backup.timer")
        assertEquals("Waiting for your approval", s.line)
        assertEquals("sudo systemctl restart backup.timer", s.detail)
        s = WidgetState.onApprovalClosed(s)
        assertEquals(WidgetStatus.Working, s.status)
        s = WidgetState.onEvent(s, ev("message.complete", """{"text":"\n\nMoving the backup to 04:30 clears it.\nSecond line."}"""), titles)
        assertEquals(WidgetStatus.Idle, s.status)
        assertEquals("Moving the backup to 04:30 clears it.", s.detail)
        // An event that says nothing about a turn changes nothing; a long line is cut.
        assertEquals(s, WidgetState.onEvent(s, ev("session.usage"), titles))
        val long = WidgetState.onEvent(s, ev("message.complete", """{"text":"${"x".repeat(400)}"}"""), titles)
        assertEquals(WidgetState.MAX_DETAIL, long.detail?.length)
        assertEquals("Not connected", s.copy(connected = false).line)
    }

    @Test
    fun theAppLocksAfterTheSetWaitAndOpensOnProof() {
        var now = 1_000_000L
        val lock = AppLock { now }
        assertTrue("a fresh process is locked", lock.locked.value)
        lock.unlocked()
        lock.onStop()
        now += 30_000
        lock.onStart(afterSeconds = 60)
        assertFalse(lock.locked.value)
        lock.onStop()
        now += 60_000
        lock.onStart(afterSeconds = 60)
        assertTrue(lock.locked.value)
        lock.unlocked()
        lock.onStart(afterSeconds = 0) // no stop before it (a rotation): nothing to judge
        assertFalse(lock.locked.value)
        assertTrue(AppLock.shouldLock(leftAt = 10, now = 10, afterSeconds = 0))
        assertTrue("the clock was set back", AppLock.shouldLock(leftAt = 10, now = 5, afterSeconds = 600))
        assertEquals(listOf("Immediately", "After 1 minute", "After 5 minutes", "After 15 minutes"), AppLock.WAITS.map(AppLock::waitLabel))
    }

    @Test
    fun aLocationReadsAsTextWithAMapLink() {
        assertEquals(
            "My location: 52.22970, 21.01220 (within 15 m) https://maps.google.com/?q=52.22970,21.01220",
            LocationText.of(52.2297, 21.0122, 14.2f),
        )
        assertEquals("My location: -33.86880, 151.20930 https://maps.google.com/?q=-33.86880,151.20930", LocationText.of(-33.8688, 151.2093))
        assertTrue("(within 2.5 km)" in LocationText.of(0.0, 0.0, 2_450f))
    }

    @Test
    fun theAttachSheetOffersFilesCameraAudioAndLocation() {
        fun labels(state: ChatUiState) = attachItems(state, ChatActions()).map { it.label }
        assertEquals(listOf("Photos and files", "Take a photo", "Record audio", "Share my location"), labels(ChatUiState()))
        assertEquals(listOf("Photos and files", "Take a photo", "Share my location"), labels(ChatUiState(recordingSince = 5)))
    }

    @Test
    fun aPlayedFileIsFetchedOnceWhileFreshAndNamedWithoutItsPath() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        var now = 10_000_000L
        var fetches = 0
        val cache = MediaCache(tmp.newFolder("media"), dispatcher) { now }
        val download = suspend {
            fetches++
            Result.success(FileDownload("song.mp3", "audio/mpeg", 3, Buffer().writeUtf8("abc")))
        }
        val first = cache.file("/home/user/out/../song.MP3", download).getOrThrow()
        assertEquals("abc", first.readText())
        assertTrue(first.name.matches(Regex("[0-9a-f]{32}\\.mp3")))
        assertEquals(first, cache.file("/home/user/out/../song.MP3", download).getOrThrow())
        assertEquals(1, fetches)
        now += MediaCache.FRESH_MS + 1
        cache.file("/home/user/out/../song.MP3", download).getOrThrow()
        assertEquals(2, fetches)
        val failed = cache.file("/other.mp4") { Result.failure(IOException("gone")) }
        assertEquals("gone", failed.exceptionOrNull()?.message)
        assertNull(tmp.root.walkTopDown().firstOrNull { it.name.endsWith(".part") })
    }
}
