package app.tsukaima.notifications

import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import app.tsukaima.MainActivity
import app.tsukaima.TsukaimaApp
import app.tsukaima.ui.chat.ContainerChatBackend
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config

/** The live voice foreground service, on the real [TsukaimaApp] (its container holds the two live voice flows). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LiveVoiceServiceTest {
    private val app get() = RuntimeEnvironment.getApplication() as TsukaimaApp
    private val container get() = app.container
    private val ends = mutableListOf<Unit>()
    private val collecting = CoroutineScope(Dispatchers.Unconfined + Job())

    @Before
    fun setUp() {
        container.liveVoiceSeconds.value = 0
        collecting.launch { container.liveVoiceEndRequests.collect { ends += it } }
        LiveVoiceService.start(app) // as the chat does: a fresh start, not a stop
    }

    @After
    fun tearDown() = collecting.cancel()

    private fun started(): ServiceController<LiveVoiceService> =
        Robolectric.buildService(LiveVoiceService::class.java).create().startCommand(0, 1)

    private fun posted(): Notification {
        shadowOf(Looper.getMainLooper()).idle()
        val manager = app.getSystemService(NotificationManager::class.java)
        return checkNotNull(shadowOf(manager).getNotification(ApprovalNotifier.LIVE_VOICE_ID))
    }

    @Test
    fun startingPostsTheOngoingNotificationWithAnEndAction() {
        val service = started().get()
        val n = checkNotNull(shadowOf(service).lastForegroundNotification)
        assertEquals(ApprovalNotifier.LIVE_VOICE_ID, shadowOf(service).lastForegroundNotificationId)
        assertEquals(Channels.AGENT, n.channelId)
        assertEquals("Live voice with Hermes", n.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals("0:00 · tap to return", n.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertTrue("ongoing", n.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertTrue("alerts once", n.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        assertEquals(Notification.VISIBILITY_PRIVATE, n.visibility)
        val public = checkNotNull(n.publicVersion)
        assertEquals("Live voice with Hermes", public.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertNull(public.extras.getCharSequence(Notification.EXTRA_TEXT))

        val open = shadowOf(n.contentIntent)
        assertTrue(open.isActivity)
        assertEquals(MainActivity::class.java.name, open.savedIntent.component?.className)
        assertEquals(ApprovalNotifier.SCREEN_CHAT, open.savedIntent.getStringExtra(ApprovalNotifier.EXTRA_OPEN_SCREEN))

        assertEquals(listOf("End"), n.actions.map { it.title.toString() })
        val end = shadowOf(n.actions[0].actionIntent)
        assertTrue(end.isService)
        assertEquals(LiveVoiceService::class.java.name, end.savedIntent.component?.className)
        assertEquals(LiveVoiceService.ACTION_END, end.savedIntent.action)
    }

    @Test
    fun theTextFollowsTheSessionLength() {
        started()
        container.liveVoiceSeconds.value = 84
        assertEquals("1:24 · tap to return", posted().extras.getCharSequence(Notification.EXTRA_TEXT).toString())
    }

    @Test
    fun aRepeatedStartRefreshesTheNotification() {
        val controller = started()
        container.liveVoiceSeconds.value = 5 // same 10 s bucket: no update of its own
        controller.startCommand(0, 2)
        val n = shadowOf(controller.get()).lastForegroundNotification
        assertEquals("0:05 · tap to return", n.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
    }

    @Test
    fun theEndActionRequestsTheEnd() {
        val controller = started()
        controller.withIntent(Intent(app, LiveVoiceService::class.java).setAction(LiveVoiceService.ACTION_END))
            .startCommand(0, 2)
        assertEquals(1, ends.size)
    }

    @Test
    fun aDestroyTheAppDidNotAskForRequestsTheEnd() {
        started().destroy()
        assertEquals(1, ends.size)
    }

    @Test
    fun theAppsOwnStopDoesNotRequestAnotherEnd() {
        val controller = started()
        LiveVoiceService.stop(app)
        controller.destroy()
        assertEquals(0, ends.size) // a dropped session's screen keeps its reason line
    }

    @Test
    fun theChatBackendStartsAndStopsTheService() {
        val backend = ContainerChatBackend(container)
        val shadowApp = shadowOf(app)
        shadowApp.clearStartedServices()
        backend.startLiveService()
        assertEquals(LiveVoiceService::class.java.name, shadowApp.nextStartedService?.component?.className)
        backend.stopLiveService()
        assertEquals(LiveVoiceService::class.java.name, shadowApp.nextStoppedService?.component?.className)
        assertEquals(0, ends.size)
    }
}
