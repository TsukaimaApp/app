package app.tsukaima.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import androidx.core.net.toUri
import app.tsukaima.R
import app.tsukaima.Shortcuts
import app.tsukaima.TsukaimaApp
import app.tsukaima.notifications.ApprovalNotifier

/**
 * The home-screen widget: what Hermes is doing in the last chat, and a bar to ask it something (a new chat) or to
 * talk to it (voice). The system asks for a redraw now and then ([onUpdate]); the app redraws it whenever its state
 * changes ([render]). With the app lock on, the chat's title and detail are left out: the home screen is not locked.
 */
class StatusWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val container = (context.applicationContext as? TsukaimaApp)?.container
        val state = container?.widget?.state?.value ?: WidgetState()
        val private = container?.settings?.value?.appLock == true
        ids.forEach { manager.updateAppWidget(it, views(context, state, private)) }
    }

    companion object {
        /** Redraws every placed widget with [state]; [private] hides the chat's title and detail. */
        fun render(context: Context, state: WidgetState, private: Boolean) {
            try {
                val manager = AppWidgetManager.getInstance(context) ?: return
                val ids = manager.getAppWidgetIds(ComponentName(context, StatusWidget::class.java))
                if (ids.isNotEmpty()) manager.updateAppWidget(ids, views(context, state, private))
            } catch (e: RuntimeException) { // a launcher without widgets, or the binder transaction failed
                Log.w("Tsukaima", "widget not updated", e)
            }
        }

        internal fun views(context: Context, state: WidgetState, private: Boolean): RemoteViews =
            RemoteViews(context.packageName, R.layout.widget_status).apply {
                setTextViewText(R.id.widget_line, state.line)
                setTextColor(R.id.widget_line, context.getColor(if (state.connected && state.status == WidgetStatus.Approval) R.color.widget_warn else R.color.widget_ink2))
                val title = state.title?.takeIf { !private }
                val detail = (if (private) "Open Tsukaima to see your chats" else state.detail)
                setTextViewText(R.id.widget_title, title.orEmpty())
                setViewVisibility(R.id.widget_title, if (title.isNullOrBlank()) View.GONE else View.VISIBLE)
                setTextViewText(R.id.widget_detail, detail.orEmpty())
                setViewVisibility(R.id.widget_detail, if (detail.isNullOrBlank()) View.GONE else View.VISIBLE)
                setOnClickPendingIntent(R.id.widget_top, open(context, ApprovalNotifier.SCREEN_CHAT, 1))
                setOnClickPendingIntent(R.id.widget_ask, open(context, ApprovalNotifier.SCREEN_NEW_CHAT, 2))
                setOnClickPendingIntent(R.id.widget_mic, open(context, ApprovalNotifier.SCREEN_VOICE, 3))
            }

        private fun open(context: Context, screen: String, code: Int): PendingIntent =
            PendingIntent.getActivity(
                context, code, Shortcuts.intent(context, screen).setData("tsukaima://widget/$screen".toUri()),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
    }
}
