package labs.suchattai.tsukaima.assist

import labs.suchattai.tsukaima.data.prefs.Settings

/**
 * Which chat the device-assistant sheet opens on: the one it used last if that was within [REUSE_WINDOW_MS]
 * (a follow-up question keeps its context), otherwise a new chat.
 */
object AssistSessionPolicy {
    const val REUSE_WINDOW_MS = 30 * 60_000L

    /** The stored session id to resume at [now] (epoch millis), or null for a new chat. */
    fun sessionToResume(settings: Settings, now: Long): String? {
        val at = settings.assistSessionAt ?: return null
        return settings.assistSessionId.takeIf { now - at < REUSE_WINDOW_MS }
    }
}
