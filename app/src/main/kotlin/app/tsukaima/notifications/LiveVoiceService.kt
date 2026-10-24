package app.tsukaima.notifications

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
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

    /** An end request already went out for this instance; [onDestroy] does not repeat it. */
    private var endRequested = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_END) {
            requestEnd() // the chat's close ends the session, then stops this service
            return START_NOT_STICKY
        }
        try {
            val notification = build(container.liveVoiceSeconds.value)
            ServiceCompat.startForeground(this, ApprovalNotifier.LIVE_VOICE_ID, notification, type())
        } catch (e: RuntimeException) { // ForegroundServiceStartNotAllowedException, a missing mic grant, …
            Log.w(TAG, "live voice service: cannot go foreground", e)
            requestEnd()
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
        if (!stopRequested) requestEnd() // the system ended the service: the session must not run without it
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun requestEnd() {
        if (endRequested) return
        endRequested = true
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

        /**
         * Set by [stop], cleared by [start]: the app itself ended the service, so its [onDestroy] is not an end
         * request (a dropped session's screen keeps its reason line until the user leaves it).
         */
        @Volatile private var stopRequested = false

        /**
         * Starts the service (or refreshes its notification when it runs). Returns false when the system refused
         * the start; the caller then ends the session.
         */
        fun start(context: Context): Boolean = try {
            stopRequested = false
            ContextCompat.startForegroundService(context, Intent(context, LiveVoiceService::class.java))
            true
        } catch (e: RuntimeException) {
            Log.w(TAG, "live voice service: start refused", e)
            false
        }

        /** Stops the service if it runs; a no-op otherwise. */
        fun stop(context: Context) {
            stopRequested = true
            context.stopService(Intent(context, LiveVoiceService::class.java))
        }
    }
}
