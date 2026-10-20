package labs.suchattai.tsukaima.ui.drawer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import labs.suchattai.tsukaima.data.SessionsSource
import labs.suchattai.tsukaima.data.api.SessionDto
import labs.suchattai.tsukaima.data.prefs.Settings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DrawerViewModelTest {
    private class FakeSessions(var list: List<SessionDto>) : SessionsSource {
        val calls = mutableListOf<String>()
        var failLoads = false
        override suspend fun sessions(): Result<List<SessionDto>> {
            calls += "sessions"
            return if (failLoads) Result.failure(java.io.IOException("down")) else Result.success(list)
        }
        override suspend fun rename(id: String, title: String): Result<Unit> {
            calls += "rename:$id:$title"
            return Result.success(Unit)
        }
        override suspend fun archive(id: String, archived: Boolean): Result<Unit> {
            calls += "archive:$id:$archived"
            return Result.success(Unit)
        }
        override suspend fun delete(id: String): Result<Unit> {
            calls += "delete:$id"
            return Result.success(Unit)
        }
    }

    private val sessions = listOf(
        SessionDto(id = "a", title = "Fix the cron job", preview = "backup failing", profileName = "default"),
        SessionDto(id = "b", title = "Summarize server logs", profile = "ops"),
        SessionDto(id = "c", title = "Paper triage", preview = "arXiv LIST", profileName = "research"),
        SessionDto(id = "d", title = "Old migration notes", profile = "ops", archived = true),
        SessionDto(id = "e", title = "Untitled", archived = true),
        SessionDto(id = "f", title = "Dataset licence check"),
    )

    private val settings = MutableStateFlow<Settings?>(Settings(serverUrl = "http://hermes:9119", onboarded = true))

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun vm(list: List<SessionDto> = sessions) = DrawerViewModel(FakeSessions(list), settings)

    @Test
    fun groupsByProfileExcludingArchived() = runTest {
        val state = vm().state.value
        assertFalse(state.loading)
        assertEquals(
            mapOf(
                "default" to listOf("a", "f"),
                "ops" to listOf("b"),
                "research" to listOf("c"),
            ),
            state.grouped.mapValues { (_, v) -> v.map { it.id } },
        )
        // The default profile always leads; the rest keep server order.
        assertEquals(listOf("default", "ops", "research"), state.grouped.keys.toList())
    }

    @Test
    fun queryFiltersTitleAndPreviewCaseInsensitive() = runTest {
        val vm = vm()
        vm.setQuery("CRON")
        assertEquals(listOf("a"), vm.state.value.grouped.values.flatten().map { it.id })
        vm.setQuery("arxiv")
        assertEquals(listOf("c"), vm.state.value.grouped.values.flatten().map { it.id })
        vm.setQuery("  ")
        assertEquals(4, vm.state.value.grouped.values.flatten().size)
    }

    @Test
    fun archivedCountMatches() = runTest {
        val vm = vm()
        assertEquals(2, vm.state.value.archivedCount)
        vm.setShowArchived(true)
        assertEquals(listOf("d", "e"), vm.state.value.archived.map { it.id })
    }

    @Test
    fun ageLabelFormats() {
        val now = 1_800_000_000_000L
        val nowSec = now / 1000.0
        assertEquals("1m", ageLabel(nowSec - 90, now))
        assertEquals("1h", ageLabel(nowSec - 3700, now))
        assertEquals("2d", ageLabel(nowSec - 2 * 86_400, now))
        assertEquals("now", ageLabel(nowSec - 20, now))
        assertEquals("", ageLabel(null, now))
    }

    @Test
    fun mutationsRefreshAfterwards() = runTest {
        val fake = FakeSessions(sessions)
        val vm = DrawerViewModel(fake, settings)
        fake.calls.clear()
        vm.rename("a", "New title")
        vm.archive("b", true)
        vm.delete("c")
        assertEquals(
            listOf("rename:a:New title", "sessions", "archive:b:true", "sessions", "delete:c", "sessions"),
            fake.calls,
        )
    }

    @Test
    fun deletingOpenSessionReportsNavigation() = runTest {
        val vm = vm()
        val removed = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.openSessionRemoved.collect { removed += it } }
        vm.select("a")
        vm.delete("b") // not the open one
        vm.archive("c", archived = false) // unarchiving never removes
        assertEquals(emptyList<String>(), removed)
        vm.delete("a")
        assertEquals(listOf("a"), removed)
        vm.select("f")
        vm.archive("f", archived = true)
        assertEquals(listOf("a", "f"), removed)
    }

    @Test
    fun repeatedLoadFailuresToastOnce() = runTest {
        val fake = FakeSessions(sessions).apply { failLoads = true }
        var reachable = true
        val vm = DrawerViewModel(fake, settings, isReachable = { reachable })
        val toasts = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.errors.collect { toasts += it } }
        // The init load failed before collection started; the Channel buffered its one toast.
        vm.refresh()
        vm.refresh()
        assertEquals(listOf("Couldn't load sessions"), toasts)

        fake.failLoads = false
        vm.refresh() // success resets
        fake.failLoads = true
        vm.refresh()
        vm.refresh()
        assertEquals(2, toasts.size)

        // Never while Hermes is unreachable: the status line already says so.
        fake.failLoads = false
        vm.refresh()
        reachable = false
        fake.failLoads = true
        vm.refresh()
        assertEquals(2, toasts.size)

        // Mutation failures always toast.
        val failing = object : SessionsSource by fake {
            override suspend fun delete(id: String): Result<Unit> = Result.failure(java.io.IOException("x"))
        }
        val vm2 = DrawerViewModel(failing, settings, isReachable = { false })
        val toasts2 = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm2.errors.collect { toasts2 += it } }
        vm2.delete("a")
        vm2.delete("a")
        assertEquals(listOf("Couldn't delete the session", "Couldn't delete the session"), toasts2)
    }
}
