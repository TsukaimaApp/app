package app.tsukaima

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.edit
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
            .putExtra(EXTRA_TOKEN, token(context))

    /** The extra that proves an intent is one of the app's own ([token]). */
    const val EXTRA_TOKEN = "launch_token"

    /**
     * A random value made once per install and kept in the app's private preferences. [MainActivity] is exported
     * (the launcher starts it), so any app can send it an intent; only the app's own shortcuts and widget carry
     * this value, and only an intent that carries it may start the microphone ([trusted]).
     */
    fun token(context: Context): String {
        val prefs = context.applicationContext.getSharedPreferences("launch", Context.MODE_PRIVATE)
        prefs.getString("token", null)?.let { return it }
        val made = java.util.UUID.randomUUID().toString()
        prefs.edit { putString("token", made) }
        return prefs.getString("token", made) ?: made
    }

    /** Whether [intent] carries this install's [token]. Compared in constant time. */
    fun trusted(context: Context, intent: Intent?): Boolean {
        val given = intent?.getStringExtra(EXTRA_TOKEN) ?: return false
        return java.security.MessageDigest.isEqual(given.toByteArray(), token(context).toByteArray())
    }

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
