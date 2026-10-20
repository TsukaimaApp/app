package labs.suchattai.tsukaima.ui.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import labs.suchattai.tsukaima.data.ConnectionManager
import labs.suchattai.tsukaima.data.HermesRepository
import labs.suchattai.tsukaima.data.api.HermesApi
import labs.suchattai.tsukaima.data.gateway.GatewayClient
import labs.suchattai.tsukaima.data.prefs.InMemoryCredentialStore
import labs.suchattai.tsukaima.data.prefs.Settings
import labs.suchattai.tsukaima.data.prefs.SettingsStore
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    @get:Rule
    val tempDir = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var scope: CoroutineScope
    private lateinit var store: SettingsStore
    private lateinit var connection: ConnectionManager
    private lateinit var repo: HermesRepository
    private val creds = InMemoryCredentialStore()
    private val okHttp = OkHttpClient()
    private val snapshot = MutableStateFlow(Settings())

    /** path -> response; unknown paths 404. */
    private val routes = mutableMapOf<String, () -> MockResponse>()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.url.encodedPath
                    return routes[path]?.invoke() ?: MockResponse.Builder().code(404).build()
                }
            }
            start()
        }
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        store = SettingsStore(
            PreferenceDataStoreFactory.create(scope = scope, produceFile = { File(tempDir.root, "s.preferences_pb") }),
        )
        val api = HermesApi(okHttp, { snapshot.value.serverUrl }, { snapshot.value })
        val gateway = GatewayClient(okHttp, { snapshot.value.serverUrl }, { "tk" }, scope)
        connection = ConnectionManager(api, gateway, snapshot, creds, scope)
        repo = HermesRepository(api, store, creds, connection, loginApi = { url -> HermesApi(okHttp, { url }) })
    }

    @After
    fun tearDown() {
        vms.forEach { it.viewModelScope.cancel() } // before resetMain: nothing may resume on Main afterwards
        Dispatchers.resetMain()
        scope.cancel()
        server.close()
    }

    private fun hostPort() = "${server.hostName}:${server.port}"

    private fun json(body: String) = MockResponse.Builder().code(200).body(body).build()

    private fun loginOk() = MockResponse.Builder()
        .code(200).addHeader("Set-Cookie", "hermes_session_at=tok1; Path=/").body("{}").build()

    private val vms = mutableListOf<SettingsViewModel>()

    private fun vm() = SettingsViewModel(repo, store, creds, connection).also { vms += it }

    private suspend fun SettingsViewModel.await(cond: (SettingsUiState) -> Boolean) =
        withTimeout(5_000) { state.first(cond) }

    @Test
    fun signInNormalizesUrlAndPersists() = runBlocking<Unit> {
        routes["/auth/password-login"] = ::loginOk
        val vm = vm()
        vm.setServerUrl("${hostPort()}/")
        vm.setUsername("alice")
        vm.setPassword("pw")

        val result = vm.signIn()

        assertTrue(result.toString(), result.isSuccess)
        val expected = "http://${hostPort()}"
        val s = store.settings.first()
        assertEquals(expected, s.serverUrl)
        assertEquals("alice", s.username)
        assertTrue(s.onboarded)
        assertEquals("pw", creds.password())
        assertEquals(expected, creds.origin())
        val ui = vm.await { it.serverUrl == expected && !it.signingIn }
        assertEquals("", ui.password) // never kept after a successful sign-in
        assertTrue(ui.passwordSaved)
        assertNull(ui.signInError)
    }

    @Test
    fun signInFailureKeepsOldCredentials() = runBlocking<Unit> {
        routes["/auth/password-login"] = ::loginOk
        val vm = vm()
        vm.setServerUrl(hostPort()); vm.setUsername("alice"); vm.setPassword("pw")
        assertTrue(vm.signIn().isSuccess)

        routes["/auth/password-login"] = { MockResponse.Builder().code(401).build() }
        vm.setUsername("bob"); vm.setPassword("wrong")
        val result = vm.signIn()

        assertTrue(result.isFailure)
        assertEquals("pw", creds.password())
        assertEquals("tok1", creds.accessToken())
        assertEquals("alice", store.settings.first().username)
        assertEquals("http://${hostPort()}", store.settings.first().serverUrl)
    }

    @Test
    fun testConnectionReportsVersion() = runBlocking<Unit> {
        routes["/api/health"] = { json("""{"ok":true,"version":"0.9.2"}""") }
        val vm = vm()
        vm.setServerUrl(hostPort())
        vm.testConnection()
        assertEquals("Hermes 0.9.2", vm.await { it.testResult != null && !it.testing }.testResult)
    }

    @Test
    fun setProfilePersists() = runBlocking<Unit> {
        val vm = vm()
        vm.setProfile("research")
        assertEquals("research", withTimeout(5_000) { store.settings.first { it.profile == "research" } }.profile)
        assertEquals("research", vm.await { it.profile == "research" }.profile)
    }

    @Test
    fun editedUrlWithoutSignInShowsHint() = runBlocking<Unit> {
        store.update { it.copy(serverUrl = "http://old.example:9119", username = "alice", onboarded = true) }
        val vm = vm()
        vm.await { it.serverUrl == "http://old.example:9119" }
        assertFalse(vm.state.value.urlChangedWithoutSignIn)

        vm.setServerUrl("new.example:9119")
        assertTrue(vm.state.value.urlChangedWithoutSignIn)

        vm.setServerUrl("http://old.example:9119/") // normalizes back to the stored origin
        assertFalse(vm.state.value.urlChangedWithoutSignIn)
    }

    @Test
    fun authFailureMessageShownInline() = runBlocking<Unit> {
        routes["/auth/password-login"] = { MockResponse.Builder().code(401).build() }
        val vm = vm()
        vm.setServerUrl(hostPort()); vm.setUsername("alice"); vm.setPassword("nope")

        assertTrue(vm.signIn().isFailure)

        val err = vm.await { it.signInError != null }.signInError
        assertEquals("Invalid username or password (401)", err)
        assertEquals("nope", vm.state.value.password) // kept so the user can fix a typo
    }

    @Test
    fun unreachableServerShowsCantReach() = runBlocking<Unit> {
        val vm = vm()
        val url = hostPort()
        server.close()
        vm.setServerUrl(url); vm.setUsername("a"); vm.setPassword("b")
        assertTrue(vm.signIn().isFailure)
        assertEquals("Can't reach Hermes at http://$url", vm.state.value.signInError)
    }

    @Test
    fun simulateOfflineFeedsConnectionManager() = runBlocking<Unit> {
        val vm = vm()
        vm.setSimulateOffline(true)
        assertTrue(connection.simulateOffline.value)
        assertTrue(vm.await { it.simulateOffline }.simulateOffline)
    }

    @Test
    fun usageSummaryFormatsTotals() {
        val totals = JsonObject(
            mapOf(
                "total_input" to JsonPrimitive(1_000_000),
                "total_output" to JsonPrimitive(234_000),
                "total_sessions" to JsonPrimitive(318),
                "total_estimated_cost" to JsonPrimitive(4.5),
            ),
        )
        assertEquals("1.2M tokens · 318 sessions · \$4.50", usageSummary(totals))
        assertNull(usageSummary(null))
    }
}
