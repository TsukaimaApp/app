package app.tsukaima.notifications

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.annotation.MainThread
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import app.tsukaima.MainActivity
import app.tsukaima.R
import app.tsukaima.TsukaimaApp
import app.tsukaima.domain.voice.liveClock

/**
 * Foreground service (types `microphone|mediaPlayback`) that runs exactly while a live voice session does, so the
 * mic and the WebRTC connection stay alive with the screen off. The chat starts it when the session starts (the app
 * is in the foreground then, as Android 14 requires for the microphone type) and stops it when the session ends.
 *
 * Its ongoing "Live voice with Hermes" notification shows the session length (refreshed every 10 s from
 * `AppContainer.liveVoiceSeconds`), opens the chat on a tap, and has one action, **End**, which comes back here as
 * [ACTION_END] and becomes an end request (`AppContainer.liveVoiceEndRequests`) for the chat to close the session.
 * The session must never run unattended, so an end of the service the app did not ask for (the system stopped it,
 * or it could not go foreground) is an end request too.
 */
class LiveVoiceService : Service() {
    private val container get() = (application as TsukaimaApp).container
    private var scope: CoroutineScope? = null

    /** The [start] generation this instance serves now; 0 before its first start command. */
    private var generation = 0L

    /** The generation an end request already went out for; [onDestroy] does not repeat it. */
    private var endRequestedFor = -1L

    /** This instance called `startForeground` successfully. */
    private var foreground = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_END) {
            requestEnd() // the chat's close ends the session, then stops this service
            if (!foreground) stopSelf(startId) // nothing to keep alive (a tap after the process was restarted)
            return START_NOT_STICKY
        }
        generation = intent?.getLongExtra(EXTRA_GENERATION, 0L) ?: 0L
        // Always, even for a start the app has already stopped: a startForegroundService() must be answered.
        try {
            val notification = build(container.liveVoiceSeconds.value)
            ServiceCompat.startForeground(this, ApprovalNotifier.LIVE_VOICE_ID, notification, type())
        } catch (e: RuntimeException) { // ForegroundServiceStartNotAllowedException, a missing mic grant, …
            Log.w(TAG, "live voice service: cannot go foreground", e)
            if (isCurrent(generation)) requestEnd()
            stopSelf(startId)
            return START_NOT_STICKY
        }
        foreground = true
        foregroundGeneration = generation
        if (!isCurrent(generation)) { // stopped (or superseded) before it could go foreground: end quietly
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (scope == null) {
            val s = MainScope().also { scope = it }
            s.launch {
                container.liveVoiceSeconds
                    .filter { it > 0 } // 0: the session is ending; the stop removes the notification
                    .distinctUntilChanged { a, b -> a / 10 == b / 10 }
                    .collect { update(it) }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope?.cancel()
        scope = null
        // The system ended the service of the running session: it must not run without it. A destroy after the
        // app's own stop, or of an older session's instance, is not an end request.
        if (generation != 0L && isCurrent(generation)) requestEnd()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun requestEnd() {
        if (generation != 0L && endRequestedFor == generation) return
        endRequestedFor = generation
        container.liveVoiceEndRequests.tryEmit(Unit)
    }

    @SuppressLint("MissingPermission") // a foreground service's notification; without the grant it is not shown
    private fun update(seconds: Int) {
        try {
            NotificationManagerCompat.from(this).notify(ApprovalNotifier.LIVE_VOICE_ID, build(seconds))
        } catch (e: SecurityException) {
            Log.w(TAG, "live voice notification not updated", e)
        }
    }

    private fun build(seconds: Int): Notification {
        val open = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .setData("tsukaima://n/${ApprovalNotifier.LIVE_VOICE_ID}/content".toUri())
            .putExtra(ApprovalNotifier.EXTRA_OPEN_SCREEN, ApprovalNotifier.SCREEN_CHAT)
        val end = Intent(this, LiveVoiceService::class.java).setAction(ACTION_END)
        val flags = AndroidNotificationPoster.FLAGS
        return NotificationCompat.Builder(this, Channels.AGENT)
            .setSmallIcon(R.drawable.ic_stat_tsukaima)
            .setContentTitle(TITLE)
            .setContentText("${liveClock(seconds)} · tap to return")
            .setContentIntent(PendingIntent.getActivity(this, ApprovalNotifier.LIVE_VOICE_ID, open, flags))
            .addAction(0, "End", PendingIntent.getService(this, ApprovalNotifier.LIVE_VOICE_ID, end, flags))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(
                NotificationCompat.Builder(this, Channels.AGENT)
                    .setSmallIcon(R.drawable.ic_stat_tsukaima)
                    .setContentTitle(TITLE)
                    .build(),
            )
            .build()
    }

    /** `microphone` exists from API 30; on API 29 the playback type alone keeps the process (and the mic) going. */
    private fun type(): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
    } else {
        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
    }

    companion object {
        private const val TAG = "Tsukaima"
        private const val TITLE = "Live voice with Hermes"

        /** The notification's End action. */
        const val ACTION_END = "app.tsukaima.action.LIVE_VOICE_END"

        private const val EXTRA_GENERATION = "generation"

        /** The latest successful [start]; these generations are main-thread only, like the service's callbacks. */
        private var latestStart = 0L

        /** The latest start [stop] applied to: from then on that session's service ends without an end request. */
        private var stoppedGeneration = 0L

        /** The latest start whose service went foreground. */
        private var foregroundGeneration = 0L

        /** [generation] is the latest start and the app has not stopped it. */
        private fun isCurrent(generation: Long) = generation == latestStart && generation > stoppedGeneration

        /**
         * Starts the service for a new session (or refreshes its notification when it runs) and returns the start's
         * generation, the token its [stop] takes. Null, starting nothing, without the mic grant (a microphone service
         * would be refused) or when the system refused the start.
         */
        @MainThread
        fun start(context: Context): Long? {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PERMISSION_GRANTED) {
                Log.w(TAG, "live voice service: no RECORD_AUDIO grant")
                return null
            }
            val generation = latestStart + 1
            val intent = Intent(context, LiveVoiceService::class.java).putExtra(EXTRA_GENERATION, generation)
            return try {
                ContextCompat.startForegroundService(context, intent)
                latestStart = generation // only now: a refused start leaves a running service's generation current
                generation
            } catch (e: RuntimeException) {
                Log.w(TAG, "live voice service: start refused", e)
                null
            }
        }

        /**
         * Ends the service of the session [start] returned [generation] for. A no-op returning false unless that is
         * still the latest start: an older session ending late (its close takes up to 15 s) must not stop a newer
         * one's service. `stopService` only once that start has gone foreground (stopping a service started with
         * `startForegroundService` before its `startForeground` crashes the app); otherwise its start command goes
         * foreground and then stops itself.
         */
        @MainThread
        fun stop(context: Context, generation: Long): Boolean {
            if (generation != latestStart) return false
            stoppedGeneration = latestStart
            if (foregroundGeneration == latestStart) context.stopService(Intent(context, LiveVoiceService::class.java))
            return true
        }
    }
}
