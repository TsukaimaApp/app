package app.tsukaima

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import app.tsukaima.notifications.ApprovalNotifier

/**
 * The launcher shortcuts on a long press of the app icon: New chat, Voice, Continue last chat. Each opens
 * [MainActivity] with the screen it wants ([ApprovalNotifier.EXTRA_OPEN_SCREEN]), the way a notification does.
 * Published from code at each start, so they follow the build's own package name.
 */
object Shortcuts {
    /** The intent that opens the app on [screen]; also what the widget's buttons fire. */
    fun intent(context: Context, screen: String): Intent =
        Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(ApprovalNotifier.EXTRA_OPEN_SCREEN, screen)

    fun publish(context: Context) {
        fun shortcut(id: String, label: String, icon: Int, screen: String) = ShortcutInfoCompat.Builder(context, id)
            .setShortLabel(label)
            .setIcon(IconCompat.createWithResource(context, icon))
            .setIntent(intent(context, screen))
            .build()
        try {
            ShortcutManagerCompat.setDynamicShortcuts(
                context,
                listOf(
                    shortcut("new_chat", "New chat", R.drawable.ic_shortcut_chat, ApprovalNotifier.SCREEN_NEW_CHAT),
                    shortcut("voice", "Voice", R.drawable.ic_shortcut_mic, ApprovalNotifier.SCREEN_VOICE),
                    shortcut("continue", "Continue last chat", R.drawable.ic_shortcut_history, ApprovalNotifier.SCREEN_CHAT),
                ),
            )
        } catch (e: RuntimeException) { // a launcher without shortcuts, or the system's rate limit
            Log.w("Tsukaima", "shortcuts not published", e)
        }
    }
}
