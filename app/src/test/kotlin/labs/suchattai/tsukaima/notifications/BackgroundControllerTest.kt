package labs.suchattai.tsukaima.notifications

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import labs.suchattai.tsukaima.data.ConnectionControl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BackgroundControllerTest {
    /** Records the calls; `stop` is the only one that disconnects the gateway. */
    private class FakeConnection : ConnectionControl {
        val calls = mutableListOf<String>()
        val disconnects get() = calls.count { it == "stop" }
        override fun start() {
            calls += "start"
        }
        override fun startBackground() {
            calls += "startBackground"
        }
        override fun stop() {
            calls += "stop"
        }
    }

    private class Harness(var allow: Boolean = true, var serviceStarts: Boolean = true) {
        val connection = FakeConnection()
        val inForeground = MutableStateFlow(false)
        val events = mutableListOf<String>()
        var gate: (suspend () -> Unit)? = null
        fun controller(scope: kotlinx.coroutines.CoroutineScope) = BackgroundController(
            scope = scope,
            connection = connection,
            inForeground = inForeground,
            shouldHandOff = { gate?.invoke(); allow },
            startService = { events += "service"; serviceStarts },
            stopService = { events += "stopService" },
            onHandOff = { events += "handOff" },
            onQuiet = { events += "quiet" },
        )
    }

    @Test
    fun backgroundHandoffDoesNotDisconnect() = runTest(UnconfinedTestDispatcher()) {
        val h = Harness()
        val c = h.controller(this)
        c.onForeground()
        c.onBackground().join()
        assertEquals(listOf("start", "startBackground"), h.connection.calls)
        assertEquals(0, h.connection.disconnects)
        assertEquals(listOf("service", "handOff"), h.events)

        c.onForeground() // back: same connection, foreground polling, still no disconnect
        assertEquals(0, h.connection.disconnects)
        assertEquals("start", h.connection.calls.last())
        c.onServiceEnded(c.generation) // the service stops itself after the return: must not drop the connection
        assertEquals(0, h.connection.disconnects)
    }

    @Test
    fun policyDenyStillStops() = runTest(UnconfinedTestDispatcher()) {
        val h = Harness(allow = false)
        val c = h.controller(this)
        c.onForeground()
        c.onBackground().join()
        assertEquals(listOf("start", "stop"), h.connection.calls)
        assertEquals(listOf("quiet"), h.events) // no service
    }

    @Test
    fun refusedServiceStartStops() = runTest(UnconfinedTestDispatcher()) {
        val h = Harness(serviceStarts = false)
        val c = h.controller(this)
        c.onBackground().join()
        assertEquals(listOf("startBackground", "stop"), h.connection.calls)
        assertEquals(listOf("service", "quiet"), h.events)
    }

    @Test
    fun serviceEndedWhileBackgroundedStops() = runTest(UnconfinedTestDispatcher()) {
        val h = Harness()
        val c = h.controller(this)
        c.onBackground().join()
        c.onServiceEnded(c.generation)
        assertEquals(1, h.connection.disconnects)
        assertEquals("quiet", h.events.last())
    }

    @Test
    fun returnBeforeDecisionLeavesConnectionAlone() = runTest(UnconfinedTestDispatcher()) {
        val h = Harness()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        h.gate = { release.await() }
        val c = h.controller(this)
        val decision = c.onBackground()
        c.onForeground() // the user is back while the policy is still deciding
        release.complete(Unit)
        decision.join()
        assertEquals(listOf("start"), h.connection.calls)
        assertTrue(h.events.isEmpty())
    }

    @Test
    fun staleServiceEndDoesNotStopNewHandOff() = runTest(UnconfinedTestDispatcher()) {
        val h = Harness()
        val c = h.controller(this)
        c.onBackground().join()
        val first = c.generation
        c.onForeground()
        c.onBackground().join() // a fresh hand-off while the old instance is still being destroyed
        val second = c.generation
        assertTrue(second != first)
        c.onServiceEnded(first) // the old instance's late onDestroy
        assertEquals(0, h.connection.disconnects)
        c.onServiceEnded(second)
        assertEquals(1, h.connection.disconnects)
    }

    @Test
    fun everyKindSwitchedOffInBackgroundStopsTheService() = runTest(UnconfinedTestDispatcher()) {
        val h = Harness()
        val c = h.controller(this)
        c.onBackground().join()
        val handedOff = c.generation
        h.allow = false // the last notification kind went off
        c.reevaluate().join()
        assertEquals(listOf("startBackground", "stop"), h.connection.calls)
        assertEquals(listOf("service", "handOff", "stopService", "quiet"), h.events)
        c.onServiceEnded(handedOff) // that service's onDestroy: already quiet, no second stop
        assertEquals(1, h.connection.disconnects)
    }

    @Test
    fun kindSwitchedBackOnInBackgroundHandsOffAgain() = runTest(UnconfinedTestDispatcher()) {
        val h = Harness(allow = false)
        val c = h.controller(this)
        c.onBackground().join()
        assertEquals(listOf("quiet"), h.events)
        h.allow = true
        c.reevaluate().join()
        assertEquals(listOf("stop", "startBackground"), h.connection.calls)
        assertEquals(listOf("quiet", "service", "handOff"), h.events)
        c.reevaluate().join() // unchanged policy: nothing more
        assertEquals(listOf("quiet", "service", "handOff"), h.events)
    }

    @Test
    fun reevaluateInForegroundDoesNothing() = runTest(UnconfinedTestDispatcher()) {
        val h = Harness(allow = false)
        val c = h.controller(this)
        c.onForeground()
        c.reevaluate().join()
        assertEquals(listOf("start"), h.connection.calls)
        assertTrue(h.events.isEmpty())
    }
}
