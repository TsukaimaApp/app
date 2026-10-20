package labs.suchattai.tsukaima.ui.logs

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import labs.suchattai.tsukaima.data.api.ApiException
import labs.suchattai.tsukaima.data.api.LogsDto
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LogsViewModelTest {
    private class FakeSource : LogsSource {
        val calls = mutableListOf<Triple<String, Int, String?>>()
        var result: Result<LogsDto> = Result.success(LogsDto("gateway", listOf("a\n", "b\n")))
        var gate: CompletableDeferred<Unit>? = null

        override suspend fun logs(file: String, lines: Int, level: String?): Result<LogsDto> {
            calls += Triple(file, lines, level)
            gate?.await()
            return result
        }
    }

    private val created = mutableListOf<LogsViewModel>()

    @Before fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    @After
    fun tearDown() {
        runBlocking { created.forEach { it.viewModelScope.coroutineContext[Job]?.cancelAndJoin() } }
        Dispatchers.resetMain()
    }

    private fun vm(src: LogsSource) = LogsViewModel(src).also { created += it }

    @Test
    fun logsErrorShowsDetail() = runTest {
        val src = FakeSource().apply { result = Result.failure(ApiException.fromResponse(400, """{"detail":"Unknown log file: 'x'"}""")) }
        val vm = vm(src)
        advanceUntilIdle()
        assertEquals("Couldn't load logs: Unknown log file: 'x'", vm.state.value.error)

        src.result = Result.failure(ApiException.fromResponse(502, "Bad Gateway"))
        vm.setLevel(LEVEL_WARN)
        advanceUntilIdle()
        assertEquals("Couldn't load logs: HTTP 502: Bad Gateway", vm.state.value.error)
    }

    @Test
    fun switchingFileRefetches() = runTest {
        val src = FakeSource()
        val vm = vm(src)
        advanceUntilIdle()
        assertEquals(Triple("gateway", 300, null), src.calls.single())
        assertEquals("a\nb", vm.state.value.text)
        vm.setFile("agent")
        advanceUntilIdle()
        assertEquals(Triple("agent", 300, null), src.calls.last())
        assertEquals("agent", vm.state.value.file)
        assertEquals(2, src.calls.size)
    }

    @Test
    fun warnLevelMapsToWARNING() = runTest {
        val src = FakeSource()
        val vm = vm(src)
        advanceUntilIdle()
        vm.setLevel("WARNING")
        advanceUntilIdle()
        assertEquals("WARNING", src.calls.last().third)
        vm.setLevel(null)
        advanceUntilIdle()
        assertNull(src.calls.last().third)
    }

    @Test
    fun refreshSkippedWhileInFlight() = runTest {
        val src = FakeSource().apply { gate = CompletableDeferred() }
        val vm = vm(src)
        advanceUntilIdle()
        vm.refresh()
        vm.refresh()
        advanceUntilIdle()
        assertEquals(1, src.calls.size)
        assertTrue(vm.state.value.loading)
        src.gate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(false, vm.state.value.loading)
        vm.refresh()
        advanceUntilIdle()
        assertEquals(2, src.calls.size)
    }

    @Test
    fun errorKeepsPreviousLines() = runTest {
        val src = FakeSource()
        val vm = vm(src)
        advanceUntilIdle()
        src.result = Result.failure(ApiException(400, "Unknown log file: bogus"))
        vm.refresh()
        advanceUntilIdle()
        assertEquals(listOf("a\n", "b\n"), vm.state.value.lines)
        assertEquals("Couldn't load logs: Unknown log file: bogus", vm.state.value.error)
        src.result = Result.success(LogsDto("gateway", listOf("c\n")))
        vm.refresh()
        advanceUntilIdle()
        assertNull(vm.state.value.error)
        assertEquals("c", vm.state.value.text)
    }
}
