package labs.suchattai.tsukaima.assist

import java.security.SecureRandom

/**
 * How far an [AssistActivity] launch is trusted. The activity is exported (it must take `ACTION_ASSIST`), so any app
 * could start it: only the system's launches — through [TsukaimaVoiceSession] (valid [AssistNonce]) or
 * `ACTION_ASSIST` while Tsukaima holds the assistant role — listen at once and resume the recent assist chat.
 * Anything else opens a new chat at rest ("Tap to speak"): listening starts only on the user's tap.
 */
sealed interface AssistLaunch {
    data object Trusted : AssistLaunch

    data object Untrusted : AssistLaunch

    companion object {
        /** `Intent.ACTION_ASSIST`, spelled out so this stays plain JVM. */
        const val ACTION_ASSIST = "android.intent.action.ASSIST"

        fun trust(action: String?, nonceValid: Boolean, roleHeld: Boolean): AssistLaunch = when {
            nonceValid -> Trusted
            action == ACTION_ASSIST && roleHeld -> Trusted
            else -> Untrusted
        }
    }
}

/**
 * One-time token from [TsukaimaVoiceSession] to [AssistActivity] (same process): only the latest one issued is
 * valid, and only once.
 */
object AssistNonce {
    const val EXTRA = "labs.suchattai.tsukaima.extra.ASSIST_NONCE"

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
