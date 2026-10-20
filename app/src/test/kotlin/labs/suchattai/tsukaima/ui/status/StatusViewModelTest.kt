package labs.suchattai.tsukaima.ui.status

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import labs.suchattai.tsukaima.data.HermesState
import labs.suchattai.tsukaima.data.api.DiskDto
import labs.suchattai.tsukaima.data.api.PairingDto
import labs.suchattai.tsukaima.data.api.SessionDto
import labs.suchattai.tsukaima.data.api.StatusDto
import labs.suchattai.tsukaima.data.api.SystemStatsDto
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StatusViewModelTest {
    private val gib = 1024L * 1024 * 1024

    private fun running() = StatusDto(
        version = "0.22.2",
        gatewayRunning = true,
        gatewayState = "running",
        gatewayPlatforms = mapOf("telegram" to JsonPrimitive("connected"), "discord" to JsonPrimitive("connected")),
        activeSessions = 3,
        disk = DiskDto(totalMb = 100_000.0, freeMb = 90_000.0, usedPercent = 10.0),
    )

    private fun stopped() = running().copy(gatewayRunning = false, gatewayState = "stopped", gatewayPlatforms = emptyMap())

    private val stats = SystemStatsDto(
        hostname = "hermes-box",
        memory = JsonObject(mapOf("total" to JsonPrimitive(8 * gib), "available" to JsonPrimitive((6.8 * gib).toLong()))),
    )

    private class FakeSource(initial: HermesState) : StatusSource {
        override val hermes = MutableStateFlow(initial)
        var statusCalls = 0
        var flipOnCall = Int.MAX_VALUE
        var flipTo: StatusDto? = null
        var stats: SystemStatsDto? = null
        var sessions: List<SessionDto> = emptyList()
        var pairing = PairingDto()
        val actions = mutableListOf<String>()

        override suspend fun refreshNow() {
            statusCalls++
            if (statusCalls >= flipOnCall) hermes.value = hermes.value.copy(status = flipTo)
        }
        override suspend fun systemStats() = stats?.let { Result.success(it) } ?: Result.failure(IllegalStateException())
        override suspend fun sessions(limit: Int) = Result.success(sessions)
        override suspend fun pairing() = Result.success(pairing)
        override suspend fun approvePairing(platform: String, requestId: String) = Result.success(Unit).also { actions += "approve $platform $requestId" }
        override suspend fun revokePairing(platform: String, userId: String) = Result.success(Unit).also { actions += "revoke $platform $userId" }
        override suspend fun gatewayStart() = Result.success(Unit).also { actions += "start" }
        override suspend fun gatewayStop() = Result.success(Unit).also { actions += "stop" }
        override suspend fun gatewayRestart() = Result.success(Unit).also { actions += "restart" }
    }

    @Before fun setUp() = Dispatchers.setMain(StandardTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private fun vm(src: StatusSource, now: Long = 0L) = StatusViewModel(src, clock = { now }, pollMs = 1_000)

    @Test
    fun factsShowDangerWhenStopped() = runTest {
        val src = FakeSource(HermesState(status = stopped(), lastSeenAt = 0L)).apply { stats = this@StatusViewModelTest.stats }
        val vm = vm(src)
        advanceUntilIdle()
        val gateway = vm.state.value.facts()[1]
        assertEquals("Gateway stopped", gateway.title)
        assertTrue(gateway.danger)

        src.hermes.value = HermesState(status = running(), lastSeenAt = 0L)
        advanceUntilIdle()
        val facts = vm.state.value.facts()
        assertEquals("Hermes 0.22.2", facts[0].title)
        assertEquals("hermes-box", facts[0].subtitle)
        assertEquals("Gateway running", facts[1].title)
        assertFalse(facts[1].danger)
        assertEquals("Telegram · Discord", facts[1].subtitle)
        assertEquals("3 active sessions", facts[3].title)
        assertEquals("Memory 1.2 / 8.0 GB", facts[4].title)
        assertEquals("Disk 10% used", facts[4].subtitle)
    }

    @Test
    fun stopGatewayPollsUntilStopped() = runTest {
        val src = FakeSource(HermesState(status = running(), lastSeenAt = 0L)).apply { flipTo = stopped() }
        val vm = vm(src)
        advanceUntilIdle()
        val before = src.statusCalls
        src.flipOnCall = before + 3 // the 3rd status poll after the stop call sees "stopped"
        vm.stopGateway()
        advanceUntilIdle()
        assertEquals(listOf("stop"), src.actions)
        assertEquals(3, src.statusCalls - before)
        assertFalse(vm.state.value.busy)
        assertEquals("stopped", vm.state.value.hermes.status?.gatewayState)
    }

    @Test
    fun offlineUsesLastKnownStatus() = runTest {
        val src = FakeSource(HermesState(reachable = false, status = running(), lastSeenAt = 0L))
        val vm = vm(src)
        advanceUntilIdle()
        assertTrue(vm.state.value.offline)
        assertEquals("Gateway running", vm.state.value.facts()[1].title)
    }

    @Test
    fun lastKnownMinutesLabel() = runTest {
        val now = 100 * 60_000L
        val src = FakeSource(HermesState(reachable = false, status = running(), lastSeenAt = now - 14 * 60_000L))
        val vm = vm(src, now)
        advanceUntilIdle()
        assertEquals("LAST KNOWN STATUS · 14 MIN AGO", vm.lastKnownLabel())

        src.hermes.value = HermesState(reachable = false, status = running(), lastSeenAt = null)
        advanceUntilIdle()
        assertEquals("LAST KNOWN STATUS", vm.lastKnownLabel())
        assertNull(vm.state.value.confirm)
    }
}
