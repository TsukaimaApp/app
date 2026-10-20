package labs.suchattai.tsukaima.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import labs.suchattai.tsukaima.data.api.HermesApi
import labs.suchattai.tsukaima.data.api.HermesJson
import labs.suchattai.tsukaima.data.gateway.ConnectionState
import labs.suchattai.tsukaima.data.gateway.GatewayClient
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
import java.util.concurrent.atomic.AtomicInteger

class ConnectionManagerTest {
    private lateinit var server: MockWebServer
    private lateinit var scope: CoroutineScope
    private val okHttp = OkHttpClient()
    private val creds = InMemoryCredentialStore()
    private lateinit var settings: MutableStateFlow<Settings>
    private val managers = mutableListOf<ConnectionManager>()
    private val wsUpgrades = AtomicInteger()

    @Volatile private var statusBody = STATUS_RUNNING

    /** Server side of the chat socket: answers capabilities with ready, then pushes one approval request. */
    @Volatile private var wsListener: WebSocketListener? = null

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.url.encodedPath) {
                "/api/status" -> MockResponse.Builder()
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
        settings = MutableStateFlow(Settings(serverUrl = serverUrl(), username = "u"))
    }

    @After
    fun tearDown() {
        managers.forEach { it.stop() }
        scope.cancel()
        server.close()
    }

    private fun serverUrl() = server.url("/").toString().trimEnd('/')

    private fun manager(pollMs: Long = 20_000): ConnectionManager {
        val api = HermesApi(okHttp, { settings.value.serverUrl }, { settings.value })
        val gateway = GatewayClient(
            client = okHttp,
            baseUrl = { settings.value.serverUrl },
            ticket = { "tk" },
            scope = scope,
            backoffMs = listOf(20L),
        )
        return ConnectionManager(api, gateway, settings, creds, scope, pollMs).also { managers += it }
    }

    private suspend fun <T> await(block: suspend () -> T): T = withTimeout(TIMEOUT_MS) { block() }

    @Test
    fun pollMarksUnreachableOnIoError() = runBlocking<Unit> {
        val cm = manager(pollMs = 50)
        cm.start()
        val seen = await { cm.hermes.first { it.reachable && it.lastSeenAt != null } }
        val lastSeen = seen.lastSeenAt

        server.close()
        val down = await { cm.hermes.first { !it.reachable } }
        assertEquals(lastSeen, down.lastSeenAt)
        assertEquals(AgentStatus.Offline, down.gatewayStatus())
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
        }
        creds.save("pw", "tok")
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
