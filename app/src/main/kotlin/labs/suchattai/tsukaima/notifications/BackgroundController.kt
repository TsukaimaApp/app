package labs.suchattai.tsukaima.notifications

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import labs.suchattai.tsukaima.data.ConnectionControl

/**
 * Hands the live connection over between foreground and background (amended ruling 1):
 *
 * - [onForeground] (ON_START): back to foreground polling via [ConnectionControl.start], which keeps a
 *   connected socket. It never stops the service; [GatewayService] watches [inForeground] and stops itself.
 * - [onBackground] (ON_STOP): if [shouldHandOff] allows, the running connection switches to background
 *   polling (same socket, no disconnect), the service starts and [onHandOff] notifies open approvals. If the
 *   policy says no, or the service start is refused, the connection stops and [onQuiet] clears approvals.
 * - [onServiceEnded]: the service died while backgrounded: stop and go quiet.
 *
 * All transitions run under one lock with the [inForeground] check, so a quick return to the app can never
 * be followed by a background stop.
 */
class BackgroundController(
    private val scope: CoroutineScope,
    private val connection: ConnectionControl,
    val inForeground: MutableStateFlow<Boolean>,
    private val shouldHandOff: suspend () -> Boolean,
    private val startService: () -> Boolean,
    private val onHandOff: () -> Unit,
    private val onQuiet: () -> Unit,
) {
    private val lock = Any()

    fun onForeground() {
        synchronized(lock) {
            inForeground.value = true
            connection.start()
        }
    }

    /** Decides off the caller's thread (the policy may read the Keystore). */
    fun onBackground(): Job {
        synchronized(lock) { inForeground.value = false }
        return scope.launch {
            val handOff = shouldHandOff()
            synchronized(lock) {
                if (inForeground.value) return@launch // the user came back first: nothing to do
                if (handOff) {
                    connection.startBackground()
                    if (startService()) {
                        onHandOff()
                        return@launch
                    }
                }
                connection.stop()
                onQuiet()
            }
        }
    }

    fun onServiceEnded() {
        synchronized(lock) {
            if (inForeground.value) return
            connection.stop()
            onQuiet()
        }
    }
}
