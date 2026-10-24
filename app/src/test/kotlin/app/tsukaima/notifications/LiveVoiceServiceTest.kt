package app.tsukaima.notifications

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ServiceInfo
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
    }

    @After
    fun tearDown() = collecting.cancel()

    /** [LiveVoiceService.start] as the chat calls it; returns the start the system then delivers (later). */
    private fun startIntent(): Intent {
        shadowOf(app).clearStartedServices()
        generation = checkNotNull(LiveVoiceService.start(app))
        return checkNotNull(shadowOf(app).nextStartedService)
    }

    /** The generation of the latest [startIntent]. */
    private var generation = 0L

    private fun deliver(intent: Intent, startId: Int = 1): ServiceController<LiveVoiceService> =
        Robolectric.buildService(LiveVoiceService::class.java, intent).create().startCommand(0, startId)

    private fun started() = deliver(startIntent())

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
        controller.withIntent(startIntent()).startCommand(0, 2)
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
        LiveVoiceService.stop(app, generation)
        assertEquals(LiveVoiceService::class.java.name, shadowOf(app).nextStoppedService?.component?.className)
        controller.destroy()
        assertEquals(0, ends.size) // a dropped session's screen keeps its reason line
    }

    @Test
    fun aStopBeforeTheServiceWentForegroundLetsItGoForegroundThenEndQuietly() {
        val intent = startIntent()
        LiveVoiceService.stop(app, generation) // a synchronous start failure: the start command has not run yet
        assertNull("no stopService before startForeground", shadowOf(app).nextStoppedService)
        val controller = deliver(intent, startId = 4)
        val shadow = shadowOf(controller.get())
        assertNotNull("startForeground answered the start", shadow.lastForegroundNotification)
        assertEquals(4, shadow.stopSelfId)
        controller.destroy()
        assertEquals(0, ends.size)
    }

    @Test
    fun anOlderSessionsLateDestroyDoesNotEndTheNextOne() {
        val a = started()
        LiveVoiceService.stop(app, generation)
        val b = startIntent()
        a.destroy() // A's destroy lands after B's start
        assertEquals(0, ends.size)
        deliver(b).destroy() // B is current: the system ending it is an end request
        assertEquals(1, ends.size)
    }

    @Test
    fun anOlderSessionsLateStopLeavesTheNewerServiceRunning() {
        val a = started()
        val genA = generation
        val b = deliver(startIntent(), startId = 2) // chat B's session starts while A is still closing
        shadowOf(app).clearStartedServices()
        assertFalse(LiveVoiceService.stop(app, genA)) // A's close completes late
        assertNull("B's service is not stopped", shadowOf(app).nextStoppedService)
        assertNotNull(shadowOf(b.get()).lastForegroundNotification)
        a.destroy()
        assertEquals(0, ends.size)
        assertTrue(LiveVoiceService.stop(app, generation))
        assertEquals(LiveVoiceService::class.java.name, shadowOf(app).nextStoppedService?.component?.className)
    }

    @Test
    fun withoutTheMicGrantNothingStarts() {
        shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO)
        shadowOf(app).clearStartedServices()
        assertNull(LiveVoiceService.start(app))
        assertNull(shadowOf(app).nextStartedService)
    }

    @Test
    fun endOnAnInstanceThatNeverWentForegroundRequestsTheEndAndStops() {
        val end = Intent(app, LiveVoiceService::class.java).setAction(LiveVoiceService.ACTION_END)
        val controller = Robolectric.buildService(LiveVoiceService::class.java, end).create().startCommand(0, 3)
        assertEquals(1, ends.size)
        assertNull(shadowOf(controller.get()).lastForegroundNotification)
        assertEquals(3, shadowOf(controller.get()).stopSelfId)
    }

    @Test
    fun aFailedStartForegroundRequestsTheEndOnceAndStops() {
        val controller = Robolectric.buildService(LiveVoiceService::class.java, startIntent()).create()
        shadowOf(controller.get()).setThrowInStartForeground(IllegalStateException("not allowed"))
        controller.startCommand(0, 2)
        assertEquals(1, ends.size)
        assertEquals(2, shadowOf(controller.get()).stopSelfId)
        controller.destroy()
        assertEquals(1, ends.size)
    }

    @Test
    fun theTypesAreMicrophoneAndMediaPlayback() = assertEquals(
        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
        started().get().foregroundServiceType,
    )

    @Test
    @Config(sdk = [29])
    fun api29HasNoMicrophoneTypeSoMediaPlaybackAlone() =
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK, started().get().foregroundServiceType)

    @Test
    fun theChatBackendStartsAndStopsTheService() {
        val backend = ContainerChatBackend(container)
        val shadowApp = shadowOf(app)
        shadowApp.clearStartedServices()
        val token = checkNotNull(backend.startLiveService())
        val start = checkNotNull(shadowApp.nextStartedService)
        assertEquals(LiveVoiceService::class.java.name, start.component?.className)
        deliver(start) // foreground: the stop may now stop it
        assertTrue(backend.stopLiveService(token))
        assertEquals(LiveVoiceService::class.java.name, shadowApp.nextStoppedService?.component?.className)
        assertEquals(0, ends.size)
    }
}
