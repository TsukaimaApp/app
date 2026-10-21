package app.tsukaima.assist

import java.security.SecureRandom

/**
 * How far an [AssistActivity] launch is trusted. The activity is exported (it must take `ACTION_ASSIST`), so any app
 * could start it, even with `ACTION_ASSIST` while Tsukaima holds the assistant role (the role says nothing about the
 * caller). Only a launch carrying the voice session's one-time [AssistNonce] — the system invoking the assistant
 * through [TsukaimaVoiceSession] — listens at once and resumes the recent assist chat. Anything else opens a new
 * chat at rest ("Tap to speak"), remembers nothing, and listens only on the user's tap.
 */
sealed interface AssistLaunch {
    data object Trusted : AssistLaunch

    data object Untrusted : AssistLaunch

    companion object {
        fun trust(nonceValid: Boolean): AssistLaunch = if (nonceValid) Trusted else Untrusted
    }
}

/**
 * One-time token from [TsukaimaVoiceSession] to [AssistActivity] (same process): only the latest one issued is
 * valid, and only once.
 */
object AssistNonce {
    const val EXTRA = "app.tsukaima.extra.ASSIST_NONCE"

    private val random = SecureRandom()
    private var latest: String? = null

    @Synchronized
    fun issue(): String {
        val bytes = ByteArray(16).also(random::nextBytes)
        return bytes.joinToString("") { "%02x".format(it) }.also { latest = it }
    }

    @Synchronized
    fun consume(value: String?): Boolean {
        val ok = value != null && value == latest
        if (ok) latest = null
        return ok
    }
}
