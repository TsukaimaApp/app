package app.tsukaima.notifications

/** A notification asked the app to show [screen] (`chat` / `settings`); for chat, the stored [sessionId] to open. */
data class OpenScreen(val screen: String, val sessionId: String? = null)

/** The session id a notification's PendingIntent carries into [app.tsukaima.MainActivity]. */
object DeepLink {
    const val EXTRA_SESSION_ID = "session_id"

    private const val MAX_SESSION_ID = 64
    private val SESSION_ID = Regex("[A-Za-z0-9_-]{1,$MAX_SESSION_ID}")

    /** [raw] if it is a plausible stored session id (`[A-Za-z0-9_-]`, at most 64), else null. */
    fun sessionId(raw: String?): String? = raw?.takeIf { SESSION_ID.matches(it) }
}
