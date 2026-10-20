package labs.suchattai.tsukaima.ui.status

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
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
        override val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
        var statusCalls = 0
        var flipOnCall = Int.MAX_VALUE
        var flipTo: StatusDto? = null
        var stats: SystemStatsDto? = null
        var sessions: List<SessionDto> = emptyList()
        var pairing = PairingDto()
        val actions = mutableListOf<String>()

        var gate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
        var onRefresh: (Int) -> Unit = {}

        override suspend fun refreshNow() {
            statusCalls++
            onRefresh(statusCalls)
            gate?.await()
            if (statusCalls >= flipOnCall) hermes.value = hermes.value.copy(status = flipTo)
        }
        override suspend fun systemStats() = stats?.let { Result.success(it) } ?: Result.failure(IllegalStateException())
        override suspend fun sessions(limit: Int) = Result.success(sessions)
        override suspend fun pairing() = Result.success(pairing)
        var pairingResult: Result<Unit> = Result.success(Unit)
        override var serverUrl = ""
        override suspend fun approvePairing(platform: String, requestId: String) = pairingResult.also { actions += "approve $platform $requestId" }
        override suspend fun revokePairing(platform: String, userId: String) = Result.success(Unit).also { actions += "revoke $platform $userId" }
        var actionResult: Result<Unit> = Result.success(Unit)
        override suspend fun gatewayStart() = actionResult.also { actions += "start" }
        override suspend fun gatewayStop() = Result.success(Unit).also { actions += "stop" }
        override suspend fun gatewayRestart() = Result.success(Unit).also { actions += "restart" }
    }

    private val created = mutableListOf<StatusViewModel>()

    @Before fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    @After
    fun tearDown() {
        created.forEach { it.viewModelScope.cancel() } // nothing may outlive the test and touch Main after resetMain
        Dispatchers.resetMain()
    }

    private val toasts = mutableListOf<String>()

    private fun vm(src: StatusSource, now: Long = 0L) =
        StatusViewModel(src, clock = { now }, pollMs = 1_000, toast = { toasts += it }).also { created += it }

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
    fun gatewayActionFailureToastsDetail() = runTest {
        val src = FakeSource(HermesState(status = stopped(), lastSeenAt = 0L)).apply {
            actionResult = Result.failure(
                labs.suchattai.tsukaima.data.api.ApiException.fromResponse(409, """{"detail":"Gateway is already starting"}"""),
            )
        }
        val vm = vm(src)
        advanceUntilIdle()
        val polls = src.statusCalls
        vm.startGateway()
        advanceUntilIdle()
        assertEquals(listOf("Couldn’t start the gateway: Gateway is already starting"), toasts)
        assertEquals("no polling after a refused action", polls, src.statusCalls)
        assertFalse(vm.state.value.busy)
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

    @Test
    fun restartWaitsForUpdatedAtChange() = runTest {
        val src = FakeSource(HermesState(status = running().copy(gatewayUpdatedAt = "t0"), lastSeenAt = 0L))
        val vm = vm(src)
        advanceUntilIdle()
        val before = src.statusCalls
        src.onRefresh = { n ->
            if (n == before + 3) src.hermes.value = HermesState(status = running().copy(gatewayUpdatedAt = "t1"), lastSeenAt = 0L)
        }
        vm.restartGateway()
        repeat(2) { advanceTimeBy(1_001) } // polls 1 and 2: still "running" with the old timestamp
        runCurrent()
        assertTrue(vm.state.value.busy)
        advanceUntilIdle()
        assertEquals(3, src.statusCalls - before)
        assertFalse(vm.state.value.busy)
    }

    @Test
    fun refreshIgnoredWhileInFlight() = runTest {
        val src = FakeSource(HermesState(status = running())).apply { gate = CompletableDeferred() }
        val vm = vm(src)
        runCurrent()
        assertTrue(vm.state.value.refreshing)
        vm.refresh()
        vm.refresh()
        runCurrent()
        assertEquals(1, src.statusCalls)
        src.gate!!.complete(Unit)
        advanceUntilIdle()
        assertFalse(vm.state.value.refreshing)
        vm.refresh()
        advanceUntilIdle()
        assertEquals(2, src.statusCalls)
    }

    @Test
    fun platformsChangedRefreshesWhileVisibleDebounced() = runTest {
        val src = FakeSource(HermesState(status = running(), lastSeenAt = 0L))
        val vm = vm(src)
        advanceUntilIdle()
        val initial = src.statusCalls

        src.changes.emit(Unit)
        advanceUntilIdle()
        assertEquals("not on screen: ignored", initial, src.statusCalls)

        vm.setVisible(true)
        runCurrent()
        src.changes.emit(Unit)
        advanceTimeBy(400)
        src.changes.emit(Unit)
        advanceTimeBy(999)
        runCurrent()
        assertEquals("debounced", initial, src.statusCalls)
        advanceTimeBy(2)
        runCurrent()
        assertEquals(initial + 1, src.statusCalls)

        vm.setVisible(false)
        runCurrent()
        src.changes.emit(Unit)
        advanceUntilIdle()
        assertEquals(initial + 1, src.statusCalls)
    }

    @Test
    fun pairingApproveIsToastedAndADoubleTapSendsOnce() = runTest {
        val src = FakeSource(HermesState(status = running(), lastSeenAt = 0L))
        val vm = vm(src)
        advanceUntilIdle()
        val entry = labs.suchattai.tsukaima.ui.settings.PairingEntry("telegram", "alice", "req-1", "42")
        vm.approvePairing(entry)
        vm.approvePairing(entry) // the second tap of a double tap
        advanceUntilIdle()
        assertEquals(listOf("approve telegram req-1"), src.actions)
        assertEquals(listOf("Approved alice"), toasts)

        src.pairingResult = Result.failure(java.io.IOException("boom"))
        vm.approvePairing(entry) // the guard is released once the first call finished
        advanceUntilIdle()
        assertEquals(2, src.actions.size)
        assertEquals("Couldn’t approve alice", toasts.last())

        vm.revokePairing(entry)
        vm.revokePairing(entry)
        advanceUntilIdle()
        assertEquals("revoke telegram 42", src.actions.last())
        assertEquals(3, src.actions.size)
        assertEquals("Revoked alice", toasts.last())
    }

    @Test
    fun offlineCaptionNamesTheHost() = runTest {
        val src = FakeSource(HermesState(reachable = false)).apply { serverUrl = "http://100.64.1.2:9119" }
        val vm = vm(src)
        advanceUntilIdle()
        assertTrue(vm.state.value.offlineCaption, vm.state.value.offlineCaption.startsWith("Hermes at 100.64.1.2:9119 didn’t answer."))
        assertTrue(StatusUiState().offlineCaption.startsWith("Hermes didn’t answer."))
    }

    @Test
    fun stoppingPlatformsJoinWithCommasAndAnd() {
        fun with(vararg names: String) = StatusUiState(
            hermes = HermesState(status = running().copy(gatewayPlatforms = names.associateWith { JsonPrimitive("connected") })),
        ).stoppingPlatforms
        assertEquals("Messaging platforms", with())
        assertEquals("Telegram", with("telegram"))
        assertEquals("Telegram and Discord", with("telegram", "discord"))
        assertEquals("Telegram, Discord and Slack", with("telegram", "discord", "slack"))
    }
}
