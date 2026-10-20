package labs.suchattai.tsukaima.ui.cron

import androidx.lifecycle.viewModelScope
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import labs.suchattai.tsukaima.data.api.CronJobDto
import labs.suchattai.tsukaima.data.api.CronScheduleDto
import labs.suchattai.tsukaima.data.api.DeliveryTargetDto
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CronViewModelTest {
    private fun job(id: String, name: String = "Job $id", paused: Boolean = false) = CronJobDto(
        id = id,
        name = name,
        prompt = "do $id",
        schedule = CronScheduleDto(kind = "cron", expr = "0 9 * * *", display = "0 9 * * *"),
        enabled = !paused,
        state = if (paused) "paused" else "scheduled",
        deliver = JsonPrimitive("local"),
    )

    private class FakeSource : CronSource {
        var jobs = listOf<CronJobDto>()
        var targets = listOf(DeliveryTargetDto("local", "Local (save only)"), DeliveryTargetDto("bot-chat:default", "Bot Chat (default)"))
        val calls = mutableListOf<String>()
        var updates: JsonObject? = null
        var failNext = false

        private fun <T> res(v: T, call: String): Result<T> {
            calls += call
            return if (failNext) { failNext = false; Result.failure(IllegalStateException("boom")) } else Result.success(v)
        }
        override suspend fun jobs() = Result.success(jobs).also { calls += "jobs" }
        override suspend fun deliveryTargets() = Result.success(targets)
        override suspend fun create(name: String, schedule: String, prompt: String, deliver: String) =
            res(CronJobDto(id = "new"), "create $name|$schedule|$prompt|$deliver")
        override suspend fun update(id: String, updates: JsonObject) = res(Unit, "update $id").also { this.updates = updates }
        override suspend fun pause(id: String) = res(Unit, "pause $id")
        override suspend fun resume(id: String) = res(Unit, "resume $id")
        override suspend fun trigger(id: String) = res(Unit, "trigger $id")
        override suspend fun delete(id: String) = res(Unit, "delete $id")
    }

    private val created = mutableListOf<CronViewModel>()
    private val toasts = mutableListOf<String>()

    @Before fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    @After
    fun tearDown() {
        runBlocking { created.forEach { it.viewModelScope.coroutineContext[Job]?.cancelAndJoin() } }
        Dispatchers.resetMain()
    }

    private fun vm(src: CronSource) = CronViewModel(src) { toasts += it }.also { created += it }

    @Test
    fun loadsJobsAndTargets() = runTest {
        val src = FakeSource().apply { jobs = listOf(job("a"), job("b", paused = true)) }
        val vm = vm(src)
        advanceUntilIdle()
        assertEquals(2, vm.state.value.jobs.size)
        assertEquals(2, vm.state.value.targets.size)
        assertFalse(vm.state.value.loading)
        assertFalse(vm.state.value.jobs[0].isPaused)
        assertTrue(vm.state.value.jobs[1].isPaused)
    }

    @Test
    fun saveDisabledUntilNameAndPrompt() = runTest {
        val vm = vm(FakeSource())
        advanceUntilIdle()
        vm.startEdit(null)
        assertFalse(vm.state.value.canSave)
        vm.updateDraft(name = "Backup")
        assertFalse(vm.state.value.canSave)
        vm.updateDraft(prompt = "   ")
        assertFalse(vm.state.value.canSave)
        vm.updateDraft(prompt = "Run it")
        assertTrue(vm.state.value.canSave)
    }

    @Test
    fun saveNewCallsCreate() = runTest {
        val src = FakeSource()
        val vm = vm(src)
        advanceUntilIdle()
        vm.startEdit(null)
        assertTrue(vm.state.value.isNew)
        assertTrue(vm.state.value.editorOpen)
        vm.updateDraft(name = "Backup", prompt = "Run it", schedule = "0 3 * * *", deliver = "bot-chat:default")
        vm.save()
        advanceUntilIdle()
        assertTrue(src.calls.contains("create Backup|0 3 * * *|Run it|bot-chat:default"))
        assertFalse(vm.state.value.editorOpen)
        assertNull(vm.state.value.editing)
    }

    @Test
    fun saveExistingCallsUpdateWithUpdatesObject() = runTest {
        val src = FakeSource().apply { jobs = listOf(job("a")) }
        val vm = vm(src)
        advanceUntilIdle()
        vm.startEdit(src.jobs[0])
        assertFalse(vm.state.value.isNew)
        assertEquals("Job a", vm.state.value.draft.name)
        assertEquals("0 9 * * *", vm.state.value.draft.schedule)
        assertEquals("local", vm.state.value.draft.deliver)
        vm.updateDraft(name = "Renamed", schedule = "*/5 * * * *")
        vm.save()
        advanceUntilIdle()
        assertTrue(src.calls.contains("update a"))
        val u = src.updates!!
        assertEquals(setOf("name", "schedule"), u.keys)
        assertEquals(JsonPrimitive("Renamed"), u["name"])
        assertEquals(JsonPrimitive("*/5 * * * *"), u["schedule"])
        assertFalse(vm.state.value.editorOpen)
    }

    @Test
    fun unchangedScheduleOfAnOldJobIsNotValidated() = runTest {
        // A job without a schedule kind carries a display string; it must still be renameable.
        val old = job("o").copy(schedule = CronScheduleDto(kind = null, expr = null, display = "every 30m"), deliver = null)
        val src = FakeSource().apply { jobs = listOf(old) }
        val vm = vm(src)
        advanceUntilIdle()
        vm.startEdit(old)
        assertTrue(vm.state.value.scheduleEditable)
        assertNull(vm.state.value.scheduleError)
        assertTrue(vm.state.value.canSave)
        vm.updateDraft(schedule = "+5 * * * *")
        assertNotNull("a changed schedule is validated", vm.state.value.scheduleError)
        assertFalse(vm.state.value.canSave)
    }

    @Test
    fun intervalJobEditableWithoutTouchingSchedule() = runTest {
        val interval = job("i").copy(schedule = CronScheduleDto(kind = "interval", expr = null, display = "every 60m"), deliver = null)
        val src = FakeSource().apply { jobs = listOf(interval) }
        val vm = vm(src)
        advanceUntilIdle()
        vm.startEdit(interval)
        assertFalse(vm.state.value.scheduleEditable)
        assertNull(vm.state.value.scheduleError)
        assertTrue(vm.state.value.canSave)
        vm.updateDraft(name = "Renamed")
        vm.save()
        advanceUntilIdle()
        assertEquals(JsonObject(mapOf("name" to JsonPrimitive("Renamed"))), src.updates)
    }

    @Test
    fun unchangedSaveSendsNothing() = runTest {
        val src = FakeSource().apply { jobs = listOf(job("a")) }
        val vm = vm(src)
        advanceUntilIdle()
        vm.startEdit(src.jobs[0])
        vm.save()
        advanceUntilIdle()
        assertTrue(src.calls.none { it.startsWith("update") })
        assertFalse(vm.state.value.editorOpen)
    }

    @Test
    fun scheduleWithWrongFieldCountShowsError() = runTest {
        val src = FakeSource()
        val vm = vm(src)
        advanceUntilIdle()
        vm.startEdit(null)
        vm.updateDraft(name = "X", prompt = "Y", schedule = "0 9 * *")
        assertEquals("Use 5 fields: minute hour day month weekday", vm.state.value.scheduleError)
        assertFalse(vm.state.value.canSave)
        vm.save()
        advanceUntilIdle()
        assertTrue(src.calls.none { it.startsWith("create") })
        vm.updateDraft(schedule = "  0   9 * * 1-5 ")
        assertNull(vm.state.value.scheduleError)
        assertTrue(vm.state.value.canSave)
    }

    @Test
    fun scheduleFieldsMustBeCronTokens() {
        assertEquals(SCHEDULE_FIELD_ERROR, cronScheduleError("+5 * * * *"))
        assertEquals(SCHEDULE_FIELD_ERROR, cronScheduleError("0 9 * * mon;rm"))
        assertEquals(SCHEDULE_ERROR, cronScheduleError("+5 * * *"))
        assertEquals(SCHEDULE_ERROR, cronScheduleError("0 0 9 * * *")) // six fields (seconds) stay out
        for (ok in listOf("*/5 * * * *", "0 9 * * 1-5", "0 9 1,15 * MON-FRI", "0 0 L * ?", "0 9 * JAN 1#2")) {
            assertNull(ok, cronScheduleError(ok))
        }
    }

    @Test
    fun failedSaveKeepsEditorOpenAndToasts() = runTest {
        val src = FakeSource()
        val vm = vm(src)
        advanceUntilIdle()
        vm.startEdit(null)
        vm.updateDraft(name = "X", prompt = "Y")
        src.failNext = true
        vm.save()
        advanceUntilIdle()
        assertTrue(vm.state.value.editorOpen)
        assertFalse(vm.state.value.saving)
        assertEquals(1, toasts.size)
    }

    @Test
    fun pauseThenRefresh() = runTest {
        val src = FakeSource().apply { jobs = listOf(job("a")) }
        val vm = vm(src)
        advanceUntilIdle()
        src.calls.clear()
        vm.pause("a")
        advanceUntilIdle()
        assertEquals(listOf("pause a", "jobs"), src.calls)
    }

    @Test
    fun resumeThenRefresh() = runTest {
        val src = FakeSource().apply { jobs = listOf(job("a", paused = true)) }
        val vm = vm(src)
        advanceUntilIdle()
        src.calls.clear()
        vm.resume("a")
        advanceUntilIdle()
        assertEquals(listOf("resume a", "jobs"), src.calls)
    }

    @Test
    fun runNowToastsJobName() = runTest {
        val src = FakeSource().apply { jobs = listOf(job("a", "Nightly")) }
        val vm = vm(src)
        advanceUntilIdle()
        vm.runNow("a")
        advanceUntilIdle()
        assertTrue(src.calls.contains("trigger a"))
        assertEquals(listOf("Running Nightly"), toasts)
    }

    @Test
    fun deleteThenRefresh() = runTest {
        val src = FakeSource().apply { jobs = listOf(job("a")) }
        val vm = vm(src)
        advanceUntilIdle()
        src.calls.clear()
        vm.delete("a")
        advanceUntilIdle()
        assertEquals(listOf("delete a", "jobs"), src.calls)
    }

    @Test
    fun emptyTargetsFallBackToSessionOnly() = runTest {
        val src = FakeSource().apply { targets = emptyList() }
        val vm = vm(src)
        advanceUntilIdle()
        assertEquals(listOf(DeliveryTargetDto("local", "Session only")), vm.state.value.deliveryChoices)
        assertNotNull(vm.state.value)
    }
}
