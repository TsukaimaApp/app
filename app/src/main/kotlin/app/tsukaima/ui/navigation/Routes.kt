package app.tsukaima.ui.navigation

import android.net.Uri

/**
 * Navigation destinations. [path] is the NavHost route pattern; destinations with arguments also offer a
 * builder for the concrete route.
 */
sealed class Route(val path: String) {
    data object Onboarding : Route("onboarding")
    data object Status : Route("status")

    /** A chat. `session` is the stored Hermes session id; absent means a new chat. */
    data object Chat : Route("chat?$ARG_SESSION={$ARG_SESSION}") {
        fun create(session: String? = null): String =
            if (session == null) "chat" else "chat?$ARG_SESSION=${Uri.encode(session)}"
    }

    data object Cron : Route("cron")

    /** Cron job editor. `id` absent means a new job. */
    data object CronEdit : Route("cron/edit?$ARG_ID={$ARG_ID}") {
        fun create(id: String? = null): String = if (id == null) "cron/edit" else "cron/edit?$ARG_ID=${Uri.encode(id)}"
    }

    data object Settings : Route("settings")
    data object Logs : Route("logs")

    companion object {
        const val ARG_SESSION = "session"
        const val ARG_ID = "id"
    }
}
