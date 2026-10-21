package app.tsukaima.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import app.tsukaima.data.api.AuthException
import app.tsukaima.data.api.AuthInterceptor
import app.tsukaima.data.api.HermesApi
import app.tsukaima.data.api.ImageTooLargeException
import app.tsukaima.data.gateway.GatewayClient
import app.tsukaima.data.prefs.InMemoryCredentialStore
import app.tsukaima.data.prefs.Settings
import app.tsukaima.data.prefs.SettingsStore
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

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
        assertEquals("credentials bound to the signed-in origin", serverUrl(), creds.origin())
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

    @Test
    fun sameOriginImage401NeverReLogsInOrRequiresAuth() = runBlocking<Unit> {
        val origin = serverUrl()
        creds.save("pw", "tok", origin)
        snapshot.value = Settings(serverUrl = origin, username = "u")
        var logins = 0
        val authed = okHttp.newBuilder()
            .addInterceptor(AuthInterceptor(creds, { snapshot.value }) { _, _, _ -> logins++; "fresh" })
            .build()
        val api = HermesApi(authed, { snapshot.value.serverUrl }, { snapshot.value }, plainClient = okHttp)
        val images = HermesRepository(api, store, creds, connection, loginApi = { url -> HermesApi(okHttp, { url }) })

        server.enqueue(MockResponse.Builder().code(401).build())
        server.enqueue(MockResponse.Builder().code(401).build())
        val result = images.fetchImage(server.url("/api/files/x.png").toString())

        assertTrue(result.isFailure)
        assertEquals("Bearer tok", server.takeRequest(1, TimeUnit.SECONDS)!!.headers["Authorization"])
        assertEquals("no password re-login for an image", 0, logins)
        assertEquals(1, server.requestCount)
        assertEquals("tok", creds.accessToken())
        assertFalse(connection.authRequired.value)
    }

    @Test
    fun fetchImageUsesAuthOnlyForSameOrigin() = runBlocking<Unit> {
        val foreign = MockWebServer().apply { start() }
        try {
            // The authed client adds a bearer to everything: only routing decides who gets it.
            val authed = okHttp.newBuilder()
                .addInterceptor { chain -> chain.proceed(chain.request().newBuilder().header("Authorization", "Bearer tk").build()) }
                .build()
            val api = HermesApi(authed, { snapshot.value.serverUrl }, { snapshot.value }, plainClient = okHttp)
            val images = HermesRepository(api, store, creds, connection, loginApi = { url -> HermesApi(okHttp, { url }) })
            snapshot.value = Settings(serverUrl = serverUrl())
            val png = byteArrayOf(-119, 80, 78, 71)

            server.enqueue(MockResponse.Builder().addHeader("Content-Type", "image/png").body(Buffer().write(png)).build())
            assertArrayEquals(png, images.fetchImage(server.url("/api/files/x.png").toString()).getOrThrow())
            val own = server.takeRequest(1, TimeUnit.SECONDS)!!
            assertEquals("Bearer tk", own.headers["Authorization"])

            foreign.enqueue(MockResponse.Builder().addHeader("Content-Type", "image/png").body(Buffer().write(png)).build())
            assertArrayEquals(png, images.fetchImage(foreign.url("/cat.png").toString()).getOrThrow())
            val other = foreign.takeRequest(1, TimeUnit.SECONDS)!!
            assertNull(other.headers["Authorization"])

            // A foreign 401 is that host's business: no sign-in prompt.
            foreign.enqueue(MockResponse.Builder().code(401).build())
            assertTrue(images.fetchImage(foreign.url("/private.png").toString()).isFailure)
            assertFalse(connection.authRequired.value)

            // The 8 MB cap, by Content-Length and by what actually arrives.
            val big = Buffer().write(ByteArray((HermesApi.IMAGE_MAX_BYTES + 1).toInt()))
            foreign.enqueue(MockResponse.Builder().body(big.clone()).build())
            assertTrue(images.fetchImage(foreign.url("/big.png").toString()).exceptionOrNull() is ImageTooLargeException)
            foreign.enqueue(MockResponse.Builder().chunkedBody(big, 64 * 1024).build())
            assertTrue(images.fetchImage(foreign.url("/big-chunked.png").toString()).exceptionOrNull() is ImageTooLargeException)

            // Exactly at the cap is fine; not http(s) is no fetch at all.
            foreign.enqueue(MockResponse.Builder().body(Buffer().write(ByteArray(HermesApi.IMAGE_MAX_BYTES.toInt()))).build())
            assertEquals(HermesApi.IMAGE_MAX_BYTES.toInt(), images.fetchImage(foreign.url("/max.png").toString()).getOrThrow().size)
            assertTrue(images.fetchImage("file:///etc/passwd").isFailure)
        } finally {
            foreign.close()
        }
    }
}
