package labs.suchattai.tsukaima.data.gateway

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.CoroutineStart
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import labs.suchattai.tsukaima.data.api.AuthException
import labs.suchattai.tsukaima.data.api.HermesJson
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class GatewayClientTest {
    private lateinit var server: MockWebServer
    private lateinit var scope: CoroutineScope
    private val okHttp = OkHttpClient()
    private val ticketCounter = AtomicInteger()
    private val gateways = mutableListOf<GatewayClient>()

    /** Server side of one upgraded socket: records inbound text, optionally answers capabilities with ready. */
    private inner class FakeServer(private val autoReady: Boolean = true) : WebSocketListener() {
        val received = LinkedBlockingQueue<String>()
        val sockets = LinkedBlockingQueue<WebSocket>()
        val closeCodes = LinkedBlockingQueue<Int>()

        override fun onOpen(webSocket: WebSocket, response: Response) {
            sockets.add(webSocket)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            received.add(text)
            if (autoReady && method(text) == "client.capabilities") webSocket.send(READY)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            closeCodes.add(code)
            webSocket.close(1000, null)
        }

        fun socket(): WebSocket = sockets.poll(TIMEOUT_S, TimeUnit.SECONDS) ?: error("no server socket")

        /** Next inbound frame whose method matches [m], skipping heartbeats and anything else. */
        fun next(m: String): JsonObject {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_S)
            while (true) {
                val left = deadline - System.nanoTime()
                val text = received.poll(left.coerceAtLeast(0), TimeUnit.NANOSECONDS) ?: throw AssertionError("no '$m' frame")
                val obj = HermesJson.parseToJsonElement(text).jsonObject
                if (obj["method"]?.jsonPrimitive?.content == m) return obj
            }
        }

        fun nextRaw(): String = received.poll(TIMEOUT_S, TimeUnit.SECONDS) ?: error("no frame")
    }

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @After
    fun tearDown() {
        gateways.forEach { it.disconnect() }
        scope.cancel()
        server.close()
    }

    private fun upgrade(listener: WebSocketListener) =
        server.enqueue(MockResponse.Builder().webSocketUpgrade(listener).build())

    private fun gateway(
        heartbeatMs: Long = 15_000,
        deadlineMs: Long = 45_000,
        backoffMs: List<Long> = listOf(20L),
        ticket: suspend () -> String = { "tk-${ticketCounter.incrementAndGet()}" },
    ) = GatewayClient(
        client = okHttp,
        baseUrl = { server.url("/").toString().trimEnd('/') },
        ticket = ticket,
        scope = scope,
        heartbeatMs = heartbeatMs,
        deadlineMs = deadlineMs,
        backoffMs = backoffMs,
    ).also { gateways += it }

    private suspend fun GatewayClient.awaitState(s: ConnectionState) =
        withTimeout(TIMEOUT_S * 1000) { state.first { it == s } }

    @Test
    fun connectSendsCapabilitiesThenBecomesConnectedOnReady() = runBlocking {
        val fake = FakeServer(autoReady = false)
        upgrade(fake)
        val gw = gateway()
        gw.connect()

        val caps = fake.next("client.capabilities")
        assertEquals(JsonPrimitive(true), caps["params"]!!.jsonObject["server_requests"])
        assertEquals(ConnectionState.Connecting, gw.state.value)

        fake.socket().send(READY)
        gw.awaitState(ConnectionState.Connected)
        assertEquals("/api/ws?ticket=tk-1", server.takeRequest().target)
    }

    @Test
    fun requestResolvesWithMatchingId() = runBlocking {
        val fake = FakeServer()
        upgrade(fake)
        val gw = gateway()
        gw.connect()
        gw.awaitState(ConnectionState.Connected)
        val ws = fake.socket()

        val reply = async(Dispatchers.Default) { gw.request("session.most_recent") }
        val req = fake.next("session.most_recent")
        assertEquals("t1", req["id"]!!.jsonPrimitive.content)
        // A response for some other id must not resolve this request.
        ws.send("""{"jsonrpc":"2.0","id":"t999","result":{"session_id":"wrong"}}""")
        ws.send("""{"jsonrpc":"2.0","id":"t1","result":{"session_id":"abc"}}""")

        val result = withTimeout(TIMEOUT_S * 1000) { reply.await() }
        assertEquals("abc", result["session_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun requestErrorFrameThrowsRpcException() = runBlocking {
        val fake = FakeServer()
        upgrade(fake)
        val gw = gateway()
        gw.connect()
        gw.awaitState(ConnectionState.Connected)
        val ws = fake.socket()

        val reply = async(Dispatchers.Default) { runCatching { gw.request("session.create", buildJsonObject { put("bogus", 1) }) } }
        val id = fake.next("session.create")["id"]!!.jsonPrimitive.content
        ws.send("""{"jsonrpc":"2.0","id":"$id","error":{"code":4000,"message":"unknown param bogus"}}""")

        val ex = withTimeout(TIMEOUT_S * 1000) { reply.await() }.exceptionOrNull()
        assertTrue("expected RpcException, got $ex", ex is RpcException)
        assertEquals(4000, (ex as RpcException).code)
        assertEquals("unknown param bogus", ex.message)
    }

    @Test
    fun eventsAreEmittedTyped() = runBlocking {
        val fake = FakeServer()
        upgrade(fake)
        val gw = gateway()
        val delta = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            withTimeout(TIMEOUT_S * 1000) { gw.events.first { it.type == "message.delta" } }
        }
        gw.connect()
        gw.awaitState(ConnectionState.Connected)
        fake.socket().send(
            """{"jsonrpc":"2.0","method":"event","params":{"type":"message.delta","session_id":"d4fb0dea","seq":12,"payload":{"text":"hi"}}}""",
        )

        val ev = delta.await()
        assertEquals("d4fb0dea", ev.sessionId)
        assertEquals(12L, ev.seq)
        assertEquals("hi", ev.payload["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun serverRequestRespondSendsResultWithSameId() = runBlocking {
        val fake = FakeServer()
        upgrade(fake)
        val gw = gateway()
        val srq = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            withTimeout(TIMEOUT_S * 1000) { gw.serverRequests.first() }
        }
        gw.connect()
        gw.awaitState(ConnectionState.Connected)
        val ws = fake.socket()
        fake.next("client.capabilities")
        ws.send("""{"jsonrpc":"2.0","id":"srq-1","method":"approval","params":{"session_id":"d4fb0dea","command":"ls"}}""")

        val req = srq.await()
        assertEquals("srq-1", req.id)
        assertEquals("approval", req.method)
        gw.respond("srq-1", buildJsonObject { put("choice", "once") })

        assertEquals("""{"jsonrpc":"2.0","id":"srq-1","result":{"choice":"once"}}""", fake.nextRaw())
    }

    @Test
    fun reconnectsAfterClose() = runBlocking {
        val first = FakeServer()
        val second = FakeServer()
        upgrade(first)
        upgrade(second)
        val gw = gateway(backoffMs = listOf(200L))
        gw.connect()
        gw.awaitState(ConnectionState.Connected)

        first.socket().close(1001, "going away")
        gw.awaitState(ConnectionState.Reconnecting) // held for the 200 ms backoff, so it can't be conflated away
        second.next("client.capabilities")
        gw.awaitState(ConnectionState.Connected)

        val t1 = server.takeRequest().url.queryParameter("ticket")
        val t2 = server.takeRequest().url.queryParameter("ticket")
        assertNotNull(t1)
        assertNotNull(t2)
        assertNotEquals(t1, t2)
    }

    @Test
    fun pendingRequestFailsOnClose() = runBlocking {
        val fake = FakeServer()
        upgrade(fake)
        upgrade(FakeServer()) // the client reconnects after the close; give it somewhere to go
        val gw = gateway()
        gw.connect()
        gw.awaitState(ConnectionState.Connected)
        val ws = fake.socket()

        val reply = async(Dispatchers.Default) { runCatching { gw.request("session.history") } }
        fake.next("session.history")
        ws.close(1001, "going away")

        val ex = withTimeout(TIMEOUT_S * 1000) { reply.await() }.exceptionOrNull()
        assertTrue("expected GatewayClosedException, got $ex", ex is GatewayClosedException)
    }

    @Test
    fun pingSentAfterHeartbeatInterval() = runBlocking {
        val fake = FakeServer()
        upgrade(fake)
        val gw = gateway(heartbeatMs = 50, deadlineMs = 10_000)
        gw.connect()
        gw.awaitState(ConnectionState.Connected)

        val ping = fake.next("gateway.ping")
        assertNotNull(ping["id"])
        // Still connected: pings alone keep the socket alive while the server keeps talking.
        assertEquals(ConnectionState.Connected, gw.state.value)
    }

    // ---- Lifecycle coverage beyond the brief's eight ----

    @Test
    fun silentServerHitsDeadlineClosesWith4000AndReconnects() = runBlocking {
        val silent = FakeServer() // answers capabilities with ready, then never sends another frame
        val second = FakeServer()
        upgrade(silent)
        upgrade(second)
        val gw = gateway(heartbeatMs = 50, deadlineMs = 200)
        gw.connect()
        gw.awaitState(ConnectionState.Connected)

        assertEquals(4000, silent.closeCodes.poll(TIMEOUT_S, TimeUnit.SECONDS))
        second.next("client.capabilities")
        gw.awaitState(ConnectionState.Connected)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun disconnectFailsPendingClosesWith1000AndStopsReconnecting() = runBlocking {
        val fake = FakeServer()
        upgrade(fake)
        upgrade(FakeServer())
        val gw = gateway()
        gw.connect()
        gw.awaitState(ConnectionState.Connected)

        val reply = async(Dispatchers.Default) { runCatching { gw.request("session.history") } }
        fake.next("session.history")
        gw.disconnect()
        gw.disconnect() // idempotent

        val ex = withTimeout(TIMEOUT_S * 1000) { reply.await() }.exceptionOrNull()
        assertTrue("expected GatewayClosedException, got $ex", ex is GatewayClosedException)
        assertEquals(1000, fake.closeCodes.poll(TIMEOUT_S, TimeUnit.SECONDS))
        assertEquals(ConnectionState.Disconnected, gw.state.value)
        Thread.sleep(200) // well past the 20 ms backoff
        assertEquals(1, server.requestCount)
        val late = runCatching { gw.request("session.list") }.exceptionOrNull()
        assertTrue("expected GatewayClosedException, got $late", late is GatewayClosedException)
    }

    @Test
    fun ticketAuthFailureStopsReconnecting() = runBlocking {
        val calls = AtomicInteger()
        val gw = gateway(ticket = { calls.incrementAndGet(); throw AuthException() })
        gw.connect()
        withTimeout(TIMEOUT_S * 1000) { while (calls.get() == 0) delay(5) }
        gw.awaitState(ConnectionState.Disconnected)
        Thread.sleep(200) // well past the 20 ms backoff: a retry would have asked for another ticket
        assertEquals(1, calls.get())
        assertEquals(ConnectionState.Disconnected, gw.state.value)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun ticketIoFailureBacksOffThenConnects() = runBlocking {
        upgrade(FakeServer())
        val calls = AtomicInteger()
        val gw = gateway(ticket = { if (calls.incrementAndGet() == 1) throw IOException("boom") else "tk-ok" })
        gw.connect()
        gw.awaitState(ConnectionState.Connected)
        assertEquals(2, calls.get())
        assertEquals("tk-ok", server.takeRequest().url.queryParameter("ticket"))
    }

    private companion object {
        const val TIMEOUT_S = 5L
        const val READY =
            """{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","session_id":"","payload":{"replay_epoch":1}}}"""

        fun method(text: String): String? =
            runCatching { HermesJson.parseToJsonElement(text).jsonObject["method"]?.jsonPrimitive?.content }.getOrNull()
    }
}
