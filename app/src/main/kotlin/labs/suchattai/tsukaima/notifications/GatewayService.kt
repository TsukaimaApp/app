package labs.suchattai.tsukaima.notifications

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import labs.suchattai.tsukaima.TsukaimaApp

/**
 * Foreground service (type `dataSync`) that keeps the app process, and with it the connection handed over
 * by [BackgroundController], alive while the app is in the background. Thin: it shows the ongoing
 * "Listening for Hermes" notification, runs [NotificationHub]'s background poll, and stops itself as soon as
 * the app is in the foreground again (ON_START never calls stopService, so startForeground always runs).
 */
class GatewayService : Service() {
    private val container get() = (application as TsukaimaApp).container
    private var scope: CoroutineScope? = null

    /** Latest start of this instance: a stale stop request cannot end a newer start. */
    private var lastStartId = 0

    /** The hand-off generation this instance was last started for ([BackgroundController.generation]). */
    private var generation = 0L

    /** Stops the service once the app is in the foreground; replaced on every start, so each start has one. */
    private var watcher: Job? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        generation = container.background.generation
        val notification = AndroidNotificationPoster(this).build(ApprovalNotifier.forService())
        try {
            ServiceCompat.startForeground(
                this, ApprovalNotifier.SERVICE_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } catch (e: RuntimeException) { // ForegroundServiceStartNotAllowedException and friends
            Log.w(TAG, "gateway service: cannot go foreground", e)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val s = scope ?: MainScope().also { scope = it }
        // A watcher that already fired stopped with an older start id, which a newer start outlives: relaunch.
        watcher?.cancel()
        watcher = s.launch {
            container.background.inForeground.first { it }
            stopSelf(lastStartId) // back in the foreground: the connection already polls normally
        }
        container.notifications.startBackground()
        return START_NOT_STICKY
    }

    /** Android 15 caps `dataSync` time; when it runs out, stop and let the app go quiet. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        stopSelf() // the system's limit: end this instance whatever its starts
    }

    override fun onDestroy() {
        scope?.cancel()
        scope = null
        watcher = null
        container.notifications.stopBackground()
        // Only while backgrounded: disconnect and clear approval buttons that can no longer work.
        container.background.onServiceEnded(generation)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "Tsukaima"

        /** Stops the service if it runs (sign-out); it also stops itself once the app is in the foreground. */
        fun stop(context: Context) {
            context.stopService(Intent(context, GatewayService::class.java))
        }

        /** Returns false when the system refused the start (background-start restrictions). */
        fun start(context: Context): Boolean = try {
            ContextCompat.startForegroundService(context, Intent(context, GatewayService::class.java))
            true
        } catch (e: RuntimeException) {
            Log.w(TAG, "gateway service: start refused", e)
            false
        }
    }
}
