package app.tsukaima.data.gateway

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import app.tsukaima.data.api.AuthException
import app.tsukaima.data.api.CleartextGuard
import app.tsukaima.data.api.TrustedHost
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * The long-lived Hermes chat WebSocket (`/api/ws`): JSON-RPC requests, typed events, server requests,
 * an app-level heartbeat and reconnect with backoff. See docs/protocol/README.md §2.
 *
 * Concurrency model: all mutable connection state (socket, epoch, timers, retry count) is confined to one
 * actor coroutine launched in [scope]. Public calls, OkHttp listener callbacks and timers never touch that
 * state; they post an [Input] to [inbox], and the actor handles inputs one at a time, in arrival order.
 * Timers are jobs in [scope] that only post inputs, so they run on the scope's dispatcher (and on virtual
 * time under `runTest`). Every socket and timer input carries the [epoch] it was created in, and every
 * lifecycle transition bumps the epoch, so late callbacks from a dead socket or a superseded timer are dropped.
 * Shared outside the actor, all thread-safe: [pending], the [wanted] flag, and the public flows.
 *
 * A request timeout throws a plain [IOException] (not [GatewayClosedException]): the socket may be healthy.
 *
 * A plain-http [baseUrl] that [cleartextAllowed] refuses ([CleartextGuard]) is never connected to: the attempt
 * stops before the ticket is fetched, as after an auth failure, until the next [connect].
 */
