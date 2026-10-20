package labs.suchattai.tsukaima.ui.chat

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import labs.suchattai.tsukaima.data.HermesState
import labs.suchattai.tsukaima.data.RecentIds
import labs.suchattai.tsukaima.data.api.HermesJson
import labs.suchattai.tsukaima.data.api.ModelOptionsDto
import labs.suchattai.tsukaima.data.api.WorkspacesDto
import labs.suchattai.tsukaima.data.gateway.ConnectionState
import labs.suchattai.tsukaima.data.gateway.Frame
import labs.suchattai.tsukaima.data.gateway.GatewayClosedException
import labs.suchattai.tsukaima.data.gateway.RpcTransport
import labs.suchattai.tsukaima.data.prefs.Settings
import labs.suchattai.tsukaima.domain.chat.AgentStatus
import labs.suchattai.tsukaima.domain.chat.ChatItem
import labs.suchattai.tsukaima.domain.chat.ToolStatus
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {
    private val live = "d4fb0dea"
    private val stored = "20261020_010916_48c967"

    private fun obj(s: String): JsonObject = HermesJson.parseToJsonElement(s).jsonObject

    /** Records every request and answer; [results] maps a method to its canned results, consumed in order (last repeats). */
    private class FakeTransport : RpcTransport {
        val calls = mutableListOf<Pair<String, JsonObject>>()
        val responses = mutableListOf<Pair<String, JsonObject>>()
        val results = mutableMapOf<String, ArrayDeque<JsonObject>>()
        val failing = mutableSetOf<String>()
        var gate: CompletableDeferred<Unit>? = null

        /** One-shot: the next call to the method waits for the gate (if any), then fails. */
        val failNext = mutableMapOf<String, CompletableDeferred<Unit>?>()

        /** One-shot: the next call to the method waits for the gate, then proceeds normally. */
        val gateNext = mutableMapOf<String, CompletableDeferred<Unit>>()

        fun on(method: String, vararg result: JsonObject) {
            results[method] = ArrayDeque(result.toList())
        }

        fun methods() = calls.map { it.first }
        fun last(method: String) = calls.last { it.first == method }.second

        override suspend fun request(method: String, params: JsonObject): JsonObject {
            calls += method to params
            if (method in failNext) {
                failNext.remove(method)?.await()
                throw GatewayClosedException()
            }
            gateNext.remove(method)?.await()
            if (method in failing) {
                gate?.await()
                throw GatewayClosedException()
            }
            val queue = results[method] ?: return JsonObject(emptyMap())
            return if (queue.size > 1) queue.removeFirst() else queue.first()
        }

        /** What [respond] reports: false = not enqueued on a live socket. */
        var respondOk = true

        override fun respond(id: String, result: JsonObject): Boolean {
            responses += id to result
            return respondOk
        }
        override fun respondError(id: String, code: Int, message: String): Boolean = true
    }

    private class FakeBackend(settings: Settings) : ChatBackend {
        override val transport = FakeTransport()
        override val events = MutableSharedFlow<Frame.Event>(extraBufferCapacity = 64)
        override val connection = MutableStateFlow(ConnectionState.Connected)
        override val serverRequests = MutableSharedFlow<Frame.ServerRequest>(replay = 16, extraBufferCapacity = 64)
        override val answeredRequestIds: MutableSet<String> = ConcurrentHashMap.newKeySet()
        override val closedRequestIds = RecentIds()
        override val hermes = MutableStateFlow(HermesState())
        override val settings = MutableStateFlow<Settings?>(settings)
        val toasts = mutableListOf<String>()
        val rest = mutableListOf<String>()

        override suspend fun updateSettings(block: (Settings) -> Settings) {
            settings.value = block(settings.value ?: Settings())
        }
        val bound = mutableListOf<Pair<String, String?>>()
        override suspend fun bindSession(live: String, stored: String?) {
            bound += live to stored
        }
        override suspend fun modelOptions() = Result.success(ModelOptionsDto(model = "m-1", provider = "p"))
        override suspend fun workspaces() = Result.success(WorkspacesDto(defaultCwd = "/home/hermes"))
        override suspend fun renameSession(id: String, title: String) = Result.success(Unit).also { rest += "rename $id $title" }
        override suspend fun archiveSession(id: String) = Result.success(Unit).also { rest += "archive $id" }
        override suspend fun deleteSession(id: String) = Result.success(Unit).also { rest += "delete $id" }
        override fun toast(text: String) {
            toasts += text
        }
    }

    private val dispatcher = StandardTestDispatcher()
    private val created = mutableListOf<ChatViewModel>()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() {
        // Cancel and join every VM scope before resetMain: nothing may outlive the test.
        created.forEach { it.viewModelScope.cancel() }
        dispatcher.scheduler.advanceUntilIdle()
        created.forEach { check(it.viewModelScope.coroutineContext.job.isCompleted) { "VM scope still running" } }
        Dispatchers.resetMain()
    }

    private fun backend(settings: Settings = Settings(workspace = "/srv/work", model = "m-1")) = FakeBackend(settings).apply {
        transport.on("session.create", obj("""{"session_id":"$live","stored_session_id":"$stored","info":{"model":"m-1"},"messages":[]}"""))
    }

    private fun vm(b: FakeBackend, storedId: String? = null) =
        ChatViewModel(b, storedId, dispatcher = dispatcher, flair = { "flair" }).also { created += it }

    private fun ev(type: String, seq: Long?, payload: String = "{}", session: String = live) =
        Frame.Event(type, session, seq, obj(payload))

    private fun approval(id: String, session: String = live) = Frame.ServerRequest(
        id, "approval",
        obj("""{"session_id":"$session","request_id":"req-$id","command":"rm -rf /tmp/x","tool_name":"terminal"}"""),
    )

    private val resumeJson = """
        {"session_id":"$live","stored_session_id":"$stored","running":false,"messages":[
          {"role":"user","text":"what kernel?","row_id":1},
          {"role":"assistant","text":"6.17.2","row_id":2}
        ]}
    """.trimIndent()

    @Test
    fun openNewCreatesSessionWithWorkspace() = runTest(dispatcher) {
        val b = backend()
        val vm = vm(b)
        advanceUntilIdle()

        val params = b.transport.last("session.create")
        assertEquals("/srv/work", params["cwd"]!!.jsonPrimitive.content)
        assertEquals("m-1", params["model"]!!.jsonPrimitive.content)
        val s = vm.state.value
        assertEquals(live, s.chat.liveSessionId)
        assertEquals(stored, s.chat.storedSessionId)
        assertEquals("/srv/work", s.workspace)
        assertFalse(s.loading)
        assertTrue(s.chat.items.isEmpty())
        assertEquals(1, b.transport.methods().count { it == "session.create" })
    }

    @Test
    fun openExistingResumesAndShowsHistory() = runTest(dispatcher) {
        val b = backend().apply { transport.on("session.resume", obj(resumeJson)) }
        val vm = vm(b, stored)
        assertTrue(vm.state.value.loading)
        advanceUntilIdle()

        assertEquals(stored, b.transport.last("session.resume")["session_id"]!!.jsonPrimitive.content)
        assertFalse("session.create" in b.transport.methods())
        val s = vm.state.value
        assertFalse(s.loading)
        assertEquals(live, s.chat.liveSessionId)
        assertEquals(listOf("what kernel?", "6.17.2"), s.chat.items.map {
            when (it) {
                is ChatItem.UserMessage -> it.text
                is ChatItem.AssistantMessage -> it.text
                else -> it.id
            }
        })
        assertEquals(AgentStatus.Idle, s.status)
    }

    @Test
    fun sendAppendsUserMessageAndSubmits() = runTest(dispatcher) {
        val b = backend()
        val vm = vm(b)
        advanceUntilIdle()

        vm.setInput("  restart the agent  ")
        vm.send()
        advanceUntilIdle()

        val s = vm.state.value
        assertEquals("", s.input)
        assertEquals("restart the agent", (s.chat.items.single() as ChatItem.UserMessage).text)
        assertEquals(AgentStatus.Thinking("flair"), s.chat.status)
        val params = b.transport.last("prompt.submit")
        assertEquals(live, params["session_id"]!!.jsonPrimitive.content)
        assertEquals("restart the agent", params["text"]!!.jsonPrimitive.content)

        vm.send() // empty input: nothing more is sent
        advanceUntilIdle()
        assertEquals(1, b.transport.methods().count { it == "prompt.submit" })
    }

    @Test
    fun streamedReplyUpdatesState() = runTest(dispatcher) {
        val b = backend()
        val vm = vm(b)
        advanceUntilIdle()

        b.events.emit(ev("message.start", 1))
        b.events.emit(ev("message.delta", 2, """{"text":"Hello"}"""))
        b.events.emit(ev("message.delta", 3, """{"text":", world"}"""))
        b.events.emit(ev("message.delta", 4, """{"text":"IGNORED"}""", session = "other123")) // another chat
        advanceUntilIdle()

        val streaming = vm.state.value.chat.items.single() as ChatItem.AssistantMessage
        assertEquals("Hello, world", streaming.text)
        assertTrue(streaming.streaming)
        assertEquals(AgentStatus.Replying, vm.state.value.status)

        b.events.emit(ev("message.complete", 5, """{"text":"Hello, world!"}"""))
        advanceUntilIdle()
        val done = vm.state.value.chat.items.single() as ChatItem.AssistantMessage
        assertEquals("Hello, world!", done.text)
        assertFalse(done.streaming)
        assertEquals(AgentStatus.Idle, vm.state.value.status)
        assertEquals(5L, vm.state.value.chat.lastSeq)
    }

    @Test
    fun approveRespondsOnceAndMarksAnswered() = runTest(dispatcher) {
        val b = backend()
        val vm = vm(b)
        advanceUntilIdle()

        b.serverRequests.emit(approval("srq-1"))
        advanceUntilIdle()
        assertEquals(AgentStatus.Approval, vm.state.value.status)

        vm.approve("srq-1")
        vm.approve("srq-1") // double tap
        advanceUntilIdle()

        val (id, result) = b.transport.responses.single()
        assertEquals("srq-1", id)
        assertEquals("once", result["choice"]!!.jsonPrimitive.content)
        val card = vm.state.value.chat.items.single() as ChatItem.Approval
        assertTrue(card.answered)
        assertEquals(true, vm.state.value.decisions["srq-1"])
        assertTrue("srq-1" in b.answeredRequestIds)

        b.serverRequests.emit(approval("srq-2"))
        advanceUntilIdle()
        vm.deny("srq-2")
        advanceUntilIdle()
        assertEquals("deny", b.transport.responses.last().second["choice"]!!.jsonPrimitive.content)
        assertEquals(false, vm.state.value.decisions["srq-2"])
    }

    @Test
    fun answeredApprovalFromReplayIsIgnored() = runTest(dispatcher) {
        val b = backend()
        // Already in ConnectionManager's replay buffer when the screen subscribes, and answered elsewhere.
        b.serverRequests.emit(approval("srq-old"))
        b.serverRequests.emit(approval("srq-open"))
        b.serverRequests.emit(approval("srq-foreign", session = "ffff0000"))
        b.answeredRequestIds += "srq-old"
        val vm = vm(b)
        advanceUntilIdle()

        val cards = vm.state.value.chat.items.filterIsInstance<ChatItem.Approval>()
        assertEquals(listOf("srq-open"), cards.map { it.id })
    }

    @Test
    fun withdrawnApprovalNotReadoptedFromReplay() = runTest(dispatcher) {
        // New chat: a withdrawn request still sits in the 16-entry replay, but the container saw it closed.
        val b = backend()
        b.serverRequests.emit(approval("srq-withdrawn"))
        b.serverRequests.emit(approval("srq-open"))
        b.closedRequestIds.add("srq-withdrawn")
        val vm = vm(b)
        advanceUntilIdle()
        assertEquals(listOf("srq-open"), vm.state.value.chat.items.filterIsInstance<ChatItem.Approval>().map { it.id })

        // Resumed chat: the snapshot's open_requests is authoritative; a replayed id it does not list is dead.
        val r = backend().apply {
            transport.on(
                "session.resume",
                obj(
                    resumeJson.replace(
                        "\"running\":false",
                        "\"running\":true,\"open_requests\":[{\"id\":\"srq-live\",\"method\":\"approval\"," +
                            "\"params\":{\"request_id\":\"req-live\",\"command\":\"ls\"}}]",
                    ),
                ),
            )
        }
        r.serverRequests.emit(approval("srq-stale"))
        r.serverRequests.emit(approval("srq-live"))
        val resumed = vm(r, stored)
        advanceUntilIdle()
        assertEquals(listOf("srq-live"), resumed.state.value.chat.items.filterIsInstance<ChatItem.Approval>().map { it.id })

        // A genuinely new request after the snapshot is still shown.
        r.serverRequests.emit(approval("srq-new"))
        advanceUntilIdle()
        assertEquals(
            listOf("srq-live", "srq-new"),
            resumed.state.value.chat.items.filterIsInstance<ChatItem.Approval>().map { it.id },
        )
    }

    @Test
    fun approveNotSentRevertsCard() = runTest(dispatcher) {
        val b = backend()
        val vm = vm(b)
        advanceUntilIdle()
        b.serverRequests.emit(approval("srq-1"))
        advanceUntilIdle()

        b.transport.respondOk = false
        vm.approve("srq-1")
        advanceUntilIdle()
        val card = vm.state.value.chat.items.single() as ChatItem.Approval
        assertFalse("not sent: the user can answer again", card.answered)
        assertFalse("srq-1" in b.answeredRequestIds)
        assertNull(vm.state.value.decisions["srq-1"])
        assertEquals(AgentStatus.Approval, vm.state.value.status)
        assertTrue(b.toasts.any { it.startsWith("Couldn’t send the answer") })

        b.transport.respondOk = true
        vm.approve("srq-1")
        advanceUntilIdle()
        assertTrue((vm.state.value.chat.items.single() as ChatItem.Approval).answered)
        assertTrue("srq-1" in b.answeredRequestIds)
    }

    @Test
    fun reconnectReplaysOnlyNewSeq() = runTest(dispatcher) {
        val b = backend()
        val vm = vm(b)
        advanceUntilIdle()
        b.events.emit(ev("message.start", 5))
        b.events.emit(ev("message.delta", 6, """{"text":"Working on it"}"""))
        advanceUntilIdle()
        val before = vm.state.value.chat.items.size

        // The server re-attached the same live session: its seq continues, so only 7 is new.
        b.transport.on("session.resume", obj(resumeJson.replace("\"running\":false", "\"running\":true")))
        b.transport.on(
            "session.events.since",
            obj(
                """{"events":[
                  {"type":"message.start","session_id":"$live","seq":5,"payload":{}},
                  {"type":"message.delta","session_id":"$live","seq":6,"payload":{"text":"Working on it"}},
                  {"type":"tool.start","session_id":"$live","seq":7,"payload":{"tool_id":"t1","name":"terminal","context":"uptime"}}
                ],"latest_seq":7,"truncated":false,"open_requests":[]}""",
            ),
        )
        b.connection.value = ConnectionState.Reconnecting
        advanceUntilIdle()
        assertEquals(AgentStatus.Offline, vm.state.value.status)
        b.connection.value = ConnectionState.Connected
        advanceUntilIdle()

        assertEquals(stored, b.transport.last("session.resume")["session_id"]!!.jsonPrimitive.content)
        assertEquals(6L, b.transport.last("session.events.since")["last_seen"]!!.jsonPrimitive.long)
        val items = vm.state.value.chat.items
        assertEquals(before + 1, items.size)
        assertEquals(ToolStatus.Running, (items.last() as ChatItem.ToolCall).status)
        assertEquals("Working on it", (items.first() as ChatItem.AssistantMessage).text)
        assertEquals(7L, vm.state.value.chat.lastSeq)
        assertFalse(vm.state.value.offline)
    }

    @Test
    fun reconnectWithNewLiveIdRebuildsFromHistoryAndReplaysAll() = runTest(dispatcher) {
        val b = backend()
        val vm = vm(b)
        advanceUntilIdle()
        b.events.emit(ev("message.start", 40))
        b.events.emit(ev("message.delta", 41, """{"text":"partial"}"""))
        advanceUntilIdle()

        b.transport.on("session.resume", obj(resumeJson.replace(live, "beef0001").replace("\"running\":false", "\"running\":true")))
        b.transport.on(
            "session.events.since",
            obj(
                """{"events":[
                  {"type":"tool.start","session_id":"beef0001","seq":1,"payload":{"tool_id":"t1","name":"terminal"}}
                ],"latest_seq":1,"truncated":false,
                "open_requests":[{"id":"srq-9","method":"approval","params":{"session_id":"beef0001","request_id":"r9","command":"ls","tool_name":"terminal"}}]}""",
            ),
        )
        b.connection.value = ConnectionState.Reconnecting
        advanceUntilIdle()
        b.connection.value = ConnectionState.Connected
        advanceUntilIdle()

        val s = vm.state.value
        assertEquals("beef0001", s.chat.liveSessionId)
        assertEquals(0L, b.transport.last("session.events.since")["last_seen"]!!.jsonPrimitive.long)
        assertEquals(listOf("h-1", "h-2", "t1", "srq-9"), s.chat.items.map { it.id })
        assertEquals(AgentStatus.Approval, s.status)
        assertEquals(1L, s.chat.lastSeq)
        // Live events of the old live id are now foreign.
        b.events.emit(ev("message.delta", 42, """{"text":"stale"}"""))
        advanceUntilIdle()
        assertEquals(4, vm.state.value.chat.items.size)
    }

    @Test
    fun reconnectWhileRunningShowsThinkingNotIdle() = runTest(dispatcher) {
        val b = backend()
        val vm = vm(b)
        advanceUntilIdle()
        b.transport.on("session.resume", obj(resumeJson.replace(live, "beef0001").replace("\"running\":false", "\"running\":true")))
        b.transport.on("session.events.since", obj("""{"events":[],"latest_seq":0,"truncated":false,"open_requests":[]}"""))
        b.connection.value = ConnectionState.Reconnecting
        advanceUntilIdle()
        b.connection.value = ConnectionState.Connected
        advanceUntilIdle()
        assertEquals(AgentStatus.Thinking("flair"), vm.state.value.status)
    }

    @Test
    fun resumeAdoptsPendingApproval() = runTest(dispatcher) {
        val b = backend().apply {
            transport.on(
                "session.resume",
                obj(resumeJson.replace("\"running\":false", "\"running\":true,\"pending_approval\":{\"request_id\":\"r5\",\"command\":\"reboot\",\"tool_name\":\"terminal\"}")),
            )
        }
        val vm = vm(b, stored)
        advanceUntilIdle()
        val card = vm.state.value.chat.items.last() as ChatItem.Approval
        assertEquals("reboot", card.command)

        vm.approve(card.id)
        advanceUntilIdle()
        val params = b.transport.last("approval.respond")
        assertEquals(live, params["session_id"]!!.jsonPrimitive.content)
        assertEquals("r5", params["request_id"]!!.jsonPrimitive.content)
        assertEquals("once", params["choice"]!!.jsonPrimitive.content)
        assertTrue(b.transport.responses.isEmpty())
    }

    @Test
    fun offlineDisablesComposerAndShowsOfflineStatus() = runTest(dispatcher) {
        val b = backend().apply { transport.on("session.resume", obj(resumeJson)) }
        val vm = vm(b, stored)
        advanceUntilIdle()
        assertFalse(vm.state.value.offline)

        b.hermes.value = HermesState(reachable = false)
        advanceUntilIdle()
        val s = vm.state.value
        assertTrue(s.offline)
        assertEquals(AgentStatus.Offline, s.status)
        assertEquals(2, s.chat.items.size) // cached history stays visible

        vm.setInput("hello?")
        vm.send()
        advanceUntilIdle()
        assertFalse("prompt.submit" in b.transport.methods())
        assertEquals("hello?", vm.state.value.input)

        // A dropped socket alone also counts as offline.
        b.hermes.value = HermesState()
        b.connection.value = ConnectionState.Reconnecting
        advanceUntilIdle()
        assertTrue(vm.state.value.offline)
    }

    @Test
    fun gatewayStatusWinsOverChatStatus() = runTest(dispatcher) {
        val b = backend()
        val vm = vm(b)
        advanceUntilIdle()
        b.hermes.value = HermesState(status = labs.suchattai.tsukaima.data.api.StatusDto(gatewayState = "stopped"))
        advanceUntilIdle()
        assertEquals(AgentStatus.Stopped, vm.state.value.status)
        assertFalse(vm.state.value.offline)
    }

    @Test
    fun pickingModelOnEmptyChatRecreatesSession() = runTest(dispatcher) {
        val b = backend()
        val vm = vm(b)
        advanceUntilIdle()
        b.transport.on("session.create", obj("""{"session_id":"cafe0002","stored_session_id":"20261020_020000_aaaaaa","info":{},"messages":[]}"""))

        vm.setModel("melious", "deepseek-v4")
        advanceUntilIdle()

        assertEquals(live, b.transport.last("session.close")["session_id"]!!.jsonPrimitive.content)
        val create = b.transport.last("session.create")
        assertEquals("deepseek-v4", create["model"]!!.jsonPrimitive.content)
        assertEquals("melious", create["provider"]!!.jsonPrimitive.content)
        assertEquals("cafe0002", vm.state.value.chat.liveSessionId)
        assertEquals("deepseek-v4", b.settings.value?.model)
        assertEquals("melious", b.settings.value?.modelProvider)
        assertTrue(b.toasts.isEmpty())
    }

    @Test
    fun pickingModelMidChatAppliesToNextChat() = runTest(dispatcher) {
        val b = backend()
        val vm = vm(b)
        advanceUntilIdle()
        vm.setInput("hi")
        vm.send()
        advanceUntilIdle()

        vm.setModel("melious", "deepseek-v4")
        advanceUntilIdle()
        assertFalse("session.close" in b.transport.methods())
        assertEquals(1, b.transport.methods().count { it == "session.create" })
        assertEquals("deepseek-v4", b.settings.value?.model)
        assertEquals(listOf("Model applies to the next chat"), b.toasts)
    }

    @Test
    fun renameCallsRpcAndRest() = runTest(dispatcher) {
        val b = backend()
        val vm = vm(b)
        advanceUntilIdle()
        vm.showDialog(ChatDialog.Rename)
        vm.rename("Kernel check")
        advanceUntilIdle()
        assertEquals("Kernel check", b.transport.last("session.title")["title"]!!.jsonPrimitive.content)
        assertEquals(listOf("rename $stored Kernel check"), b.rest)
        assertEquals("Kernel check", vm.state.value.chat.title)
        assertNull(vm.state.value.dialog)
    }

    @Test
    fun answerLostWithSocketIsOfferedAgainOnReconnect() = runTest(dispatcher) {
        val b = backend()
        val vm = vm(b)
        advanceUntilIdle()
        b.serverRequests.emit(approval("srq-1"))
        advanceUntilIdle()
        vm.approve("srq-1") // the socket was already gone: GatewayClient drops the answer
        advanceUntilIdle()

        b.transport.on("session.resume", obj(resumeJson.replace("\"running\":false", "\"running\":true")))
        b.transport.on(
            "session.events.since",
            obj(
                """{"events":[],"latest_seq":0,"truncated":false,
                "open_requests":[{"id":"srq-1","method":"approval","params":{"session_id":"$live","request_id":"req-srq-1","command":"rm -rf /tmp/x","tool_name":"terminal"}}]}""",
            ),
        )
        b.connection.value = ConnectionState.Reconnecting
        advanceUntilIdle()
        b.connection.value = ConnectionState.Connected
        advanceUntilIdle()

        val card = vm.state.value.chat.items.single { it is ChatItem.Approval } as ChatItem.Approval
        assertFalse(card.answered)
        assertNull(vm.state.value.decisions["srq-1"])
        assertEquals(AgentStatus.Approval, vm.state.value.status)
        vm.approve("srq-1")
        advanceUntilIdle()
        assertEquals(2, b.transport.responses.count { it.first == "srq-1" })
    }

    @Test
    fun failedResumeWhileDisconnectedStaysOffline() = runTest(dispatcher) {
        val b = backend()
        val vm = vm(b)
        advanceUntilIdle()
        b.transport.failing += "session.resume"
        b.transport.gate = CompletableDeferred()
        b.connection.value = ConnectionState.Reconnecting
        advanceUntilIdle()
        b.connection.value = ConnectionState.Connected
        advanceUntilIdle()
        assertEquals(1, b.transport.methods().count { it == "session.resume" }) // resume in flight
        b.connection.value = ConnectionState.Reconnecting // drops again while resuming
        advanceUntilIdle()
        b.transport.gate!!.complete(Unit)
        advanceUntilIdle()
        assertTrue(vm.state.value.offline)
        assertTrue(b.toasts.isEmpty())
    }

    private fun replayJson(vararg events: String) =
        obj("""{"events":[${events.joinToString(",")}],"latest_seq":0,"truncated":false,"open_requests":[]}""")

    private fun toolStart(seq: Long, id: String) =
        """{"type":"tool.start","session_id":"$live","seq":$seq,"payload":{"tool_id":"$id","name":"terminal"}}"""

    @Test
    fun reconnectFlapCancelsStaleRun() = runTest(dispatcher) {
        val b = backend()
        val vm = vm(b)
        advanceUntilIdle()
        b.events.emit(ev("message.start", 5))
        advanceUntilIdle()
        b.transport.on("session.resume", obj(resumeJson.replace("\"running\":false", "\"running\":true")))
        b.transport.on("session.events.since", replayJson(toolStart(6, "t6"), toolStart(7, "t7")))
        val firstResume = CompletableDeferred<Unit>()
        val replayGate = CompletableDeferred<Unit>()
        b.transport.gateNext["session.resume"] = firstResume

        b.connection.value = ConnectionState.Reconnecting
        advanceUntilIdle()
        b.connection.value = ConnectionState.Connected // run 1: resume parked on the gate
        advanceUntilIdle()
        b.connection.value = ConnectionState.Reconnecting // flap: run 1 is cancelled
        advanceUntilIdle()
        b.transport.gateNext["session.events.since"] = replayGate
        b.connection.value = ConnectionState.Connected // run 2: resumes, replay parked on its gate
        advanceUntilIdle()
        b.events.emit(ev("tool.start", 8, """{"tool_id":"t8","name":"terminal"}""")) // live, held by run 2
        advanceUntilIdle()
        firstResume.complete(Unit) // run 1 is gone: nothing happens
        advanceUntilIdle()
        replayGate.complete(Unit)
        advanceUntilIdle()

        assertEquals(2, b.transport.methods().count { it == "session.resume" })
        assertEquals(1, b.transport.methods().count { it == "session.events.since" })
        val s = vm.state.value
        assertEquals(listOf("t6", "t7", "t8"), s.chat.items.map { it.id }) // the empty a-5 bubble is dropped on loss
        assertEquals(8L, s.chat.lastSeq)
        assertTrue(b.toasts.isEmpty())
        assertFalse(s.offline)
    }

    @Test
    fun approvalWithoutAnyIdIsInert() = runTest(dispatcher) {
        val b = backend().apply {
            transport.on(
                "session.resume",
                obj(resumeJson.replace("\"running\":false", "\"running\":true,\"pending_approval\":{\"command\":\"reboot\",\"tool_name\":\"terminal\"}")),
            )
        }
        val vm = vm(b, stored)
        advanceUntilIdle()
        val card = vm.state.value.chat.items.last() as ChatItem.Approval
        assertFalse(ChatSync.answerable(card))

        vm.approve(card.id)
        vm.deny(card.id)
        advanceUntilIdle()
        assertFalse("approval.respond" in b.transport.methods())
        assertTrue(b.transport.responses.isEmpty())
        assertFalse((vm.state.value.chat.items.last() as ChatItem.Approval).answered)
        assertFalse(card.id in b.answeredRequestIds)
    }

    @Test
    fun pendingApprovalFallbackRequiresRequestId() = runTest(dispatcher) {
        fun resumeWith(pending: String) = obj(resumeJson.replace("\"running\":false", "\"running\":true,\"pending_approval\":$pending"))

        val empty = backend().apply { transport.on("session.resume", resumeWith("""{"request_id":"","command":"reboot"}""")) }
        val vmEmpty = vm(empty, stored)
        val bound = backend().apply { transport.on("session.resume", resumeWith("""{"request_id":"r5","command":"reboot"}""")) }
        val vmBound = vm(bound, stored)
        advanceUntilIdle()

        vmEmpty.approve(vmEmpty.state.value.chat.items.last().id)
        vmBound.approve(vmBound.state.value.chat.items.last().id)
        advanceUntilIdle()

        assertFalse("approval.respond" in empty.transport.methods())
        assertEquals("r5", bound.transport.last("approval.respond")["request_id"]!!.jsonPrimitive.content)
        assertTrue(empty.transport.responses.isEmpty() && bound.transport.responses.isEmpty())
    }

    @Test
    fun failedFallbackAnswerCanBeRetried() = runTest(dispatcher) {
        val b = backend().apply {
            transport.on("session.resume", obj(resumeJson.replace("\"running\":false", "\"running\":true,\"pending_approval\":{\"request_id\":\"r5\"}")))
            transport.failNext["approval.respond"] = null
        }
        val vm = vm(b, stored)
        advanceUntilIdle()
        vm.approve("pending-r5")
        advanceUntilIdle()
        val card = vm.state.value.chat.items.last() as ChatItem.Approval
        assertFalse(card.answered)
        assertFalse("pending-r5" in b.answeredRequestIds)
        assertEquals(AgentStatus.Approval, vm.state.value.status)

        vm.approve("pending-r5")
        advanceUntilIdle()
        assertEquals(2, b.transport.methods().count { it == "approval.respond" })
        assertTrue((vm.state.value.chat.items.last() as ChatItem.Approval).answered)
    }

    @Test
    fun openRetriesAfterFailedAttemptDuringFlap() = runTest(dispatcher) {
        val b = backend()
        val gate = CompletableDeferred<Unit>()
        b.transport.failNext["session.create"] = gate
        val vm = vm(b)
        advanceUntilIdle() // create parked
        b.connection.value = ConnectionState.Reconnecting
        advanceUntilIdle()
        b.connection.value = ConnectionState.Connected // opening: ignored by the collector
        advanceUntilIdle()
        gate.complete(Unit) // the in-flight attempt fails with the dropped socket
        advanceUntilIdle()

        assertEquals(2, b.transport.methods().count { it == "session.create" })
        assertEquals(live, vm.state.value.chat.liveSessionId)
        assertTrue(b.toasts.isEmpty())
    }

    @Test
    fun sendFailureRemovesLocalBubble() = runTest(dispatcher) {
        val b = backend()
        val vm = vm(b)
        advanceUntilIdle()
        b.transport.failNext["prompt.submit"] = null
        vm.setInput("hello")
        vm.send()
        advanceUntilIdle()
        assertTrue(vm.state.value.chat.items.isEmpty())
        assertEquals("hello", vm.state.value.input)
        assertEquals(AgentStatus.Idle, vm.state.value.chat.status)

        vm.send() // retry
        advanceUntilIdle()
        assertEquals(listOf("hello"), vm.state.value.chat.items.map { (it as ChatItem.UserMessage).text })
    }

    @Test
    fun failedSendWhileOfflineKeepsOffline() = runTest(dispatcher) {
        val b = backend()
        val vm = vm(b)
        advanceUntilIdle()
        val gate = CompletableDeferred<Unit>()
        b.transport.failNext["prompt.submit"] = gate
        vm.setInput("hello")
        vm.send()
        advanceUntilIdle() // submit parked
        b.connection.value = ConnectionState.Reconnecting // the socket drops: Offline
        advanceUntilIdle()
        gate.complete(Unit) // ...and the pending submit fails because of it
        advanceUntilIdle()

        assertEquals(AgentStatus.Offline, vm.state.value.chat.status)
        assertTrue(vm.state.value.offline)
        assertTrue(vm.state.value.chat.items.isEmpty())
        assertEquals("hello", vm.state.value.input)
    }

    @Test
    fun failedFallbackAnswerWhileOfflineKeepsOffline() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val b = backend().apply {
            transport.on("session.resume", obj(resumeJson.replace("\"running\":false", "\"running\":true,\"pending_approval\":{\"request_id\":\"r5\"}")))
            transport.failNext["approval.respond"] = gate
        }
        val vm = vm(b, stored)
        advanceUntilIdle()
        vm.approve("pending-r5")
        advanceUntilIdle() // answer parked
        b.connection.value = ConnectionState.Reconnecting
        advanceUntilIdle()
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(AgentStatus.Offline, vm.state.value.chat.status)
        assertTrue(vm.state.value.offline)
        assertFalse((vm.state.value.chat.items.last() as ChatItem.Approval).answered) // still retryable
    }

    @Test
    fun openRetryIsBoundedPerConnectionState() = runTest(dispatcher) {
        val b = backend()
        b.transport.failing += "session.create" // fails every time, while Connected
        val vm = vm(b)
        advanceUntilIdle()
        assertEquals(2, b.transport.methods().count { it == "session.create" }) // the attempt + one retry
        assertNull(vm.state.value.chat.liveSessionId)

        // A new transition allows the normal reopen again, and a fresh retry budget.
        b.transport.failing.clear()
        b.connection.value = ConnectionState.Reconnecting
        advanceUntilIdle()
        b.connection.value = ConnectionState.Connected
        advanceUntilIdle()
        assertEquals(3, b.transport.methods().count { it == "session.create" })
        assertEquals(live, vm.state.value.chat.liveSessionId)
    }
}
