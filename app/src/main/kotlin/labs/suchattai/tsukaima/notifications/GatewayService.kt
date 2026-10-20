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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = AndroidNotificationPoster(this).build(ApprovalNotifier.forService())
        try {
            ServiceCompat.startForeground(
                this, ApprovalNotifier.SERVICE_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } catch (e: RuntimeException) { // ForegroundServiceStartNotAllowedException and friends
            Log.w(TAG, "gateway service: cannot go foreground", e)
            stopSelf()
            return START_NOT_STICKY
        }
        if (scope == null) {
            scope = MainScope().also { s ->
                s.launch {
                    container.background.inForeground.first { it }
                    stopSelf() // back in the foreground: the connection already polls normally
                }
            }
        }
        container.notifications.startBackground()
        return START_NOT_STICKY
    }

    /** Android 15 caps `dataSync` time; when it runs out, stop and let the app go quiet. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        stopSelf()
    }

    override fun onDestroy() {
        scope?.cancel()
        scope = null
        container.notifications.stopBackground()
        // Only while backgrounded: disconnect and clear approval buttons that can no longer work.
        container.background.onServiceEnded()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "Tsukaima"

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