class GatewayClient(
    private val client: OkHttpClient,
    private val baseUrl: () -> String,
    private val ticket: suspend () -> String,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val heartbeatMs: Long = 15_000,
    private val deadlineMs: Long = 45_000,
    private val backoffMs: List<Long> = listOf(1_000L, 2_000L, 5_000L, 10_000L),
    private val ticketTimeoutMs: Long = 15_000,
    private val logger: (String, Throwable?) -> Unit = { _, _ -> },
    private val cleartextAllowed: (HttpUrl) -> Boolean = TrustedHost::allowsCleartext,
) : RpcTransport {
    init {
        require(heartbeatMs > 0 && deadlineMs > 0 && ticketTimeoutMs > 0) { "intervals must be positive" }
        require(backoffMs.isNotEmpty()) { "backoffMs must not be empty" }
    }

    private val _state = MutableStateFlow(ConnectionState.Disconnected)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<Frame.Event>(
        replay = 0, extraBufferCapacity = 1024, onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<Frame.Event> = _events.asSharedFlow()

    private val serverRequestChannel = Channel<Frame.ServerRequest>(Channel.UNLIMITED)

    /**
     * Server→client requests (approval, clarify), buffered until collected so none is lost while no screen
     * is listening. Single-consumer: there must be exactly one collector (each request goes to one of them).
     */
    val serverRequests: Flow<Frame.ServerRequest> = serverRequestChannel.receiveAsFlow()

    /** Caller request ids, `t<n>`. */
    private val requestIds = AtomicLong()

    /** Gateway-owned frames (capabilities, pings), `g<n>`: never awaited, so their responses are ignored. */
    private val internalIds = AtomicLong()

    /** Requests sent on the current socket, awaiting their response. */
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JsonObject>>()

    /** Set by connect()/disconnect() (and cleared on auth failure); reconnects only happen while true. */
    private val wanted = MutableStateFlow(false)

    private val inbox = Channel<Input>(Channel.UNLIMITED)

    // ---- Actor-confined state: only read or written inside handle(). ----
    private var epoch = 0L
    private var socket: WebSocket? = null

    /**
     * The current socket once `gateway.ready` arrived, else null. Written only by the actor (and its
     * completion handler); read by [respond] from any thread.
     */
    @Volatile private var liveSocket: WebSocket? = null
    private var lastInboundAt = 0L
    private var retries = 0
    private var ticketJob: Job? = null
    private var retryJob: Job? = null
    private var heartbeatJob: Job? = null

    private sealed interface Input {
        data object Connect : Input
        data object Disconnect : Input
        class Send(val text: String, val id: String?, val reply: CompletableDeferred<JsonObject>?) : Input
        class TicketReady(val epoch: Long, val ticket: String) : Input
        class TicketFailed(val epoch: Long, val error: Throwable) : Input
        class Opened(val epoch: Long) : Input
        class Message(val epoch: Long, val text: String) : Input
        class Dropped(val epoch: Long) : Input
        class Tick(val epoch: Long) : Input
        class Retry(val epoch: Long) : Input
    }

    init {
        val actor = scope.launch {
            for (input in inbox) {
                try {
                    handle(input)
                } catch (c: CancellationException) {
                    throw c
                } catch (t: Throwable) {
                    // Never let one bad input kill the actor: abandon the current socket and let reconnect take over.
                    logger("gateway: ${input::class.simpleName} handler failed; dropping connection", t)
                    socket?.cancel()
                    drop()
                }
            }
        }
        // Runs after the actor has finished (or never started), so the confined state is safe to touch here.
        actor.invokeOnCompletion {
            inbox.close()
            serverRequestChannel.close()
            while (true) {
                val leftover = inbox.tryReceive().getOrNull() ?: break
                (leftover as? Input.Send)?.reply?.completeExceptionally(GatewayClosedException())
            }
            wanted.value = false
            socket?.cancel()
            socket = null
            liveSocket = null
            failPending()
            _state.value = ConnectionState.Disconnected
        }
    }

    /** Starts connecting (idempotent). Keeps reconnecting until [disconnect] or an auth failure. */
    fun connect() {
        wanted.value = true
        post(Input.Connect)
    }

    /** Closes with 1000, fails pending requests with [GatewayClosedException], stops reconnecting (idempotent). */
    fun disconnect() {
        wanted.value = false
        post(Input.Disconnect)
    }

    override suspend fun request(method: String, params: JsonObject): JsonObject =
        request(method, params, DEFAULT_TIMEOUT_MS)

    override suspend fun requestSlow(method: String, params: JsonObject, timeoutMs: Long): JsonObject =
        request(method, params, timeoutMs)

    /**
     * Sends [method] once the gateway is [ConnectionState.Connected] (waiting through a reconnect if needed)
     * and suspends for the response; [timeoutMs] covers both the wait and the round trip.
     * @throws RpcException on an error frame.
     * @throws GatewayClosedException if the socket closes while pending, or the client is not wanted.
     * @throws IOException (plain) on timeout.
     */
    suspend fun request(
        method: String,
        params: JsonObject = JsonObject(emptyMap()),
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): JsonObject {
        val id = "t${requestIds.incrementAndGet()}"
        val reply = CompletableDeferred<JsonObject>()
        try {
            return withTimeoutOrNull(timeoutMs) {
                awaitConnected()
                post(Input.Send(JsonRpc.encodeRequest(id, method, params), id, reply))
                reply.await()
            } ?: throw IOException("gateway request '$method' timed out after ${timeoutMs}ms")
        } finally {
            reply.cancel()
            pending.remove(id)
        }
    }

    /**
     * Enqueues the answer directly on the live (ready) socket; OkHttp's [WebSocket.send] is thread-safe.
     * Returns false when there is no live socket or it refused the frame (closing, queue full): the answer
     * was not sent and the caller may let the user try again. The server re-offers open requests on resume.
     */
    override fun respond(id: String, result: JsonObject): Boolean =
        liveSocket?.send(JsonRpc.encodeResult(id, result)) ?: false

    /** Like [respond], with a JSON-RPC error frame. */
    override fun respondError(id: String, code: Int, message: String): Boolean =
        liveSocket?.send(JsonRpc.encodeError(id, code, message)) ?: false

    private suspend fun awaitConnected() {
        val s = combine(state, wanted) { s, w -> if (s == ConnectionState.Disconnected && !w) null else s }
            .first { it == null || it == ConnectionState.Connected }
        if (s == null) throw GatewayClosedException()
    }

    private fun post(input: Input) {
        if (inbox.trySend(input).isFailure) {
            (input as? Input.Send)?.reply?.completeExceptionally(GatewayClosedException())
        }
    }

    // ---- Actor ----

    private fun handle(input: Input) {
        when (input) {
            Input.Connect -> if (wanted.value && _state.value == ConnectionState.Disconnected) {
                retries = 0
                startAttempt(ConnectionState.Connecting)
            }
            Input.Disconnect -> teardown()
            is Input.Send -> send(input)
            is Input.TicketReady -> if (current(input.epoch) && wanted.value) openSocket(input.ticket)
            is Input.TicketFailed -> if (current(input.epoch)) {
                if (input.error is AuthException) {
                    wanted.value = false
                    teardown()
                } else {
                    drop()
                }
            }
            is Input.Opened -> if (current(input.epoch)) onOpened()
            is Input.Message -> if (current(input.epoch)) onMessage(input.text)
            is Input.Dropped -> if (current(input.epoch)) drop()
            is Input.Tick -> if (current(input.epoch)) onTick()
            is Input.Retry -> if (current(input.epoch) && wanted.value) startAttempt(ConnectionState.Reconnecting)
        }
    }

    private fun current(e: Long) = e == epoch

    private fun startAttempt(s: ConnectionState) {
        val base = baseUrl().toHttpUrlOrNull()
        if (base != null && CleartextGuard.refuses(base, cleartextAllowed)) {
            logger("gateway: ${CleartextGuard.MESSAGE}", null)
            wanted.value = false
            teardown()
            return
        }
        val e = ++epoch
        _state.value = s
        ticketJob = scope.launch {
            val result = try {
                Input.TicketReady(e, withTimeout(ticketTimeoutMs) { ticket() })
            } catch (t: Throwable) {
                // Our own cancellation (teardown/scope) propagates; anything else, including the ticket
                // timeout or a CancellationException leaked by the supplier, is a failed attempt.
                if (!isActive) throw t
                Input.TicketFailed(e, t)
            }
            post(result)
        }
    }

    private fun openSocket(t: String) {
        ticketJob = null
        val url = baseUrl().trimEnd('/').replaceFirst("http", "ws") +
            "/api/ws?ticket=" + URLEncoder.encode(t, "UTF-8")
        val request = try {
            Request.Builder().url(url).build()
        } catch (_: IllegalArgumentException) {
            drop() // malformed server URL: same as a failed connect, back off and retry
            return
        }
        socket = client.newWebSocket(request, Listener(epoch))
    }

    private fun onOpened() {
        lastInboundAt = clock()
        sendInternal("client.capabilities", buildJsonObject { put("server_requests", true) })
        val e = epoch
        heartbeatJob = scope.launch {
            while (true) {
                delay(heartbeatMs)
                post(Input.Tick(e))
            }
        }
    }

    private fun onMessage(text: String) {
        lastInboundAt = clock()
        for (frame in JsonRpc.decodeFrames(text)) {
            when (frame) {
                is Frame.Response -> pending.remove(frame.id)?.let { reply ->
                    val error = frame.error
                    if (error != null) {
                        reply.completeExceptionally(RpcException(error.code, error.message))
                    } else {
                        reply.complete(frame.result ?: JsonObject(emptyMap()))
                    }
                }
                is Frame.Event -> {
                    if (frame.type == READY_EVENT && _state.value != ConnectionState.Connected) {
                        retries = 0
                        liveSocket = socket
                        _state.value = ConnectionState.Connected
                    }
                    _events.tryEmit(frame)
                }
                is Frame.ServerRequest -> serverRequestChannel.trySend(frame)
                is Frame.Unknown -> Unit
            }
        }
    }

    private fun onTick() {
        val s = socket ?: return
        if (clock() - lastInboundAt >= deadlineMs) {
            s.close(HEARTBEAT_CLOSE_CODE, "heartbeat timeout")
            drop()
        } else {
            sendInternal("gateway.ping", JsonObject(emptyMap()))
        }
    }

    private fun send(input: Input.Send) {
        val reply = input.reply
        val id = input.id
        if (reply == null || id == null) {
            socket?.send(input.text) // respond(): best effort on whatever socket is open
            return
        }
        if (reply.isCompleted) return // timed out or cancelled before reaching the actor
        val s = socket
        if (s == null || _state.value != ConnectionState.Connected) {
            reply.completeExceptionally(GatewayClosedException())
            return
        }
        pending[id] = reply
        // The caller may have timed out between the check above and the put; don't leak its entry.
        if (reply.isCompleted) pending.remove(id, reply)
        if (!s.send(input.text)) pending.remove(id)?.completeExceptionally(GatewayClosedException())
    }

    private fun sendInternal(method: String, params: JsonObject) {
        socket?.send(JsonRpc.encodeRequest("g${internalIds.incrementAndGet()}", method, params))
    }

    /** The current socket (or attempt) is gone: fail in-flight requests, then back off or stop. */
    private fun drop() {
        val e = ++epoch
        cancelJobs()
        socket = null
        liveSocket = null
        failPending()
        if (!wanted.value) {
            _state.value = ConnectionState.Disconnected
            return
        }
        _state.value = ConnectionState.Reconnecting
        val wait = backoffMs[retries.coerceAtMost(backoffMs.lastIndex)]
        retries++
        retryJob = scope.launch {
            delay(wait)
            post(Input.Retry(e))
        }
    }

    private fun teardown() {
        epoch++
        cancelJobs()
        socket?.close(NORMAL_CLOSE_CODE, null)
        socket = null
        liveSocket = null
        failPending()
        retries = 0
        _state.value = ConnectionState.Disconnected
    }

    private fun cancelJobs() {
        ticketJob?.cancel()
        retryJob?.cancel()
        heartbeatJob?.cancel()
        ticketJob = null
        retryJob = null
        heartbeatJob = null
    }

    private fun failPending() {
        for (id in pending.keys) pending.remove(id)?.completeExceptionally(GatewayClosedException())
    }

    /** Hops every OkHttp callback onto the actor, tagged with the epoch the socket was opened in. */
    private inner class Listener(private val epoch: Long) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) = post(Input.Opened(epoch))

        override fun onMessage(webSocket: WebSocket, text: String) = post(Input.Message(epoch, text))

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(NORMAL_CLOSE_CODE, null)
            post(Input.Dropped(epoch))
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = post(Input.Dropped(epoch))

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = post(Input.Dropped(epoch))
    }

    private companion object {
        const val DEFAULT_TIMEOUT_MS = 60_000L
        const val READY_EVENT = "gateway.ready"
        const val NORMAL_CLOSE_CODE = 1000
        const val HEARTBEAT_CLOSE_CODE = 4000
    }
}
