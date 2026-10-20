package labs.suchattai.tsukaima.notifications

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import labs.suchattai.tsukaima.TsukaimaApp

/**
 * Foreground service (type `dataSync`) that keeps the gateway connected while the app is in the
 * background, so approvals, pairing requests and alerts can reach the user. Thin: the connection is
 * [labs.suchattai.tsukaima.data.ConnectionManager.startBackground], the decisions are in [NotificationHub].
 * Started on ProcessLifecycle ON_STOP (see [BackgroundPolicy]) and stopped on ON_START.
 */
class GatewayService : Service() {
    private val container get() = (application as TsukaimaApp).container

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
        if (container.inForeground.value) {
            stopSelf() // the user came back before we started: the normal start() already runs
            return START_NOT_STICKY
        }
        container.connection.startBackground()
        container.notifications.startBackground()
        return START_NOT_STICKY
    }

    /** Android 15 caps `dataSync` time; when it runs out, stop and let the app go quiet. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        stopSelf()
    }

    override fun onDestroy() {
        container.notifications.stopBackground()
        // Returning to the foreground already called start(); only a background stop drops the connection.
        if (!container.inForeground.value) container.connection.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "Tsukaima"

        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, GatewayService::class.java))
            } catch (e: RuntimeException) { // background-start restrictions
                Log.w(TAG, "gateway service: start refused", e)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, GatewayService::class.java))
        }
    }
}
