package labs.suchattai.tsukaima.notifications

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import labs.suchattai.tsukaima.MainActivity

/** Notification channel ids and their registration. */
object Channels {
    const val APPROVALS = "approvals"
    const val PAIRING = "pairing"
    const val AGENT = "agent"
    const val GATEWAY = "gateway"
    const val SERVICE = "service"

    /** Creates (or updates the names of) every channel; idempotent, call at process start. */
    fun register(context: Context) {
        val high = NotificationManagerCompat.IMPORTANCE_HIGH
        val default = NotificationManagerCompat.IMPORTANCE_DEFAULT
        val low = NotificationManagerCompat.IMPORTANCE_LOW
        val channels = listOf(
            NotificationChannelCompat.Builder(APPROVALS, high).setName("Tool approvals").build(),
            NotificationChannelCompat.Builder(PAIRING, high).setName("Pairing requests").build(),
            NotificationChannelCompat.Builder(AGENT, default).setName("Agent activity").build(),
            NotificationChannelCompat.Builder(GATEWAY, default).setName("Gateway").build(),
            NotificationChannelCompat.Builder(SERVICE, low).setName("Connection").setShowBadge(false).build(),
        )
        NotificationManagerCompat.from(context).createNotificationChannelsCompat(channels)
    }
}

/** Where notification commands go; a seam so the coordination logic is testable on the JVM. */
interface NotificationPoster {
    fun post(spec: NotificationSpec)
    fun cancel(id: Int)
}

/** Builds Android notifications from [NotificationSpec]s. Every action is an explicit, immutable broadcast. */
class AndroidNotificationPoster(context: Context) : NotificationPoster {
    private val context = context.applicationContext
    private val manager = NotificationManagerCompat.from(this.context)

    fun build(spec: NotificationSpec): Notification {
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = NotificationCompat.Builder(context, spec.channel)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(spec.title)
            .setContentText(spec.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(spec.text))
            .setContentIntent(open)
            .setOngoing(spec.ongoing)
            .setAutoCancel(!spec.ongoing)
            .setOnlyAlertOnce(true)
            .setPriority(priority(spec.channel))
        spec.actions.forEachIndexed { index, action ->
            val intent = Intent(action.intentAction)
                .setClass(context, NotificationActionReceiver::class.java) // explicit component
            action.extras.forEach { (k, v) -> intent.putExtra(k, v) }
            // Distinct request code per (notification, button): extras are not part of intent equality.
            val pending = PendingIntent.getBroadcast(
                context, spec.id * 31 + index, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            builder.addAction(0, action.label, pending)
        }
        return builder.build()
    }

    @SuppressLint("MissingPermission") // checked: areNotificationsEnabled covers POST_NOTIFICATIONS on API 33+
    override fun post(spec: NotificationSpec) {
        if (!manager.areNotificationsEnabled()) return
        try {
            manager.notify(spec.id, build(spec))
        } catch (e: SecurityException) {
            Log.w("Tsukaima", "notification not posted", e)
        }
    }

    override fun cancel(id: Int) = manager.cancel(id)

    private fun priority(channel: String) = when (channel) {
        Channels.APPROVALS, Channels.PAIRING -> NotificationCompat.PRIORITY_HIGH
        Channels.SERVICE -> NotificationCompat.PRIORITY_LOW
        else -> NotificationCompat.PRIORITY_DEFAULT
    }
}
