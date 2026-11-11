package app.tsukaima.security

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The app lock's state: whether the app's content is behind the lock screen right now. It starts locked (a fresh
 * process has proved nothing), locks again when the app comes back after more than the set wait in the background,
 * and opens on [unlocked]. Whether the lock is switched on at all is the setting's business
 * ([app.tsukaima.data.prefs.Settings.appLock]); with it off this state is ignored.
 */
class AppLock(private val now: () -> Long = System::currentTimeMillis) {
    private val _locked = MutableStateFlow(true)
    val locked: StateFlow<Boolean> = _locked.asStateFlow()

    /** When the app last left the screen; null while it is on it, and before it ever left. */
    private var leftAt: Long? = null

    /** The app left the screen (not for a rotation). */
    fun onStop() {
        leftAt = now()
    }

    /** The app is back on screen: it locks if it was away for [afterSeconds] or longer. */
    fun onStart(afterSeconds: Int) {
        val left = leftAt ?: return
        leftAt = null
        if (shouldLock(left, now(), afterSeconds)) _locked.value = true
    }

    /**
     * Whether the app is locked as of now, for an entry point that is not the main screen (the assistant sheet):
     * the wait is judged at this moment, because [onStart] only runs when the main screen comes back. The time
     * the app left is kept, so the main screen judges it again. The sheet itself does not count as being in the app.
     */
    fun lockedNow(afterSeconds: Int): Boolean {
        val left = leftAt
        if (left != null && shouldLock(left, now(), afterSeconds)) _locked.value = true
        return _locked.value
    }

    /** The user proved who they are, or the lock is switched off. */
    fun unlocked() {
        _locked.value = false
    }

    companion object {
        /** Away since [leftAt] until [now] (epoch millis): lock when that is [afterSeconds] or more; a clock set back locks too. */
        fun shouldLock(leftAt: Long, now: Long, afterSeconds: Int): Boolean {
            val away = now - leftAt
            return away < 0 || away >= afterSeconds * 1000L
        }

        /** The waits the setting offers, in seconds; 0 = at once. */
        val WAITS = listOf(0, 60, 300, 900)

        fun waitLabel(seconds: Int): String = when {
            seconds <= 0 -> "Immediately"
            seconds == 60 -> "After 1 minute"
            else -> "After ${seconds / 60} minutes"
        }

        /** Whether the phone has a screen lock at all (PIN, pattern, password): without one there is nothing to ask for. */
        fun deviceSecure(context: Context): Boolean =
            context.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true

        /**
         * Asks the user to prove who they are with the phone's own prompt: fingerprint or face where set up, else
         * the screen lock. [onResult] gets true on success; false when they backed out or the prompt could not show.
         */
        fun authenticate(activity: Activity, title: String, onResult: (Boolean) -> Unit) {
            try {
                val builder = BiometricPrompt.Builder(activity).setTitle(title)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    builder.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
                } else {
                    @Suppress("DEPRECATION")
                    builder.setDeviceCredentialAllowed(true)
                }
                builder.build().authenticate(
                    CancellationSignal(), activity.mainExecutor,
                    object : BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) = onResult(true)
                        override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) = onResult(false)
                    },
                )
            } catch (e: RuntimeException) { // no screen lock set, or the prompt refused its options
                Log.w("Tsukaima", "app lock: prompt unavailable", e)
                onResult(false)
            }
        }
    }
}
