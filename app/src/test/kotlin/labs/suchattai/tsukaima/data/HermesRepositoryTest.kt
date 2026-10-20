package labs.suchattai.tsukaima.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import labs.suchattai.tsukaima.data.api.AuthException
import labs.suchattai.tsukaima.data.api.HermesApi
import labs.suchattai.tsukaima.data.gateway.GatewayClient
import labs.suchattai.tsukaima.data.prefs.InMemoryCredentialStore
import labs.suchattai.tsukaima.data.prefs.Settings
import labs.suchattai.tsukaima.data.prefs.SettingsStore
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
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

class HermesRepositoryTest {
    @get:Rule
    val tempDir = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var scope: CoroutineScope
    private lateinit var store: SettingsStore
    private val okHttp = OkHttpClient()
    private val creds = InMemoryCredentialStore()
    private val snapshot = MutableStateFlow(Settings())
    private lateinit var connection: ConnectionManager
    private lateinit var repo: HermesRepository

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
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
        scope.cancel()
        server.close()
    }

    private fun serverUrl() = server.url("/").toString().trimEnd('/')

    @Test
    fun signInWithBadCredentialsFailsWithAuthExceptionAndPersistsNothing() = runBlocking<Unit> {
        server.enqueue(MockResponse.Builder().code(401).build())
        val result = repo.signIn(serverUrl(), "u", "wrong")
        assertTrue(result.exceptionOrNull() is AuthException)
        assertNull(creds.password())
        assertEquals("", store.settings.first().serverUrl)
        assertFalse(repo.isConfigured.first())
    }

    @Test
    fun signInPersistsSettingsAndCredsAndClearsAuthRequired() = runBlocking<Unit> {
        server.enqueue(
            MockResponse.Builder().code(200).addHeader("Set-Cookie", "hermes_session_at=tok1; Path=/").body("{}").build(),
        )
        connection.reportAuthFailure()
        val result = repo.signIn(serverUrl() + "/some/path/", "alice", "pw")
        assertTrue(result.toString(), result.isSuccess)

        val s = store.settings.first()
        assertEquals(serverUrl(), s.serverUrl)
        assertEquals("alice", s.username)
        assertTrue(s.onboarded)
        assertEquals("pw", creds.password())
        assertEquals("tok1", creds.accessToken())
        assertFalse(connection.authRequired.value)
        assertTrue(withTimeout(5_000) { repo.isConfigured.first { it } })

        repo.signOut()
        assertNull(creds.password())
        assertFalse(withTimeout(5_000) { repo.isConfigured.first { !it } })
    }

    @Test
    fun signInRejectsInvalidUrl() = runBlocking<Unit> {
        val result = repo.signIn("   ", "u", "p")
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun passThroughNonIoFailureBecomesResultFailure() = runBlocking<Unit> {
        snapshot.value = Settings(serverUrl = "not a url")
        val result = repo.sessions()
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun passThroughAuthFailureFlagsAuthRequired() = runBlocking<Unit> {
        snapshot.value = Settings(serverUrl = serverUrl())
        server.enqueue(MockResponse.Builder().code(401).build())
        val result = repo.cronJobs()
        assertTrue(result.exceptionOrNull() is AuthException)
        assertTrue(connection.authRequired.value)
    }

    @Test
    fun passThroughSuccessReturnsValue() = runBlocking<Unit> {
        snapshot.value = Settings(serverUrl = serverUrl())
        server.enqueue(MockResponse.Builder().addHeader("Content-Type", "application/json").body("""{"active":"work"}""").build())
        assertEquals("work", repo.activeProfile().getOrThrow())
    }
}
