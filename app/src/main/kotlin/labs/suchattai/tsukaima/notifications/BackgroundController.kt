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
 * - [onServiceEnded]: the service died while backgrounded: stop and go quiet, unless a newer hand-off
 *   ([generation]) has started since that service instance was started (a stale `onDestroy`).
 * - [reevaluate]: an input of [shouldHandOff] changed (a notification kind switched on or off). While in the
 *   background it stops a running hand-off the policy no longer allows ([stopService], then stop and go quiet), or
 *   hands off a quiet connection it now allows; in the foreground the next [onBackground] decides.
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
    private val stopService: () -> Unit,
    private val onHandOff: () -> Unit,
    private val onQuiet: () -> Unit,
) {
    private val lock = Any()

    /** Bumped by each hand-off before it starts the service; a service instance records the latest it saw. */
    @Volatile var generation = 0L
        private set

    /** True from a successful hand-off until the connection is stopped; guarded by [lock]. */
    private var handedOff = false

    fun onForeground() {
        synchronized(lock) {
            inForeground.value = true
            handedOff = false // the service stops itself; the connection is the foreground's again
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
                if (handOff && handOffLocked()) return@launch
                goQuietLocked()
            }
        }
    }

    /** Re-applies the policy in the background (see the class doc); a no-op in the foreground. */
    fun reevaluate(): Job = scope.launch {
        if (inForeground.value) return@launch
        val handOff = shouldHandOff()
        synchronized(lock) {
            if (inForeground.value || handOff == handedOff) return@launch
            if (handOff) {
                if (!handOffLocked()) goQuietLocked() // startBackground() also restarts a stopped connection
            } else {
                generation++ // the ending service's onServiceEnded is now stale: this call already went quiet
                stopService()
                goQuietLocked()
            }
        }
    }

    private fun handOffLocked(): Boolean {
        generation++
        connection.startBackground()
        handedOff = startService()
        if (handedOff) onHandOff()
        return handedOff
    }

    private fun goQuietLocked() {
        handedOff = false
        connection.stop()
        onQuiet()
    }

    /** [startedGeneration] is the [generation] the ending service instance was last started for. */
    fun onServiceEnded(startedGeneration: Long) {
        synchronized(lock) {
            if (inForeground.value || startedGeneration != generation) return
            goQuietLocked()
        }
    }
}
