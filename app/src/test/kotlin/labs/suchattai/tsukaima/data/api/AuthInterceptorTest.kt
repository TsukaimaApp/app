package labs.suchattai.tsukaima.data.api

import labs.suchattai.tsukaima.data.prefs.InMemoryCredentialStore
import labs.suchattai.tsukaima.data.prefs.Settings
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class AuthInterceptorTest {
    private lateinit var server: MockWebServer
    private val creds = InMemoryCredentialStore()
    private val loginCalls = AtomicInteger()
    private var expectedPassword = "pw"
    private var loginResult: () -> String = { "new-token" }

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun baseUrl() = server.url("/").toString().trimEnd('/')

    private fun client(): OkHttpClient {
        val settings = { Settings(serverUrl = baseUrl(), username = "u") }
        val interceptor = AuthInterceptor(creds, settings) { url, user, password ->
            loginCalls.incrementAndGet()
            assertEquals(baseUrl(), url)
            assertEquals("u", user)
            assertEquals(expectedPassword, password)
            loginResult()
        }
        return OkHttpClient.Builder().addInterceptor(interceptor).build()
    }

    private fun get(client: OkHttpClient, path: String = "/api/sessions", tagged: Boolean = false): Int {
        val builder = Request.Builder().url(baseUrl() + path)
        if (tagged) builder.tag(NoAuth::class.java, NoAuth())
        return client.newCall(builder.build()).execute().use { it.code }
    }

    private fun ok(code: Int = 200) = MockResponse.Builder().code(code).body("{}").build()

    @Test
    fun addsBearerHeader() {
        creds.save("pw", "tok")
        server.enqueue(ok())
        assertEquals(200, get(client()))
        assertEquals("Bearer tok", server.takeRequest(1, TimeUnit.SECONDS)!!.headers["Authorization"])
    }

    @Test
    fun noHeaderWhenNoToken() {
        server.enqueue(ok())
        get(client())
        assertNull(server.takeRequest(1, TimeUnit.SECONDS)!!.headers["Authorization"])
    }

    @Test
    fun reloginStoresNewTokenAndRetries() {
        creds.save("pw", "old")
        server.enqueue(ok(401))
        server.enqueue(ok())
        assertEquals(200, get(client()))
        assertEquals(1, loginCalls.get())
        assertEquals(2, server.requestCount)
        assertEquals("Bearer old", server.takeRequest(1, TimeUnit.SECONDS)!!.headers["Authorization"])
        assertEquals("Bearer new-token", server.takeRequest(1, TimeUnit.SECONDS)!!.headers["Authorization"])
        assertEquals("new-token", creds.accessToken())
        assertEquals("pw", creds.password())
    }

    @Test
    fun reloginsOnceOn401() {
        // The login lambda performs a real HTTP request (NoAuth-tagged): 401, login, retry = 3 requests.
        creds.save("pw", "old")
        val settings = { Settings(serverUrl = baseUrl(), username = "u") }
        lateinit var client: OkHttpClient
        val interceptor = AuthInterceptor(creds, settings) { url, user, password ->
            val req = Request.Builder().url("$url/auth/password-login").post("$user:$password".toRequestBody()).tag(NoAuth::class.java, NoAuth()).build()
            client.newCall(req).execute().use { r ->
                if (r.code == 401) throw AuthException()
                "relogged"
            }
        }
        client = OkHttpClient.Builder().addInterceptor(interceptor).build()
        server.enqueue(ok(401))
        server.enqueue(ok()) // login
        server.enqueue(ok())
        assertEquals(200, get(client))
        assertEquals(3, server.requestCount)
        assertEquals("Bearer old", server.takeRequest(1, TimeUnit.SECONDS)!!.headers["Authorization"])
        val login = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("/auth/password-login", login.url.encodedPath)
        assertNull(login.headers["Authorization"])
        assertEquals("Bearer relogged", server.takeRequest(1, TimeUnit.SECONDS)!!.headers["Authorization"])
    }

    @Test
    fun secondFailureThrowsAuthException() {
        creds.save("pw", "old")
        loginResult = { throw AuthException() }
        server.enqueue(ok(401))
        assertThrows(AuthException::class.java) { get(client()) }
        assertEquals(1, loginCalls.get())
    }

    @Test
    fun retryStill401ThrowsAuthException() {
        creds.save("pw", "old")
        server.enqueue(ok(401))
        server.enqueue(ok(401))
        assertThrows(AuthException::class.java) { get(client()) }
        assertEquals(1, loginCalls.get())
        assertEquals(2, server.requestCount)
    }

    @Test
    fun noPasswordThrowsAuthExceptionWithoutLogin() {
        creds.save(null, "old")
        server.enqueue(ok(401))
        assertThrows(AuthException::class.java) { get(client()) }
        assertEquals(0, loginCalls.get())
        assertEquals(1, server.requestCount)
    }

    @Test
    fun noAuthTaggedRequestsSkipBearerAndRetry() {
        creds.save("pw", "tok")
        server.enqueue(ok(401))
        assertEquals(401, get(client(), "/api/status", tagged = true))
        assertEquals(0, loginCalls.get())
        assertEquals(1, server.requestCount)
        assertNull(server.takeRequest(1, TimeUnit.SECONDS)!!.headers["Authorization"])
    }

    @Test
    fun concurrentRequestsShareOneRelogin() {
        creds.save("pw", "old")
        val bothArrived = CountDownLatch(2)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return when (request.headers["Authorization"]) {
                    "Bearer old" -> {
                        // Hold both initial requests until both are in flight, then reject them.
                        bothArrived.countDown()
                        bothArrived.await(5, TimeUnit.SECONDS)
                        ok(401)
                    }
                    "Bearer new-token" -> ok()
                    else -> ok(500)
                }
            }
        }
        val client = client()
        val pool = Executors.newFixedThreadPool(2)
        try {
            val a = pool.submit<Int> { get(client, "/api/sessions") }
            val b = pool.submit<Int> { get(client, "/api/cron/jobs") }
            assertEquals(200, a.get(10, TimeUnit.SECONDS))
            assertEquals(200, b.get(10, TimeUnit.SECONDS))
        } finally {
            pool.shutdownNow()
        }
        assertEquals(1, loginCalls.get())
        assertEquals(4, server.requestCount)
    }

    @Test
    fun failedReloginIsNotRetriedByWaiters() {
        creds.save("pw", "old")
        loginResult = { throw AuthException() }
        val arrived = CountDownLatch(3)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                arrived.countDown()
                arrived.await(5, TimeUnit.SECONDS)
                return ok(401)
            }
        }
        val client = client()
        val pool = Executors.newFixedThreadPool(3)
        try {
            val calls = (1..3).map { pool.submit<Int> { get(client, "/api/p$it") } }
            calls.forEach { f ->
                val e = assertThrows(java.util.concurrent.ExecutionException::class.java) { f.get(10, TimeUnit.SECONDS) }
                assertEquals(AuthException::class.java, e.cause!!.javaClass)
            }
        } finally {
            pool.shutdownNow()
        }
        assertEquals(1, loginCalls.get())
        // A later request also fails fast, without another login.
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = ok(401)
        }
        assertThrows(AuthException::class.java) { get(client) }
        assertEquals(1, loginCalls.get())
    }

    @Test
    fun newPasswordClearsFailureLatch() {
        creds.save("pw", "old")
        loginResult = { throw AuthException() }
        server.enqueue(ok(401))
        val client = client()
        assertThrows(AuthException::class.java) { get(client) }
        assertEquals(1, loginCalls.get())

        loginResult = { "fresh" }
        creds.save("pw", "old") // identical credentials: still latched
        server.enqueue(ok(401))
        assertThrows(AuthException::class.java) { get(client) }
        assertEquals(1, loginCalls.get())

        expectedPassword = "pw2"
        creds.save("pw2", "old")
        server.enqueue(ok(401))
        server.enqueue(ok())
        assertEquals(200, get(client))
        assertEquals(2, loginCalls.get())
        assertEquals("fresh", creds.accessToken())
    }
}
