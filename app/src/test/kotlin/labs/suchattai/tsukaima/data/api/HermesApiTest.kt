package labs.suchattai.tsukaima.data.api

import kotlinx.coroutines.runBlocking
import labs.suchattai.tsukaima.data.prefs.Settings
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class HermesApiTest {
    private lateinit var server: MockWebServer
    private var settings = Settings()

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun api() = HermesApi(
        client = OkHttpClient(),
        baseUrl = { server.url("/").toString().trimEnd('/') },
        settings = { settings },
    )

    private fun json(body: String, code: Int = 200) = server.enqueue(
        MockResponse.Builder()
            .code(code)
            .addHeader("Content-Type", "application/json")
            .body(body)
            .build(),
    )

    @Test
    fun loginReturnsCookieToken() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .addHeader("Set-Cookie", "hermes_session_at=abc; Path=/; HttpOnly")
                .addHeader("Set-Cookie", "hermes_session_rt=zzz; Path=/; HttpOnly")
                .body("""{"ok":true,"next":"/"}""")
                .build(),
        )
        assertEquals("abc", api().login("u", "p"))
        val recorded = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("POST", recorded.method)
        assertEquals("/auth/password-login", recorded.url.encodedPath)
        assertEquals("""{"provider":"basic","username":"u","password":"p"}""", recorded.body!!.utf8())
    }

    @Test
    fun loginBadCredentialsThrowsAuthException() {
        server.enqueue(MockResponse.Builder().code(401).body("""{"detail":"bad"}""").build())
        assertThrows(AuthException::class.java) { runBlocking { api().login("u", "p") } }
    }

    @Test
    fun statusParsesGatewayState() = runBlocking {
        json(
            """{"version":"0.22.2","gateway_running":true,"gateway_state":"running",
            |"gateway_platforms":{"telegram":{"state":"connected"}},"active_sessions":0,"active_agents":0,
            |"profiles":["default"],"memory":{"pressure":"ok"},
            |"disk":{"total_mb":32768,"free_mb":23161,"used_percent":10.4},"overall":"ok","unknown_key":1}""".trimMargin(),
        )
        val status = api().status()
        assertEquals("running", status.gatewayState)
        assertEquals(10.4, status.disk!!.usedPercent!!, 0.0)
        assertEquals(true, status.gatewayRunning)
        assertEquals("/api/status", server.takeRequest(1, TimeUnit.SECONDS)!!.target)
    }

    @Test
    fun cronJobsParseSchedule() = runBlocking {
        json(
            """[{"id":"j1","name":"Morning","prompt":"hi","schedule":{"kind":"cron","expr":"0 9 * * *","display":"0 9 * * *"},
            |"schedule_display":"0 9 * * *","enabled":true,"state":"scheduled",
            |"next_run_at":"2026-10-20T09:00:00+02:00","last_run_at":null,"last_status":null,"deliver":"local"}]""".trimMargin(),
        )
        val job = api().cronJobs().single()
        assertEquals("0 9 * * *", job.schedule!!.expr)
        assertEquals("2026-10-20T09:00:00+02:00", job.nextRunAt)
        assertNull(job.lastRunAt)
    }

    @Test
    fun profileQueryAppendedWhenNotDefault() = runBlocking {
        settings = Settings(profile = "ops")
        json("[]")
        api().cronJobs()
        assertEquals("/api/cron/jobs?profile=ops", server.takeRequest(1, TimeUnit.SECONDS)!!.target)
    }

    @Test
    fun profileQueryAppendedWithAmpersandWhenQueryExists() = runBlocking {
        settings = Settings(profile = "ops")
        json("""{"sessions":[]}""")
        api().sessions(limit = 5)
        assertEquals("/api/sessions?limit=5&profile=ops", server.takeRequest(1, TimeUnit.SECONDS)!!.target)
    }

    @Test
    fun profileQueryOmittedForDefaultAndForAuthEndpoints() = runBlocking {
        json("[]")
        api().cronJobs()
        assertEquals("/api/cron/jobs", server.takeRequest(1, TimeUnit.SECONDS)!!.target)

        settings = Settings(profile = "ops")
        json("""{"ok":true,"version":"1","auth_required":true}""")
        api().health()
        assertEquals("/api/health", server.takeRequest(1, TimeUnit.SECONDS)!!.target)
        json("""{"user_id":"u","provider":"basic","expires_at":5}""")
        assertEquals(5L, api().me().expiresAt)
        assertEquals("/api/auth/me", server.takeRequest(1, TimeUnit.SECONDS)!!.target)
    }

    @Test
    fun non2xxThrowsApiException() {
        server.enqueue(MockResponse.Builder().code(500).body("boom").build())
        val e = assertThrows(ApiException::class.java) { runBlocking { api().cronJobs() } }
        assertEquals(500, e.code)
        assertEquals(true, e.message!!.contains("boom"))
    }

    @Test
    fun sessionMutationsUseExpectedMethodsAndBodies() = runBlocking {
        val api = api()
        repeat(3) { json("""{"ok":true}""") }
        api.renameSession("s 1", "New")
        api.archiveSession("s 1", true)
        api.deleteSession("s 1")
        val rename = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("PATCH", rename.method)
        assertEquals("/api/sessions/s%201", rename.target)
        assertEquals("""{"title":"New"}""", rename.body!!.utf8())
        val archive = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("""{"archived":true}""", archive.body!!.utf8())
        assertEquals("DELETE", server.takeRequest(1, TimeUnit.SECONDS)!!.method)
    }

    @Test
    fun cronMutationsUseExpectedPathsAndBodies() = runBlocking {
        val api = api()
        json("""{"id":"j1"}""")
        api.createCronJob("n", "0 9 * * *", "p", "local")
        repeat(5) { json("""{"ok":true}""") }
        api.updateCronJob("j1", kotlinx.serialization.json.buildJsonObject { put("name", kotlinx.serialization.json.JsonPrimitive("x")) })
        api.pauseCronJob("j1")
        api.resumeCronJob("j1")
        api.triggerCronJob("j1")
        api.deleteCronJob("j1")
        val create = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("POST /api/cron/jobs", "${create.method} ${create.target}")
        assertEquals("""{"name":"n","schedule":"0 9 * * *","prompt":"p","deliver":"local"}""", create.body!!.utf8())
        val update = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("PUT /api/cron/jobs/j1", "${update.method} ${update.target}")
        assertEquals("""{"updates":{"name":"x"}}""", update.body!!.utf8())
        assertEquals("POST /api/cron/jobs/j1/pause", server.takeRequest(1, TimeUnit.SECONDS)!!.let { "${it.method} ${it.target}" })
        assertEquals("POST /api/cron/jobs/j1/resume", server.takeRequest(1, TimeUnit.SECONDS)!!.let { "${it.method} ${it.target}" })
        assertEquals("POST /api/cron/jobs/j1/trigger", server.takeRequest(1, TimeUnit.SECONDS)!!.let { "${it.method} ${it.target}" })
        assertEquals("DELETE /api/cron/jobs/j1", server.takeRequest(1, TimeUnit.SECONDS)!!.let { "${it.method} ${it.target}" })
    }

    @Test
    fun simpleResponseShapesParse() = runBlocking {
        val api = api()
        json("""{"providers":[{"name":"basic","display_name":"x"}]}""")
        assertEquals(listOf("basic"), api.authProviders())
        json("""{"ticket":"tk","ttl_seconds":30}""")
        assertEquals("tk", api.wsTicket())
        json("""{"active":"ops"}""")
        assertEquals("ops", api.activeProfile())
        json("""{"targets":[{"id":"local","name":"Local"}]}""")
        assertEquals("local", api.deliveryTargets().single().id)
        json("""{"profiles":[{"name":"default","is_default":true,"gateway_running":true}]}""")
        assertEquals(true, api.profiles().profiles.single().isDefault)
        json("""{"sessions":[{"id":"a","title":"t","started_at":1792451641.5,"message_count":2,"archived":null}]}""")
        val s = api.sessions().single()
        assertEquals(1792451641.5, s.startedAt!!, 0.0)
        assertFalse(s.archived)
        json("""{"file":"agent","lines":["l1"]}""")
        assertEquals(listOf("l1"), api.logs("agent", level = "WARNING").lines)
        assertEquals(
            "/api/logs?file=agent&lines=200&level=WARNING",
            server.takeRequests().last(),
        )
    }

    private fun MockWebServer.takeRequests(): List<String> =
        generateSequence { takeRequest(100, TimeUnit.MILLISECONDS) }.map { it.target }.toList()
}
