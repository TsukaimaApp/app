package labs.suchattai.tsukaima.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import labs.suchattai.tsukaima.data.api.HermesApi
import labs.suchattai.tsukaima.data.api.HermesJson
import labs.suchattai.tsukaima.data.gateway.ConnectionState
import labs.suchattai.tsukaima.data.gateway.GatewayClient
import labs.suchattai.tsukaima.data.prefs.CredentialStore
import labs.suchattai.tsukaima.data.prefs.InMemoryCredentialStore
import labs.suchattai.tsukaima.data.prefs.Settings
import labs.suchattai.tsukaima.domain.chat.AgentStatus
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class ConnectionManagerTest {
    private lateinit var server: MockWebServer
    private lateinit var scope: CoroutineScope
    private val okHttp = OkHttpClient()
    private val creds = InMemoryCredentialStore()
    private lateinit var settings: MutableStateFlow<Settings?>
    private val managers = mutableListOf<ConnectionManager>()
    private val wsUpgrades = AtomicInteger()
    private val statusPolls = AtomicInteger()

    @Volatile private var statusBody = STATUS_RUNNING

    /** Server side of the chat socket: answers capabilities with ready, then pushes one approval request. */
    @Volatile private var wsListener: WebSocketListener? = null

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.url.encodedPath) {
                "/api/status" -> MockResponse.Builder().also { statusPolls.incrementAndGet() }
                    .addHeader("Content-Type", "application/json")
                    .body(statusBody)
                    .build()
                "/api/ws" -> {
                    wsUpgrades.incrementAndGet()
                    wsListener?.let { MockResponse.Builder().webSocketUpgrade(it).build() }
                        ?: MockResponse.Builder().code(404).build()
                }
                else -> MockResponse.Builder().code(404).build()
            }
        }
        server.start()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        settings = MutableStateFlow<Settings?>(Settings(serverUrl = serverUrl(), username = "u"))
    }

    @After
    fun tearDown() {
        managers.forEach { it.stop() }
        scope.cancel()
        server.close()
    }

    private fun serverUrl() = server.url("/").toString().trimEnd('/')

    private val clock = AtomicLong(1_000)

    private fun manager(
        pollMs: Long = 20_000,
        credStore: CredentialStore = creds,
        backgroundPollMs: Long = 60_000,
    ): ConnectionManager {
        val api = HermesApi(okHttp, { settings.value?.serverUrl.orEmpty() }, { settings.value ?: Settings() })
        val gateway = GatewayClient(
            client = okHttp,
            baseUrl = { settings.value?.serverUrl.orEmpty() },
            ticket = { "tk" },
            scope = scope,
            backoffMs = listOf(20L),
        )
        return ConnectionManager(
            api, gateway, settings, credStore, scope, pollMs,
            backgroundPollMs = backgroundPollMs,
            now = { clock.incrementAndGet() },
        )
            .also { managers += it }
    }

    private suspend fun <T> await(block: suspend () -> T): T = withTimeout(TIMEOUT_MS) { block() }

    @Test
    fun pollMarksUnreachableOnIoError() = runBlocking<Unit> {
        val cm = manager()
        cm.refreshNow()
        val seen = cm.hermes.value
        assertTrue(seen.reachable)
        val lastSeen = seen.lastSeenAt
        assertNotNull(lastSeen)

        server.close()
        cm.refreshNow()
        val down = cm.hermes.value
        assertFalse(down.reachable)
        assertEquals(lastSeen, down.lastSeenAt)
        assertEquals("1.0", down.status?.version) // last known status kept
        assertEquals(AgentStatus.Offline, down.gatewayStatus())
    }

    @Test
    fun coldStartDoesNotPublishOfflineBeforeSettingsLoad() = runBlocking<Unit> {
        settings.value = null // DataStore not loaded yet
        val cm = manager() // 20 s poll: only the load itself can trigger the first poll in time
        cm.start()
        delay(200)
        assertTrue("no offline flash before settings load", cm.hermes.value.reachable)
        assertNull(cm.hermes.value.gatewayStatus())
        assertEquals("no poll against default settings", 0, server.requestCount)

        settings.value = Settings(serverUrl = serverUrl(), username = "u")
        val loaded = await { cm.hermes.first { it.status != null } }
        assertTrue(loaded.reachable)
    }

    @Test
    fun emptyServerUrlLeavesReachableDefault() = runBlocking<Unit> {
        settings.value = Settings()
        val cm = manager()
        cm.refreshNow()
        assertTrue(cm.hermes.value.reachable)
        assertNull(cm.hermes.value.gatewayStatus())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun urlChangeWakesPollLoop() = runBlocking<Unit> {
        val other = MockWebServer().apply {
            enqueue(
                MockResponse.Builder().addHeader("Content-Type", "application/json")
                    .body("""{"version":"other","gateway_state":"running"}""").build(),
            )
            start()
        }
        try {
            val cm = manager() // 20 s poll: only the URL change can trigger the second poll in time
            cm.start()
            await { cm.hermes.first { it.status?.version == "1.0" } }
            settings.value = Settings(serverUrl = other.url("/").toString().trimEnd('/'))
            await { cm.hermes.first { it.status?.version == "other" } }
        } finally {
            other.close()
        }
    }

    @Test
    fun stopIsNotUndoneByInFlightPoll() = runBlocking<Unit> {
        wsListener = object : WebSocketListener() {}
        val inSync = CountDownLatch(1)
        val release = CountDownLatch(1)
        // password() is first read by syncGateway, after refreshNow: block there to hold an iteration in flight.
        val blocking = object : CredentialStore by InMemoryCredentialStore() {
            private val first = AtomicBoolean(true)
            override fun password(): String {
                if (first.getAndSet(false)) {
                    inSync.countDown()
                    release.await(5, TimeUnit.SECONDS)
                }
                return "pw"
            }
        }
        val cm = manager(credStore = blocking)
        cm.start()
        assertTrue(inSync.await(5, TimeUnit.SECONDS))
        cm.stop()
        release.countDown()
        delay(300)
        assertEquals(ConnectionState.Disconnected, cm.hermes.value.connection)
        assertEquals("gateway must not connect after stop()", 0, wsUpgrades.get())
    }

    @Test
    fun pollMarksStoppedGateway() = runBlocking<Unit> {
        statusBody = """{"version":"0.9","gateway_running":false,"gateway_state":"stopped"}"""
        val cm = manager(pollMs = 50)
        cm.start()
        val state = await { cm.hermes.first { it.status != null } }
        assertTrue(state.reachable)
        assertEquals("stopped", state.status!!.gatewayState)
        assertTrue(state.gatewayStatus() is AgentStatus.Stopped)
    }

    @Test
    fun runningGatewayLeavesStatusToChat() = runBlocking<Unit> {
        val cm = manager()
        cm.refreshNow()
        assertNull(cm.hermes.value.gatewayStatus())
    }

    @Test
    fun refreshNowUpdatesImmediately() = runBlocking<Unit> {
        val cm = manager()
        assertNull(cm.hermes.value.status)
        statusBody = """{"version":"1.2.3","gateway_state":"running"}"""
        cm.refreshNow()
        val state = cm.hermes.value
        assertTrue(state.reachable)
        assertEquals("1.2.3", state.status?.version)
        assertNotNull(state.lastSeenAt)
    }

    @Test
    fun simulateOfflineForcesUnreachable() = runBlocking<Unit> {
        val cm = manager()
        cm.refreshNow()
        assertTrue(cm.hermes.value.reachable)

        cm.simulateOffline.value = true
        val offline = await { cm.hermes.first { !it.reachable } }
        assertEquals(AgentStatus.Offline, offline.gatewayStatus())
        cm.refreshNow()
        assertFalse("a successful poll must not override simulateOffline", cm.hermes.value.reachable)

        cm.simulateOffline.value = false
        await { cm.hermes.first { it.reachable } }
    }

    @Test
    fun gatewayNotConnectedWhenNoPasswordStored() = runBlocking<Unit> {
        val cm = manager(pollMs = 50)
        cm.start()
        await { cm.hermes.first { it.status != null } }
        delay(200)
        assertEquals(ConnectionState.Disconnected, cm.hermes.value.connection)
        assertEquals(0, wsUpgrades.get())
    }

    @Test
    fun serverRequestsReplayToLateSubscriber() = runBlocking<Unit> {
        wsListener = object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (method(text) == "client.capabilities") {
                    webSocket.send(READY)
                    webSocket.send(
                        """{"jsonrpc":"2.0","id":"srq-1","method":"approval","params":{"command":"ls"}}""",
                    )
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null) // finish the handshake so the server can shut down promptly
            }
        }
        creds.save("pw", "tok", serverUrl())
        val cm = manager()
        cm.start()
        cm.start() // idempotent: still exactly one collector of the gateway's single-consumer flow
        await { cm.hermes.first { it.connection == ConnectionState.Connected } }
        // Wait until the request has been forwarded, then subscribe for the first time.
        await { while (cm.serverRequests.replayCache.isEmpty()) delay(10) }

        val req = await { cm.serverRequests.first() }
        assertEquals("srq-1", req.id)
        assertEquals("approval", req.method)
        assertEquals(1, cm.serverRequests.replayCache.size)
    }

    @Test
    fun approvalsClarifyAndMaskedPromptsForwarded() = runBlocking<Unit> {
        val answers = java.util.concurrent.ConcurrentHashMap<String, kotlinx.serialization.json.JsonObject>()
        wsListener = object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val frame = HermesJson.parseToJsonElement(text).jsonObject
                val id = frame["id"]?.jsonPrimitive?.content
                if (id != null && id.startsWith("srq-")) answers[id] = frame
                if (method(text) == "client.capabilities") {
                    webSocket.send(READY)
                    for ((rid, m) in listOf("srq-c" to "clarify", "srq-p" to "preview.read", "srq-s" to "secret", "srq-v" to "vault.code", "srq-a" to "approval")) {
                        val qs = if (m == "clarify") ""","questions":[{"qid":"q1","question":"Colour?"}]""" else ""
                        webSocket.send("""{"jsonrpc":"2.0","id":"$rid","method":"$m","params":{"session_id":"live-1"$qs}}""")
                    }
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }
        }
        creds.save("pw", "tok", serverUrl())
        val cm = manager()
        cm.start()
        await { while (answers.isEmpty() || cm.serverRequests.replayCache.size < 4) delay(10) }

        assertEquals(listOf("srq-c", "srq-s", "srq-v", "srq-a"), cm.serverRequests.replayCache.map { it.id })
        assertNull("clarify waits for the user", answers["srq-c"])
        assertNull("a secret waits for the masked card", answers["srq-s"])
        assertEquals("4404", answers["srq-p"]!!["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
        assertNull("a vault code waits for the card", answers["srq-v"])
        assertNull("approvals wait for the user", answers["srq-a"])
    }

    @Test
    fun backgroundModeKeepsGatewayAndPollsSlower() = runBlocking<Unit> {
        wsListener = object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (method(text) == "client.capabilities") webSocket.send(READY)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null) // finish the handshake so the server can shut down promptly
            }
        }
        creds.save("pw", "tok", serverUrl())
        val cm = manager(pollMs = 50, backgroundPollMs = 60_000)
        cm.startBackground()
        await { cm.hermes.first { it.connection == ConnectionState.Connected } }
        delay(400)
        assertEquals("background polls every 60 s, not every 50 ms", 1, statusPolls.get())

        cm.start() // back to the foreground: same loop, fast interval, refreshed now
        await { while (statusPolls.get() < 4) delay(20) }
        assertEquals(ConnectionState.Connected, cm.hermes.value.connection)
        assertEquals(1, wsUpgrades.get())
        cm.stop()
        await { cm.hermes.first { it.connection == ConnectionState.Disconnected } }
    }

    @Test
    fun handoffToBackgroundKeepsSocket() = runBlocking<Unit> {
        wsListener = object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (method(text) == "client.capabilities") webSocket.send(READY)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }
        }
        creds.save("pw", "tok", serverUrl())
        val cm = manager(pollMs = 50, backgroundPollMs = 60_000)
        cm.start()
        await { cm.hermes.first { it.connection == ConnectionState.Connected } }
        val states = mutableListOf<ConnectionState>()
        val watch = scope.launch { cm.hermes.collect { states += it.connection } }

        cm.startBackground() // ON_STOP hand-off: same loop, same socket
        delay(300)
        cm.start() // ON_START: back to foreground polling, still the same socket
        delay(300)

        watch.cancel()
        assertTrue("never disconnected: $states", states.all { it == ConnectionState.Connected })
        assertEquals(1, wsUpgrades.get())
        cm.stop()
        await { cm.hermes.first { it.connection == ConnectionState.Disconnected } }
    }

    @Test
    fun authRequiredSetOnFailureAndCleared() {
        val cm = manager()
        assertFalse(cm.authRequired.value)
        cm.reportAuthFailure()
        assertTrue(cm.authRequired.value)
        cm.clearAuthRequired()
        assertFalse(cm.authRequired.value)
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
        const val STATUS_RUNNING = """{"version":"1.0","gateway_running":true,"gateway_state":"running"}"""
        const val READY =
            """{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","session_id":"","payload":{"replay_epoch":1}}}"""

        fun method(text: String): String? =
            runCatching { HermesJson.parseToJsonElement(text).jsonObject["method"]?.jsonPrimitive?.content }.getOrNull()
    }
}
