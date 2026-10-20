package labs.suchattai.tsukaima.notifications

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import labs.suchattai.tsukaima.MainActivity
import labs.suchattai.tsukaima.R

/** Notification channel ids and their registration. */
object Channels {
    const val APPROVALS = "approvals"
    const val PAIRING = "pairing"
    const val AGENT = "agent"
    const val GATEWAY = "gateway"
    const val SERVICE = "service"

    /** Channels whose content (commands, names, titles) stays off the lock screen. */
    val PRIVATE = setOf(APPROVALS, PAIRING, AGENT)

    /** Creates (or updates the names of) every channel; idempotent, call at process start. */
    fun register(context: Context) {
        fun channel(id: String, name: String, importance: Int) = NotificationChannel(id, name, importance).apply {
            if (id in PRIVATE) lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            if (id == SERVICE) setShowBadge(false)
        }
        val channels = listOf(
            channel(APPROVALS, "Tool approvals", NotificationManager.IMPORTANCE_HIGH),
            channel(PAIRING, "Pairing requests", NotificationManager.IMPORTANCE_HIGH),
            channel(AGENT, "Agent activity", NotificationManager.IMPORTANCE_DEFAULT),
            channel(GATEWAY, "Gateway", NotificationManager.IMPORTANCE_DEFAULT),
            channel(SERVICE, "Connection", NotificationManager.IMPORTANCE_LOW),
        )
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannels(channels)
    }
}

/** Where notification commands go; a seam so the coordination logic is testable on the JVM. */
interface NotificationPoster {
    fun post(spec: NotificationSpec)
    fun cancel(id: Int)

    /** Removes every notification of the app (sign-out). */
    fun cancelAll()
}

/** Builds Android notifications from [NotificationSpec]s. Every action is an explicit, immutable broadcast. */
class AndroidNotificationPoster(context: Context) : NotificationPoster {
    private val context = context.applicationContext
    private val manager = NotificationManagerCompat.from(this.context)

    fun build(spec: NotificationSpec): Notification {
        val open = openApp(spec.openScreen, spec.id, "content", spec.sessionId)
        val builder = NotificationCompat.Builder(context, spec.channel)
            .setSmallIcon(R.drawable.ic_stat_tsukaima)
            .setContentTitle(spec.title)
            .setContentText(spec.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(spec.text))
            .setContentIntent(open)
            .setOngoing(spec.ongoing)
            .setAutoCancel(!spec.ongoing)
            .setOnlyAlertOnce(true)
            .setPriority(priority(spec.channel))
        if (spec.channel in Channels.PRIVATE) {
            builder.setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(
                    NotificationCompat.Builder(context, spec.channel)
                        .setSmallIcon(R.drawable.ic_stat_tsukaima)
                        .setContentTitle(ApprovalNotifier.PUBLIC_TITLE)
                        .build(),
                )
        }
        val canRequireAuth = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        spec.actions.forEachIndexed { index, action ->
            val pending = when {
                action.intentAction == ApprovalNotifier.ACTION_OPEN_CHAT ->
                    openApp(ApprovalNotifier.SCREEN_CHAT, spec.id, index.toString(), spec.sessionId)
                // API 29–30 cannot demand an unlock for a button: open the app instead of acting.
                action.requiresAuth && !canRequireAuth ->
                    openApp(ApprovalNotifier.fallbackScreen(action.intentAction), spec.id, index.toString(), spec.sessionId)
                else -> {
                    val intent = Intent(action.intentAction)
                        .setClass(context, NotificationActionReceiver::class.java) // explicit component
                        .setData(uri(spec.id, index.toString())) // distinct intent per (notification, button)
                    action.extras.forEach { (k, v) -> intent.putExtra(k, v) }
                    PendingIntent.getBroadcast(context, spec.id * 31 + index, intent, FLAGS)
                }
            }
            val built = NotificationCompat.Action.Builder(0, action.label, pending)
            if (action.requiresAuth && canRequireAuth) built.setAuthenticationRequired(true)
            builder.addAction(built.build())
        }
        return builder.build()
    }

    /** Opens the app on [screen]; a chat screen also gets the stored [sessionId] (validated on receipt). */
    private fun openApp(screen: String?, id: Int, slot: String, sessionId: String? = null): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .setData(uri(id, slot))
        if (screen != null) intent.putExtra(ApprovalNotifier.EXTRA_OPEN_SCREEN, screen)
        if (screen == ApprovalNotifier.SCREEN_CHAT) DeepLink.sessionId(sessionId)?.let { intent.putExtra(DeepLink.EXTRA_SESSION_ID, it) }
        return PendingIntent.getActivity(context, id * 31 + slot.hashCode(), intent, FLAGS)
    }

    private fun uri(id: Int, slot: String): Uri = Uri.parse("tsukaima://n/$id/$slot")

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

    override fun cancelAll() = manager.cancelAll()

    private companion object {
        const val FLAGS = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    }

    private fun priority(channel: String) = when (channel) {
        Channels.APPROVALS, Channels.PAIRING -> NotificationCompat.PRIORITY_HIGH
        Channels.SERVICE -> NotificationCompat.PRIORITY_LOW
        else -> NotificationCompat.PRIORITY_DEFAULT
    }
}
