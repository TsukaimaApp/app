package labs.suchattai.tsukaima.ui.chat

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import labs.suchattai.tsukaima.data.HermesState
import labs.suchattai.tsukaima.data.RecentIds
import labs.suchattai.tsukaima.data.api.ApiException
import labs.suchattai.tsukaima.data.api.FileDownload
import labs.suchattai.tsukaima.data.api.HermesJson
import labs.suchattai.tsukaima.data.api.ModelOptionsDto
import labs.suchattai.tsukaima.data.api.WorkspacesDto
import labs.suchattai.tsukaima.data.files.FileSaver
import labs.suchattai.tsukaima.data.gateway.ApprovalChoice
import labs.suchattai.tsukaima.data.gateway.ConnectionState
import labs.suchattai.tsukaima.data.gateway.Frame
import labs.suchattai.tsukaima.data.gateway.GatewayClosedException
import labs.suchattai.tsukaima.data.gateway.RpcTransport
import labs.suchattai.tsukaima.data.prefs.Settings
import labs.suchattai.tsukaima.domain.chat.AgentStatus
import labs.suchattai.tsukaima.domain.chat.ChatItem
import labs.suchattai.tsukaima.domain.chat.SecretKind
import labs.suchattai.tsukaima.domain.chat.StatusTone
import labs.suchattai.tsukaima.domain.chat.running
import labs.suchattai.tsukaima.domain.chat.ToolStatus
import labs.suchattai.tsukaima.domain.voice.FakeSpeechInput
import labs.suchattai.tsukaima.domain.voice.FakeSpeechOutput
import labs.suchattai.tsukaima.domain.voice.VoiceLanguage
import labs.suchattai.tsukaima.domain.voice.VoiceMode
import labs.suchattai.tsukaima.domain.voice.SpeechEvent
import labs.suchattai.tsukaima.domain.voice.VoicePhase
import labs.suchattai.tsukaima.ui.voice.voiceLine
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import okio.Buffer
import okio.BufferedSource
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

        /** Methods that fail with a plain error (not "not connected"): not retried. */
        val crashing = mutableSetOf<String>()
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
            if (method in crashing) throw IllegalStateException("boom")
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

        /** Simulates a failing settings write: [updateSettings] changes nothing. */
        var dropSettingsWrites = false
        override suspend fun updateSettings(block: (Settings) -> Settings) {
            if (dropSettingsWrites) return
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
        var archived = false
        override suspend fun unarchiveSession(id: String) = Result.success(Unit).also {
            rest += "unarchive $id"
            archived = false
        }
        override suspend fun sessionArchived(id: String) = Result.success(archived)
        override suspend fun deleteSession(id: String) = Result.success(Unit).also { rest += "delete $id" }

        val downloads = mutableListOf<String>()
        var downloadGate: CompletableDeferred<Unit>? = null
        var downloadResult: (String) -> Result<FileDownload> = { Result.success(FileDownload("probe.txt", "text/plain", 3, Buffer().writeUtf8("abc"))) }
        override suspend fun download(path: String): Result<FileDownload> {
            downloads += path
            downloadGate?.await()
            return downloadResult(path)
        }

        /** name, mime, content of every save. */
        val saved = mutableListOf<Triple<String, String, String>>()
        override val fileSaver = object : FileSaver {
            override fun save(name: String, mime: String, source: BufferedSource): String {
                saved += Triple(name, mime, source.readUtf8())
                return "content://media/external/downloads/${saved.size}"
            }
        }
        val opened = mutableListOf<Pair<String, String>>()
        override fun openFile(uri: String, mime: String) = true.also { opened += uri to mime }
        var decoded = 0
        override fun decodePreview(bytes: ByteArray): ImageBitmap = FakeBitmap.also { decoded++ }

        override fun toast(text: String) {
            toasts += text
        }

        val speech = FakeSpeechInput()
        override fun speechInput() = speech
        override val speechOutput = FakeSpeechOutput(instant = true)
        override fun voiceLanguages() = listOf(VoiceLanguage(null, "System default"), VoiceLanguage("pl-PL", "Polish (Poland)"))
    }

    private object FakeBitmap : ImageBitmap {
        override val width = 1
        override val height = 1
        override val colorSpace get() = error("unused")
        override val hasAlpha = false
        override val config = ImageBitmapConfig.Argb8888
        override fun readPixels(buffer: IntArray, startX: Int, startY: Int, width: Int, height: Int, bufferOffset: Int, stride: Int) = Unit
        override fun prepareToDraw() = Unit
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
        ChatViewModel(b, storedId, dispatcher = dispatcher, io = dispatcher, flair = { "flair" }).also { created += it }

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
        assertEquals(ApprovalChoice.once, vm.state.value.decisions["srq-1"])
        assertTrue("srq-1" in b.answeredRequestIds)

        b.serverRequests.emit(approval("srq-2"))
        advanceUntilIdle()
        vm.deny("srq-2")
        advanceUntilIdle()
        assertEquals("deny", b.transport.responses.last().second["choice"]!!.jsonPrimitive.content)
        assertEquals(ApprovalChoice.deny, vm.state.value.decisions["srq-2"])
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

    private fun clarify(id: String, session: String = live) = Frame.ServerRequest(
        id, "clarify",
        obj(
            """{"session_id":"$session","questions":[
                 {"qid":"q1","question":"Which colour?","choices":["red","green","blue"],"multi_select":false},
                 {"qid":"q2","question":"Why?","choices":null,"multi_select":false}]}""",
        ),
    )

    @Test
    fun sendClarifyAnswersEncodesNulls() = runTest(dispatcher) {
        val b = backend()
        val vm = vm(b)
        advanceUntilIdle()
        b.serverRequests.emit(clarify("srq-c"))
        advanceUntilIdle()
        assertEquals(AgentStatus.Question, vm.state.value.status)

        vm.answerClarify("srq-c", mapOf("q1" to "red", "q2" to null))
        vm.answerClarify("srq-c", mapOf("q1" to "blue", "q2" to null)) // double tap
        advanceUntilIdle()

        val (id, result) = b.transport.responses.single()
        assertEquals("srq-c", id)
        assertEquals("""{"answers":{"q1":"red","q2":null}}""", result.toString())
        val card = vm.state.value.chat.items.single() as ChatItem.Clarify
        assertTrue(card.answered)
        assertEquals(mapOf("q1" to "red", "q2" to null), card.answers)
        assertTrue("srq-c" in b.answeredRequestIds)
        assertTrue(vm.state.value.status is AgentStatus.Thinking)
    }

    @Test
    fun skipAllSendsNoAnswers() = runTest(dispatcher) {
        val b = backend()
        val vm = vm(b)
        advanceUntilIdle()
        b.serverRequests.emit(clarify("srq-c"))
        advanceUntilIdle()

        vm.skipClarify("srq-c")
        advanceUntilIdle()

        // No `answers` key = cancel-all (gateway-contract ClarifyResult; the TUI's Esc sends `{}` too).
        assertEquals(listOf("srq-c" to JsonObject(emptyMap())), b.transport.responses)
        val card = vm.state.value.chat.items.single() as ChatItem.Clarify
        assertTrue(card.answered)
        assertTrue(card.answers.isEmpty())
    }

    @Test
    fun clarifyNotSentReopensCard() = runTest(dispatcher) {
        val b = backend()
        val vm = vm(b)
        advanceUntilIdle()
        b.serverRequests.emit(clarify("srq-c"))
        b.serverRequests.emit(clarify("srq-foreign", session = "ffff0000"))
        advanceUntilIdle()
        assertEquals(listOf("srq-c"), vm.state.value.chat.items.map { it.id })

        b.transport.respondOk = false
        vm.answerClarify("srq-c", mapOf("q1" to "red", "q2" to null))
        advanceUntilIdle()
        assertFalse((vm.state.value.chat.items.single() as ChatItem.Clarify).answered)
        assertFalse("srq-c" in b.answeredRequestIds)
        assertEquals(AgentStatus.Question, vm.state.value.status)
        assertTrue(b.toasts.any { it.startsWith("Couldn’t send the answer") })
    }

    @Test
    fun resumeReadoptsOpenClarify() = runTest(dispatcher) {
        val b = backend().apply {
            transport.on(
                "session.resume",
                obj(
                    resumeJson.replace(
                        "\"running\":false",
                        "\"running\":true,\"open_requests\":[{\"id\":\"srq-c\",\"method\":\"clarify\"," +
                            "\"params\":{\"session_id\":\"old\",\"questions\":[{\"qid\":\"q1\",\"question\":\"Colour?\"}]," +
                            "\"answers\":{\"q1\":null}}}]",
                    ),
                ),
            )
        }
        val vm = vm(b, stored)
        advanceUntilIdle()
        val card = vm.state.value.chat.items.last() as ChatItem.Clarify
        assertEquals("srq-c", card.id)
        assertFalse(card.answered)
        assertEquals(AgentStatus.Question, vm.state.value.status)
    }

    private class FakeFile(private val name: String, private val mime: String?, val bytes: ByteArray, private val size: Long? = bytes.size.toLong()) : PickedFile {
        override fun describe() = FileInfo(name, mime, size)
        override fun open() = java.io.ByteArrayInputStream(bytes)
    }

    private val notes = FakeFile("notes.txt", "text/plain", "line one\nline two\nline three\n".toByteArray())
    private val png = FakeFile("shot.png", "image/png", byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 1, 2, 3))

    private fun FakeBackend.cannedAttach() {
        transport.on(
            "file.attach",
            obj("""{"attached":true,"name":"notes.txt","path":"/w/attachments/notes.txt","ref_path":"attachments/notes.txt","ref_text":"@file:attachments/notes.txt","uploaded":true}"""),
        )
        transport.on("image.attach_bytes", obj("""{"attached":true,"path":"/h/images/upload_1.png","count":1,"width":1,"height":1}"""))
    }

    @Test
    fun attachFileUploadsAndAppendsRef() = runTest(dispatcher) {
        val b = backend().apply { cannedAttach() }
        val vm = vm(b)
        advanceUntilIdle()

        vm.attach(listOf(notes))
        advanceUntilIdle()

        val params = b.transport.last("file.attach")
        assertEquals(setOf("session_id", "data_url", "name"), params.keys)
        assertEquals(live, params["session_id"]!!.jsonPrimitive.content)
        assertEquals("notes.txt", params["name"]!!.jsonPrimitive.content)
        val dataUrl = params["data_url"]!!.jsonPrimitive.content
        assertTrue(dataUrl, dataUrl.startsWith("data:text/plain;base64,"))
        assertEquals(
            "line one\nline two\nline three\n",
            String(java.util.Base64.getDecoder().decode(dataUrl.substringAfter(","))),
        )
        val chip = vm.state.value.attachments.single()
        assertEquals(AttachmentStatus.Ready, chip.status)
        assertEquals(AttachmentKind.File, chip.kind)
        assertEquals("@file:attachments/notes.txt", chip.refText)

        vm.setInput("hi")
        vm.send()
        advanceUntilIdle()
        // Desktop buildContextText: refs joined by "\n", then a blank line, then the typed text.
        assertEquals("@file:attachments/notes.txt\n\nhi", b.transport.last("prompt.submit")["text"]!!.jsonPrimitive.content)
        assertTrue(vm.state.value.attachments.isEmpty())
        val bubble = vm.state.value.chat.items.single() as ChatItem.UserMessage
        assertEquals("hi", bubble.text)
        assertEquals(listOf("notes.txt"), bubble.attachments)
    }

    @Test
    fun attachImageQueuesNoTextRef() = runTest(dispatcher) {
        val b = backend().apply { cannedAttach() }
        val vm = vm(b)
        advanceUntilIdle()

        vm.attach(listOf(png))
        advanceUntilIdle()
        val params = b.transport.last("image.attach_bytes")
        assertEquals(setOf("session_id", "content_base64", "filename"), params.keys)
        assertEquals(java.util.Base64.getEncoder().encodeToString(png.bytes), params["content_base64"]!!.jsonPrimitive.content)
        assertEquals("shot.png", params["filename"]!!.jsonPrimitive.content)
        val chip = vm.state.value.attachments.single()
        assertEquals(AttachmentKind.Image, chip.kind)
        assertEquals(AttachmentStatus.Ready, chip.status)
        assertEquals("/h/images/upload_1.png", chip.serverPath)
        assertNull(chip.refText)
        assertFalse("file.attach" in b.transport.methods())

        assertTrue(vm.state.value.canSend)
        vm.send() // no text: the image alone goes with a stock prompt, and no @file ref
        advanceUntilIdle()
        assertEquals("See the attached image.", b.transport.last("prompt.submit")["text"]!!.jsonPrimitive.content)
        assertTrue(vm.state.value.attachments.isEmpty())
    }

    @Test
    fun oversizedAttachmentRejected() = runTest(dispatcher) {
        assertFalse(Attachments.tooLarge(Attachments.MAX_BYTES.toLong()))
        assertTrue(Attachments.tooLarge(Attachments.MAX_BYTES + 1L))
        assertFalse("unknown size: checked while reading", Attachments.tooLarge(null))
        // A 12 MB file must still fit OkHttp's 16 MiB WebSocket send queue once base64-encoded in its frame.
        assertTrue((Attachments.MAX_BYTES + 2L) / 3 * 4 + 1024 < 16L * 1024 * 1024)

        val b = backend().apply { cannedAttach() }
        val vm = vm(b)
        advanceUntilIdle()
        val big = ByteArray(Attachments.MAX_BYTES + 1)
        vm.attach(listOf(FakeFile("big.bin", "application/octet-stream", big)))
        vm.attach(listOf(FakeFile("sizeless.bin", null, big, size = null))) // caught while reading
        advanceUntilIdle()
        assertEquals(listOf("File too large (max 12 MB)", "File too large (max 12 MB)"), b.toasts)
        assertTrue(vm.state.value.attachments.isEmpty())
        assertFalse("file.attach" in b.transport.methods())
    }

    @Test
    fun removeImageCallsDetach() = runTest(dispatcher) {
        val b = backend().apply { cannedAttach() }
        val vm = vm(b)
        advanceUntilIdle()
        vm.attach(listOf(png, notes))
        advanceUntilIdle()
        val (image, file) = vm.state.value.attachments

        vm.removeAttachment(image.localId)
        advanceUntilIdle()
        val params = b.transport.last("image.detach")
        assertEquals(setOf("session_id", "path"), params.keys)
        assertEquals(live, params["session_id"]!!.jsonPrimitive.content)
        assertEquals("/h/images/upload_1.png", params["path"]!!.jsonPrimitive.content)

        vm.removeAttachment(file.localId) // a file stays in the workspace: dropped locally only
        advanceUntilIdle()
        assertEquals(1, b.transport.methods().count { it == "image.detach" })
        assertTrue(vm.state.value.attachments.isEmpty())
    }

    @Test
    fun uploadsRunOneAtATime() = runTest(dispatcher) {
        // Two frames queued at once could exceed OkHttp's 16 MiB send queue and close the socket.
        val gate = CompletableDeferred<Unit>()
        val b = backend().apply {
            cannedAttach()
            transport.gateNext["image.attach_bytes"] = gate
        }
        val vm = vm(b)
        advanceUntilIdle()
        vm.attach(listOf(png, notes))
        advanceUntilIdle()
        assertTrue("image.attach_bytes" in b.transport.methods())
        assertFalse("second upload waits for the first", "file.attach" in b.transport.methods())
        assertEquals(listOf(AttachmentStatus.Uploading, AttachmentStatus.Uploading), vm.state.value.attachments.map { it.status })
        gate.complete(Unit)
        advanceUntilIdle()
        assertTrue("file.attach" in b.transport.methods())
        assertEquals(listOf(AttachmentStatus.Ready, AttachmentStatus.Ready), vm.state.value.attachments.map { it.status })
    }

    @Test
    fun newLiveSessionFailsQueuedImagesSoRetryRequeues() = runTest(dispatcher) {
        val b = backend().apply { cannedAttach() }
        val vm = vm(b)
        advanceUntilIdle()
        vm.attach(listOf(png, notes))
        advanceUntilIdle()
        assertEquals(live, vm.state.value.attachments.first().liveSessionId)

        // The server session expired: the resume comes back with a different live id.
        b.transport.on("session.resume", obj(resumeJson.replace(live, "beef0001")))
        b.transport.on("session.events.since", obj("""{"events":[],"latest_seq":0,"truncated":false,"open_requests":[]}"""))
        b.connection.value = ConnectionState.Reconnecting
        advanceUntilIdle()
        b.connection.value = ConnectionState.Connected
        advanceUntilIdle()
        assertEquals("beef0001", vm.state.value.chat.liveSessionId)
        val (image, file) = vm.state.value.attachments
        assertEquals("queued image was left in the old live session", AttachmentStatus.Failed, image.status)
        assertEquals("a file lives in the workspace, still valid", AttachmentStatus.Ready, file.status)

        vm.retryAttachment(image.localId)
        advanceUntilIdle()
        assertEquals("beef0001", b.transport.last("image.attach_bytes")["session_id"]!!.jsonPrimitive.content)
        assertEquals(AttachmentStatus.Ready, vm.state.value.attachments.first().status)
        assertEquals("beef0001", vm.state.value.attachments.first().liveSessionId)
    }

    @Test
    fun sendDisabledWithoutTextOrReadyAttachment() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val b = backend().apply {
            cannedAttach()
            transport.gateNext["file.attach"] = gate
        }
        val vm = vm(b)
        advanceUntilIdle()
        assertFalse(vm.state.value.canSend)
        vm.setInput("   ")
        assertFalse(vm.state.value.canSend)
        vm.send()
        advanceUntilIdle()
        assertFalse("prompt.submit" in b.transport.methods())

        vm.attach(listOf(notes))
        advanceUntilIdle()
        assertEquals(AttachmentStatus.Uploading, vm.state.value.attachments.single().status)
        assertFalse("an upload in flight holds Send", vm.state.value.canSend)
        gate.complete(Unit)
        advanceUntilIdle()
        assertTrue(vm.state.value.canSend)

        vm.setInput("hello")
        assertTrue(vm.state.value.canSend)
        b.hermes.value = HermesState(reachable = false)
        advanceUntilIdle()
        assertFalse(vm.state.value.canSend)
    }

    @Test
    fun failedUploadKeepsChipWithRetry() = runTest(dispatcher) {
        val b = backend().apply {
            cannedAttach()
            transport.failNext["file.attach"] = null
        }
        val vm = vm(b)
        advanceUntilIdle()
        vm.attach(listOf(notes))
        advanceUntilIdle()
        val chip = vm.state.value.attachments.single()
        assertEquals(AttachmentStatus.Failed, chip.status)
        assertTrue(b.toasts.any { it.startsWith("Couldn’t attach notes.txt") })
        assertFalse(vm.state.value.canSend)

        vm.retryAttachment(chip.localId)
        advanceUntilIdle()
        assertEquals(AttachmentStatus.Ready, vm.state.value.attachments.single().status)
        assertEquals(2, b.transport.methods().count { it == "file.attach" })
    }

    @Test
    fun attachWaitsForTheSessionToOpen() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val b = backend().apply {
            cannedAttach()
            transport.gateNext["session.create"] = gate
        }
        val vm = vm(b)
        advanceUntilIdle()
        assertNull(vm.state.value.chat.liveSessionId)

        vm.attach(listOf(notes))
        runCurrent() // not advanceUntilIdle: virtual time would run out the 30 s wait for the session
        assertEquals(AttachmentStatus.Uploading, vm.state.value.attachments.single().status)
        assertFalse("file.attach" in b.transport.methods())

        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("session.create", "file.attach"), b.transport.methods().filter { it == "session.create" || it == "file.attach" })
        assertEquals(live, b.transport.last("file.attach")["session_id"]!!.jsonPrimitive.content)
        assertEquals(AttachmentStatus.Ready, vm.state.value.attachments.single().status)
    }

    @Test
    fun failedSubmitKeepsAttachments() = runTest(dispatcher) {
        val b = backend().apply {
            cannedAttach()
            transport.failNext["prompt.submit"] = null
        }
        val vm = vm(b)
        advanceUntilIdle()
        vm.attach(listOf(notes))
        advanceUntilIdle()
        vm.setInput("hi")
        vm.send()
        advanceUntilIdle()
        val bubble = vm.state.value.chat.items.single() as ChatItem.UserMessage
        assertTrue(bubble.failed)
        assertTrue(vm.state.value.attachments.isEmpty())

        vm.retrySend(bubble.id)
        advanceUntilIdle()
        assertTrue("the retry carries the file ref", b.transport.last("prompt.submit")["text"]!!.jsonPrimitive.content.contains("@file:"))

        b.transport.failNext["prompt.submit"] = null
        vm.attach(listOf(notes))
        advanceUntilIdle()
        vm.setInput("again")
        vm.send()
        advanceUntilIdle()
        vm.dismissFailed(vm.state.value.chat.items.last().id)
        assertEquals("again", vm.state.value.input)
        assertEquals(listOf(AttachmentStatus.Ready), vm.state.value.attachments.map { it.status })
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
    fun reconnectBeforeAnyEventAsksForSeqZeroNotTheSentinel() = runTest(dispatcher) {
        // A negative `last_seen` reads as "evicted" on the server and would force a rebuild from history.
        val b = backend()
        val vm = vm(b)
        advanceUntilIdle()
        vm.setInput("hi")
        vm.send()
        advanceUntilIdle()
        b.transport.on("session.resume", obj(resumeJson.replace("\"running\":false", "\"running\":true")))
        b.transport.on("session.events.since", obj("""{"events":[],"latest_seq":0,"truncated":false,"open_requests":[]}"""))
        b.connection.value = ConnectionState.Reconnecting
        advanceUntilIdle()
        b.connection.value = ConnectionState.Connected
        advanceUntilIdle()
        assertEquals(0L, b.transport.last("session.events.since")["last_seen"]!!.jsonPrimitive.long)
        assertTrue("the optimistic bubble survives", vm.state.value.chat.items.any { it is ChatItem.UserMessage })
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
    fun pickingModelSwitchesTheLiveSession() = runTest(dispatcher) {
        val b = backend().apply {
            transport.on("config.set", obj("""{"key":"model","value":"deepseek-v4","warning":"","confirm_required":false,"confirm_message":"","scope":"session"}"""))
        }
        val vm = vm(b)
        advanceUntilIdle()

        vm.setModel("melious", "deepseek-v4")
        advanceUntilIdle()

        val params = b.transport.last("config.set")
        assertEquals(live, params["session_id"]!!.jsonPrimitive.content)
        assertEquals("model", params["key"]!!.jsonPrimitive.content)
        assertEquals("deepseek-v4 --provider melious --session", params["value"]!!.jsonPrimitive.content)
        assertNull(params["confirm_expensive_model"])
        assertFalse("no re-create any more", "session.close" in b.transport.methods())
        assertEquals(1, b.transport.methods().count { it == "session.create" })
        assertEquals("deepseek-v4", vm.state.value.model)
        assertEquals("melious", vm.state.value.provider)
        assertEquals("deepseek-v4", b.settings.value?.model)
        assertEquals("melious", b.settings.value?.modelProvider)
        assertTrue(b.toasts.isEmpty())
    }

    @Test
    fun modelSwitchWarningAndDeferralAreToasted() = runTest(dispatcher) {
        val b = backend().apply {
            transport.on(
                "config.set",
                obj("""{"key":"model","value":"big","warning":"Context will be compressed first.\nDetails…","confirm_required":false}"""),
                obj("""{"key":"model","value":"big","deferred":true}"""),
            )
        }
        val vm = vm(b)
        advanceUntilIdle()
        vm.setModel("p", "big")
        advanceUntilIdle()
        vm.setModel("p", "big")
        advanceUntilIdle()
        assertEquals(listOf("Context will be compressed first.", "Model switches when this turn ends"), b.toasts)
    }

    @Test
    fun modelThatNeedsConfirmationAsksFirst() = runTest(dispatcher) {
        val b = backend().apply {
            transport.on(
                "config.set",
                obj("""{"key":"model","value":"opus","confirm_required":true,"confirm_message":"opus costs $75/M output tokens."}"""),
                obj("""{"key":"model","value":"opus","confirm_required":false}"""),
            )
        }
        val vm = vm(b)
        advanceUntilIdle()
        vm.setModel("anthropic", "opus")
        advanceUntilIdle()

        assertEquals(ChatDialog.ModelConfirm, vm.state.value.dialog)
        assertEquals(PendingModel("anthropic", "opus", "opus costs $75/M output tokens."), vm.state.value.pendingModel)
        assertEquals("nothing saved before the confirmation", "m-1", b.settings.value?.model)

        vm.confirmModel()
        advanceUntilIdle()
        assertEquals("true", b.transport.last("config.set")["confirm_expensive_model"]!!.jsonPrimitive.content)
        assertNull(vm.state.value.dialog)
        assertEquals("opus", b.settings.value?.model)
        assertEquals("opus", vm.state.value.model)
    }

    @Test
    fun dismissedModelConfirmationSwitchesNothing() = runTest(dispatcher) {
        val b = backend().apply { transport.on("config.set", obj("""{"value":"opus","confirm_required":true,"confirm_message":"why"}""")) }
        val vm = vm(b)
        advanceUntilIdle()
        vm.setModel("anthropic", "opus")
        advanceUntilIdle()
        vm.showDialog(null)
        vm.confirmModel()
        advanceUntilIdle()
        assertEquals(1, b.transport.methods().count { it == "config.set" })
        assertNull(vm.state.value.pendingModel)
    }

    @Test
    fun pickingModelWithoutALiveSessionAppliesToNextChat() = runTest(dispatcher) {
        val b = backend().apply { transport.failing += "session.create" }
        val vm = vm(b)
        advanceUntilIdle()

        vm.setModel("melious", "deepseek-v4")
        advanceUntilIdle()
        assertFalse("config.set" in b.transport.methods())
        assertEquals("deepseek-v4", b.settings.value?.model)
        assertEquals("melious", b.settings.value?.modelProvider)
        assertEquals(listOf("Model applies to the next chat"), b.toasts)
    }

    @Test
    fun failedModelSwitchKeepsTheOldModel() = runTest(dispatcher) {
        val b = backend()
        val vm = vm(b)
        advanceUntilIdle()
        b.transport.failNext["config.set"] = null
        vm.setModel("nope", "no-such-model")
        advanceUntilIdle()
        assertEquals("m-1", vm.state.value.model)
        assertEquals("m-1", b.settings.value?.model)
        assertTrue(b.toasts.single().startsWith("Couldn’t switch the model"))
    }

    @Test
    fun reasoningSetsTheSessionScopedEffort() = runTest(dispatcher) {
        val b = backend().apply { transport.on("config.set", obj("""{"key":"reasoning","value":"low"}""")) }
        val vm = vm(b)
        advanceUntilIdle()

        vm.setReasoning("low")
        advanceUntilIdle()
        val params = b.transport.last("config.set")
        assertEquals("reasoning", params["key"]!!.jsonPrimitive.content)
        assertEquals("low", params["value"]!!.jsonPrimitive.content)
        assertEquals(live, params["session_id"]!!.jsonPrimitive.content)
        assertNull("session scope: never the profile default", params["scope"])
        assertEquals("low", vm.state.value.chat.reasoningEffort)
        assertNull(vm.state.value.menu)

        // The server's session.info is the source of truth from then on.
        b.events.emit(ev("session.info", 7, """{"model":"m-1","reasoning_effort":"high"}"""))
        advanceUntilIdle()
        assertEquals("high", vm.state.value.chat.reasoningEffort)
        assertEquals("Reasoning · High", reasoningMenuLabel(vm.state.value.chat.reasoningEffort))
        assertEquals("Reasoning", reasoningMenuLabel(""))
    }

    @Test
    fun compactShowsProgressThenTheHeadline() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val b = backend().apply {
            transport.on(
                "session.compress",
                obj(
                    """{"status":"compressed","removed":6,"before_messages":8,"after_messages":2,
                       "summary":{"noop":false,"headline":"Compressed 8 → 2 messages"},
                       "info":{"stored_session_id":"20261020_030000_bbbbbb","usage":{"total":900,"context_percent":1}}}""",
                ),
            )
            transport.gateNext["session.compress"] = gate
        }
        val vm = vm(b)
        advanceUntilIdle()

        vm.compact()
        advanceUntilIdle()
        assertEquals(AgentStatus.Compacting, vm.state.value.status)
        assertFalse("compaction is not a turn: no Stop", vm.state.value.status.running)
        vm.compact() // ignored while one runs
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, b.transport.methods().count { it == "session.compress" })
        assertEquals(live, b.transport.last("session.compress")["session_id"]!!.jsonPrimitive.content)
        assertEquals(AgentStatus.Idle, vm.state.value.status)
        assertEquals(listOf("Compressed 8 → 2 messages"), b.toasts)
        assertEquals("compaction rotated the stored id", "20261020_030000_bbbbbb", vm.state.value.chat.storedSessionId)
        assertEquals(900L, vm.state.value.chat.usage?.totalTokens)
    }

    @Test
    fun compactOutcomesReadTheServerMessage() {
        assertEquals("Compaction is already running elsewhere", ChatSync.compacted(obj("""{"compressed":false,"lock_held":true}""")).message)
        assertEquals("busy", ChatSync.compacted(obj("""{"compressed":false,"message":"busy"}""")).message)
        assertEquals("later", ChatSync.compacted(obj("""{"status":"pending","message":"later"}""")).message)
        assertEquals("Context compacted", ChatSync.compacted(obj("""{"status":"compressed"}""")).message)
        // Live-probed: a refused compression is still `compressed` with noop + a headline saying why.
        val noop = ChatSync.compacted(
            obj("""{"status":"compressed","removed":0,"summary":{"noop":true,"headline":"Compression refused (summary would grow the conversation): 8 messages preserved"},"info":{"stored_session_id":""}}"""),
        )
        assertEquals("Compression refused (summary would grow the conversation): 8 messages preserved", noop.message)
        assertNull(noop.storedId)
    }

    @Test
    fun failedCompactionToastsAndClearsTheStatus() = runTest(dispatcher) {
        val b = backend()
        val vm = vm(b)
        advanceUntilIdle()
        b.transport.failNext["session.compress"] = null
        vm.compact()
        advanceUntilIdle()
        assertFalse(vm.state.value.compacting)
        assertEquals(AgentStatus.Idle, vm.state.value.status)
        assertTrue(b.toasts.single().startsWith("Couldn’t compact the context"))
    }

    @Test
    fun archivedSessionIsReadOnlyUntilUnarchived() = runTest(dispatcher) {
        val b = backend().apply {
            transport.on("session.resume", obj(resumeJson))
            archived = true
        }
        val vm = vm(b, stored)
        advanceUntilIdle()

        assertTrue(vm.state.value.archived)
        assertFalse(vm.state.value.writable)
        vm.setInput("hello")
        assertFalse(vm.state.value.canSend)
        vm.send()
        advanceUntilIdle()
        assertFalse("prompt.submit" in b.transport.methods())

        vm.unarchive()
        advanceUntilIdle()
        assertEquals(listOf("unarchive $stored"), b.rest)
        assertFalse(vm.state.value.archived)
        assertTrue(vm.state.value.canSend)
        assertEquals(listOf("Unarchived"), b.toasts)
    }

    @Test
    fun aNewChatIsNeverArchived() = runTest(dispatcher) {
        val b = backend().apply { archived = true }
        val vm = vm(b)
        advanceUntilIdle()
        assertFalse(vm.state.value.archived)
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
    fun sendFailureMarksTheBubbleAndRetryResubmits() = runTest(dispatcher) {
        val b = backend()
        val vm = vm(b)
        advanceUntilIdle()
        b.transport.failNext["prompt.submit"] = null
        vm.setInput("hello")
        vm.send()
        advanceUntilIdle()
        val failed = vm.state.value.chat.items.single() as ChatItem.UserMessage
        assertTrue(failed.failed)
        assertEquals("hello", failed.text)
        assertEquals("the text waits on the bubble, not twice in the composer", "", vm.state.value.input)
        assertEquals(AgentStatus.Idle, vm.state.value.chat.status)
        assertTrue(b.toasts.single().startsWith("Message not sent"))

        vm.retrySend(failed.id)
        assertEquals(AgentStatus.Thinking("flair"), vm.state.value.chat.status)
        advanceUntilIdle()
        assertEquals(2, b.transport.methods().count { it == "prompt.submit" })
        assertEquals("hello", b.transport.last("prompt.submit")["text"]!!.jsonPrimitive.content)
        val sent = vm.state.value.chat.items.single() as ChatItem.UserMessage
        assertFalse(sent.failed)
        assertEquals(failed.id, sent.id)

        vm.retrySend(failed.id) // nothing left to retry
        advanceUntilIdle()
        assertEquals(2, b.transport.methods().count { it == "prompt.submit" })
    }

    @Test
    fun failedSendSurvivesAReconnectThatRebuildsTheTranscript() = runTest(dispatcher) {
        val b = backend()
        val vm = vm(b)
        advanceUntilIdle()
        b.transport.failNext["prompt.submit"] = null
        vm.setInput("hello")
        vm.send()
        advanceUntilIdle()
        val failed = vm.state.value.chat.items.single() as ChatItem.UserMessage
        assertTrue(failed.failed)

        // The server session expired: the resume comes back with a new live id and the transcript is rebuilt.
        b.transport.on("session.resume", obj(resumeJson.replace(live, "beef0001")))
        b.transport.on("session.events.since", obj("""{"events":[],"latest_seq":0,"truncated":false,"open_requests":[]}"""))
        b.connection.value = ConnectionState.Reconnecting
        advanceUntilIdle()
        b.connection.value = ConnectionState.Connected
        advanceUntilIdle()
        assertEquals("beef0001", vm.state.value.chat.liveSessionId)
        val kept = vm.state.value.chat.items.last() as ChatItem.UserMessage
        assertEquals(failed.id, kept.id)
        assertTrue("the unsent text is still on its bubble", kept.failed)
        assertEquals("hello", kept.text)

        vm.retrySend(failed.id)
        advanceUntilIdle()
        val submit = b.transport.last("prompt.submit")
        assertEquals("beef0001", submit["session_id"]!!.jsonPrimitive.content)
        assertEquals("hello", submit["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun sendIsHeldWhileCompacting() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val b = backend().apply {
            transport.on("session.compress", obj("""{"status":"compressed","summary":{"headline":"ok"},"usage":{"context_percent":3,"total":40}}"""))
            transport.gateNext["session.compress"] = gate
        }
        val vm = vm(b)
        advanceUntilIdle()
        vm.setInput("hello")
        assertTrue(vm.state.value.canSend)
        vm.compact()
        advanceUntilIdle()
        assertFalse("no prompt may race the compaction", vm.state.value.canSend)
        gate.complete(Unit)
        advanceUntilIdle()
        assertTrue(vm.state.value.canSend)
        assertEquals("the result's own usage drives the meter", 3, vm.state.value.chat.usage?.contextPercent)
    }

    @Test
    fun dismissingAFailedSendPutsItBackInTheComposer() = runTest(dispatcher) {
        val b = backend()
        val vm = vm(b)
        advanceUntilIdle()
        b.transport.failNext["prompt.submit"] = null
        vm.setInput("hello")
        vm.send()
        advanceUntilIdle()
        val id = vm.state.value.chat.items.single().id

        vm.dismissFailed(id)
        assertTrue(vm.state.value.chat.items.isEmpty())
        assertEquals("hello", vm.state.value.input)
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
        assertTrue((vm.state.value.chat.items.single() as ChatItem.UserMessage).failed)
        val toasts = b.toasts.size
        vm.retrySend(vm.state.value.chat.items.single().id)
        assertEquals(listOf("Not connected to Hermes"), b.toasts.drop(toasts))
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

    // ---- MEDIA: file cards ------------------------------------------------------------------------

    private val probe = "/home/hermes/workspace/output/probe.txt"

    @Test
    fun tappingAMediaCardDownloadsSavesAndOpens() = runTest(dispatcher) {
        val b = backend()
        val vm = vm(b)
        advanceUntilIdle()

        vm.openMedia(probe)
        assertEquals(MediaStatus.Downloading, vm.state.value.mediaState[probe]?.status)
        runCurrent()
        assertEquals(listOf(probe), b.downloads)
        assertEquals(listOf(Triple("probe.txt", "text/plain", "abc")), b.saved)
        assertEquals(listOf("Saved to Downloads"), b.toasts)
        assertEquals(listOf("content://media/external/downloads/1" to "text/plain"), b.opened)
        assertEquals(MediaStatus.Saved, vm.state.value.mediaState[probe]?.status)

        advanceUntilIdle() // "Saved" goes back to idle
        assertNull(vm.state.value.mediaState[probe])
    }

    @Test
    fun failedDownloadShowsReasonAndRetries() = runTest(dispatcher) {
        val b = backend().apply { downloadResult = { Result.failure(ApiException(404, "File no longer exists on the server")) } }
        val vm = vm(b)
        advanceUntilIdle()

        vm.openMedia(probe)
        advanceUntilIdle()
        assertEquals(MediaState(MediaStatus.Failed, "File no longer exists on the server"), vm.state.value.mediaState[probe])
        assertEquals(listOf("Couldn’t download probe.txt: File no longer exists on the server"), b.toasts)
        assertTrue(b.saved.isEmpty() && b.opened.isEmpty())

        b.downloadResult = { Result.success(FileDownload("probe.txt", "text/plain", 3, Buffer().writeUtf8("abc"))) }
        vm.openMedia(probe) // tap on a failed card retries
        runCurrent()
        assertEquals(2, b.downloads.size)
        assertEquals(MediaStatus.Saved, vm.state.value.mediaState[probe]?.status)
    }

    @Test
    fun tapWhileDownloadingIsIgnored() = runTest(dispatcher) {
        val b = backend().apply { downloadGate = CompletableDeferred() }
        val vm = vm(b)
        advanceUntilIdle()

        vm.openMedia(probe)
        runCurrent()
        vm.openMedia(probe)
        runCurrent()
        assertEquals(1, b.downloads.size)
        b.downloadGate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, b.saved.size)
    }

    @Test
    fun imagePreviewIsFetchedOnceAndCached() = runTest(dispatcher) {
        val png = "/srv/out/chart.png"
        val b = backend().apply {
            downloadResult = { Result.success(FileDownload("chart.png", "image/png", 4, Buffer().write(byteArrayOf(1, 2, 3, 4)))) }
        }
        val vm = vm(b)
        advanceUntilIdle()

        val first = async { vm.preview(png) }
        val second = async { vm.preview(png) } // concurrent: shares the fetch
        advanceUntilIdle()
        assertEquals(FakeBitmap, first.await())
        assertEquals(FakeBitmap, second.await())
        assertEquals(FakeBitmap, vm.preview(png))
        assertEquals(listOf(png), b.downloads)
        assertEquals(1, b.decoded)

        assertNull(vm.preview(probe)) // not an image: no fetch
        assertEquals(1, b.downloads.size)
    }

    @Test
    fun oversizedImageGetsNoPreview() = runTest(dispatcher) {
        val png = "/srv/out/huge.png"
        val b = backend().apply {
            downloadResult = { Result.success(FileDownload("huge.png", "image/png", Media.PREVIEW_MAX_BYTES + 1, Buffer())) }
        }
        val vm = vm(b)
        advanceUntilIdle()
        assertNull(vm.preview(png))
        assertNull(vm.preview(png))
        assertEquals(1, b.downloads.size) // the verdict is cached
        assertEquals(0, b.decoded)
    }

    // --- v0.4.0 -----------------------------------------------------------------------------------

    @Test
    fun stopInterruptsTheLiveSessionAndCompleteEndsTheTurn() = runTest(dispatcher) {
        val b = backend().apply { transport.on("session.interrupt", obj("""{"status":"interrupted"}""")) }
        val vm = vm(b)
        advanceUntilIdle()
        vm.setInput("count to 100")
        vm.send()
        b.events.emit(ev("message.start", 1))
        b.events.emit(ev("message.delta", 2, """{"text":"1, 2"}"""))
        advanceUntilIdle()
        assertTrue(vm.state.value.chat.status.running)

        vm.interrupt()
        advanceUntilIdle()
        assertEquals(live, b.transport.last("session.interrupt")["session_id"]!!.jsonPrimitive.content)
        assertTrue("no toast when it stopped", b.toasts.isEmpty())
        // The server ends the turn itself; the status (and the send button) flip back.
        b.events.emit(ev("message.complete", 3, """{"text":"1, 2","status":"interrupted"}"""))
        advanceUntilIdle()
        assertEquals(AgentStatus.Idle, vm.state.value.chat.status)
        assertFalse(vm.state.value.chat.status.running)
    }

    @Test
    fun stopThenErrorEndsTheTurnToo() = runTest(dispatcher) {
        val b = backend().apply { transport.on("session.interrupt", obj("""{"status":"interrupted"}""")) }
        val vm = vm(b)
        advanceUntilIdle()
        b.events.emit(ev("message.start", 1))
        b.events.emit(ev("tool.start", 2, """{"tool_id":"t1","name":"terminal"}"""))
        advanceUntilIdle()
        assertTrue(vm.state.value.chat.status.running)
        vm.interrupt()
        b.events.emit(ev("error", 3, """{"message":"interrupted"}"""))
        advanceUntilIdle()
        assertEquals(AgentStatus.Idle, vm.state.value.chat.status)
        assertEquals(ToolStatus.Failed, (vm.state.value.chat.items.first { it is ChatItem.ToolCall } as ChatItem.ToolCall).status)
    }

    @Test
    fun stopWithNothingRunningToasts() = runTest(dispatcher) {
        val b = backend().apply { transport.on("session.interrupt", obj("""{"status":"not_interrupted"}""")) }
        val vm = vm(b)
        advanceUntilIdle()
        vm.showMenu(ChatMenu.Overflow)
        vm.interrupt()
        advanceUntilIdle()
        assertEquals(listOf("Nothing to stop"), b.toasts)
        assertNull("the overflow sheet closes", vm.state.value.menu)
    }

    @Test
    fun approvalScopeSendsTheChosenWireValue() = runTest(dispatcher) {
        val b = backend()
        val vm = vm(b)
        advanceUntilIdle()
        val full = """"choices":["once","session","always","deny"]"""
        b.serverRequests.emit(Frame.ServerRequest("srq-s", "approval", obj("""{"session_id":"$live","request_id":"r1","command":"ls",$full}""")))
        b.serverRequests.emit(Frame.ServerRequest("srq-a", "approval", obj("""{"session_id":"$live","request_id":"r2","command":"ls",$full}""")))
        b.serverRequests.emit(
            Frame.ServerRequest("srq-o", "approval", obj("""{"session_id":"$live","request_id":"r3","command":"ls","choices":["once","deny"]}""")),
        )
        advanceUntilIdle()

        vm.answerApproval("srq-s", ApprovalChoice.session)
        vm.answerApproval("srq-a", ApprovalChoice.always)
        vm.answerApproval("srq-o", ApprovalChoice.always) // not offered: ignored
        advanceUntilIdle()
        assertEquals(
            listOf("srq-s" to "session", "srq-a" to "always"),
            b.transport.responses.map { it.first to it.second["choice"]!!.jsonPrimitive.content },
        )
        assertEquals(ApprovalChoice.session, vm.state.value.decisions["srq-s"])
        assertEquals(ApprovalChoice.always, vm.state.value.decisions["srq-a"])
        assertFalse((vm.state.value.chat.items.last() as ChatItem.Approval).answered)
        assertEquals(AgentStatus.Approval, vm.state.value.chat.status)
    }

    private fun masked(id: String, method: String, params: String) =
        Frame.ServerRequest(id, method, obj("""{"session_id":"$live",$params}"""))

    @Test
    fun maskedPromptAnswersValueAndNeverKeepsIt() = runTest(dispatcher) {
        val b = backend()
        val vm = vm(b)
        advanceUntilIdle()
        b.serverRequests.emit(masked("srq-u", "sudo", """"command":"sudo -v""""))
        advanceUntilIdle()
        assertEquals(AgentStatus.Secret(password = true), vm.state.value.status)

        vm.answerSecret("srq-u", "hunter2")
        vm.answerSecret("srq-u", "hunter2") // double tap
        advanceUntilIdle()
        val (id, result) = b.transport.responses.single()
        assertEquals("srq-u", id)
        assertEquals(obj("""{"value":"hunter2"}"""), result)
        val card = vm.state.value.chat.items.single() as ChatItem.SecretPrompt
        assertEquals(SecretKind.Sudo, card.kind)
        assertTrue(card.answered)
        assertFalse(card.declined)
        assertTrue("srq-u" in b.answeredRequestIds)
        assertFalse("the value is nowhere in the UI state", vm.state.value.toString().contains("hunter2"))
        assertTrue(b.toasts.none { it.contains("hunter2") })
        assertTrue(vm.state.value.status is AgentStatus.Thinking)
    }

    @Test
    fun maskedPromptCancelAnswersEmptyValue() = runTest(dispatcher) {
        val b = backend()
        val vm = vm(b)
        advanceUntilIdle()
        b.serverRequests.emit(masked("srq-s", "secret", """"env_var":"API_KEY","prompt":"Key?""""))
        advanceUntilIdle()
        assertEquals("Waiting for a secret", vm.state.value.status.label)

        vm.declineSecret("srq-s")
        advanceUntilIdle()
        assertEquals("srq-s" to obj("""{"value":""}"""), b.transport.responses.single())
        assertTrue((vm.state.value.chat.items.single() as ChatItem.SecretPrompt).declined)
    }

    @Test
    fun maskedPromptNotSentReopensTheCard() = runTest(dispatcher) {
        val b = backend()
        val vm = vm(b)
        advanceUntilIdle()
        b.serverRequests.emit(masked("srq-v", "vault.unlock_prompt", """"backend":"bw","display_name":"Bitwarden""""))
        advanceUntilIdle()
        b.transport.respondOk = false
        vm.answerSecret("srq-v", "s3cret")
        advanceUntilIdle()
        val card = vm.state.value.chat.items.single() as ChatItem.SecretPrompt
        assertFalse(card.answered)
        assertFalse("srq-v" in b.answeredRequestIds)
        assertEquals(AgentStatus.Secret(password = true), vm.state.value.status)
        assertEquals(listOf("Couldn’t send the answer: not connected to Hermes"), b.toasts)
        assertFalse(vm.state.value.toString().contains("s3cret"))
    }

    @Test
    fun requestCancelClearsMaskedPromptAndReplayIsNotAdopted() = runTest(dispatcher) {
        val b = backend()
        val vm = vm(b)
        advanceUntilIdle()
        b.serverRequests.emit(masked("srq-u", "sudo", """"command":"sudo -v""""))
        advanceUntilIdle()
        b.events.emit(ev("request.cancel", 3, """{"id":"srq-u","method":"sudo","reason":"timeout"}"""))
        b.closedRequestIds.add("srq-u") // what the router records for the same event
        advanceUntilIdle()
        assertTrue(vm.state.value.chat.items.isEmpty())
        assertFalse(vm.state.value.status is AgentStatus.Secret)

        // A later screen for the same chat does not bring the withdrawn prompt back from the replay.
        val again = vm(b)
        advanceUntilIdle()
        assertTrue(again.state.value.chat.items.none { it is ChatItem.SecretPrompt })
    }

    @Test
    fun resumeReadoptsOpenMaskedPrompt() = runTest(dispatcher) {
        val b = backend().apply {
            transport.on(
                "session.resume",
                obj(
                    """{"session_id":"$live","stored_session_id":"$stored","running":true,"messages":[],
                       "open_requests":[{"id":"srq-u","method":"sudo","params":{"session_id":"old","command":"sudo -v"}}]}""",
                ),
            )
        }
        val vm = vm(b, stored)
        advanceUntilIdle()
        val card = vm.state.value.chat.items.single() as ChatItem.SecretPrompt
        assertEquals("srq-u", card.id)
        assertEquals("sudo -v", card.subject)
        assertEquals(AgentStatus.Secret(password = true), vm.state.value.status)
    }

    @Test
    fun resumeOfARunningTurnShowsThePartialReplyAndContinuesIt() = runTest(dispatcher) {
        val b = backend().apply {
            transport.on(
                "session.resume",
                obj(
                    """{"session_id":"$live","stored_session_id":"$stored","running":true,
                       "messages":[{"role":"user","text":"count to 30","row_id":1}],
                       "inflight":{"assistant":"1\n2\n3\n","streaming":true,"user":"count to 30"}}""",
                ),
            )
            transport.on("session.events.since", obj("""{"events":[],"latest_seq":40,"truncated":false,"open_requests":[]}"""))
        }
        val vm = vm(b, stored)
        advanceUntilIdle()
        val partial = vm.state.value.chat.items.last() as ChatItem.AssistantMessage
        assertEquals("1\n2\n3\n", partial.text)
        assertTrue(partial.streaming)
        assertEquals(40L, vm.state.value.chat.lastSeq)

        b.events.emit(ev("message.delta", 41, """{"text":"4\n"}"""))
        advanceUntilIdle()
        val items = vm.state.value.chat.items
        assertEquals(2, items.size)
        assertEquals("1\n2\n3\n4\n", (items.last() as ChatItem.AssistantMessage).text)

        // Not running: the inflight snapshot (a retained turn) is not shown as a live reply.
        val idle = backend().apply {
            transport.on("session.resume", obj("""{"session_id":"$live","stored_session_id":"$stored","running":false,"messages":[],
                "inflight":{"assistant":"stale","streaming":false}}"""))
        }
        val vm2 = vm(idle, stored)
        advanceUntilIdle()
        assertTrue(vm2.state.value.chat.items.isEmpty())
    }

    @Test
    fun copySessionIdFallsBackToTheLiveIdOnABrandNewChat() = runTest(dispatcher) {
        val b = backend().apply {
            transport.on("session.create", obj("""{"session_id":"$live","info":{},"messages":[]}""")) // no stored id yet
        }
        val vm = vm(b)
        advanceUntilIdle()
        var copied: String? = null
        vm.copySessionId { copied = it }
        assertEquals(live, copied)
        assertEquals(listOf("Session ID copied"), b.toasts)
    }

    @Test
    fun copyMessageCopiesTextOrReasoning() = runTest(dispatcher) {
        val b = backend()
        val vm = vm(b)
        advanceUntilIdle()
        b.events.emit(ev("message.start", 1))
        b.events.emit(ev("reasoning.delta", 2, """{"text":"think first"}"""))
        b.events.emit(ev("message.delta", 3, """{"text":"the answer"}"""))
        b.events.emit(ev("message.complete", 4, """{"text":"the answer"}"""))
        advanceUntilIdle()
        val id = vm.state.value.chat.items.single().id
        val copied = mutableListOf<String>()

        vm.showMessageMenu(id)
        assertEquals(id, vm.state.value.messageMenu)
        vm.copyMessage(id, reasoning = false) { copied += it }
        assertNull(vm.state.value.messageMenu)
        vm.copyMessage(id, reasoning = true) { copied += it }
        vm.copyMessage("missing", reasoning = false) { copied += it }
        assertEquals(listOf("the answer", "think first"), copied)
        assertEquals(listOf("Message copied", "Reasoning copied"), b.toasts)
    }

    @Test
    fun notificationShowIsToastedOnceNotOnReplay() = runTest(dispatcher) {
        val b = backend()
        val vm = vm(b)
        advanceUntilIdle()
        val notice = ev("notification.show", 5, """{"text":"Stayed on m-1: switching needs confirmation.\nmore","level":"warn","kind":"ttl","key":"model_switch.dropped"}""")
        b.events.emit(notice)
        advanceUntilIdle()
        b.events.emit(notice) // replayed after a reconnect: at the watermark
        advanceUntilIdle()
        assertEquals(listOf("Stayed on m-1: switching needs confirmation."), b.toasts)
        assertEquals(5L, vm.state.value.chat.lastSeq)
    }

    @Test
    fun createAndResumeInfoSeedTheMeterAndEffort() = runTest(dispatcher) {
        val b = backend().apply {
            transport.on(
                "session.resume",
                obj(resumeJson.replace("\"running\":false", "\"running\":false,\"info\":{\"reasoning_effort\":\"medium\",\"usage\":{\"total\":3123456,\"context_used\":15754,\"context_max\":1000000,\"context_percent\":2}}")),
            )
        }
        val vm = vm(b, stored)
        advanceUntilIdle()
        assertEquals("medium", vm.state.value.chat.reasoningEffort)
        assertEquals("the cumulative total (3.1M) is not the meter", "15.8k / 1M ctx · 2%", vm.state.value.chat.usage?.meter)
    }

    // ---- voice ------------------------------------------------------------------------------------

    @Test
    fun openVoiceListensAndSendsWhatWasHeard() = runTest(dispatcher) {
        val b = backend()
        val vm = vm(b)
        advanceUntilIdle()

        vm.openVoice()
        advanceUntilIdle()
        assertEquals(VoicePhase.Listening, vm.state.value.voice?.phase)
        assertTrue(b.speech.active)
        b.speech.emit(SpeechEvent.Final("hello"))
        advanceUntilIdle()

        assertEquals("hello", b.transport.last("prompt.submit")["text"]!!.jsonPrimitive.content)
        assertEquals("hello", (vm.state.value.chat.items.single() as ChatItem.UserMessage).text)
        assertEquals(VoicePhase.Thinking, vm.state.value.voice?.phase)
        assertFalse(b.speech.active)

        vm.openVoice() // already open: nothing restarts
        advanceUntilIdle()
        assertEquals(1, b.speech.listenCount)
    }

    @Test
    fun assistVoiceOpenedWhileResumingStartsAfterTheHistory() = runTest(dispatcher) {
        val b = backend().apply { transport.on("session.resume", obj(resumeJson)) }
        val gate = CompletableDeferred<Unit>()
        b.transport.gateNext["session.resume"] = gate
        val vm = vm(b, stored)
        advanceUntilIdle()

        vm.openVoice(VoiceMode.Assist)
        advanceUntilIdle()
        assertEquals(VoicePhase.Idle, vm.state.value.voice?.phase) // the sheet's resting look meanwhile
        assertFalse(b.speech.active)

        gate.complete(Unit)
        advanceUntilIdle()
        val s = vm.state.value
        assertEquals(2, s.chat.items.size)
        assertEquals(s.chat.items.size, s.voice?.startIndex)
        assertTrue("nothing from history is spoken", b.speechOutput.spoken.isEmpty())
        assertEquals(VoicePhase.Listening, s.voice?.phase)
        assertTrue(b.speech.active)
    }

    @Test
    fun assistVoiceWaitsForAResumeRetriedOnConnected() = runTest(dispatcher) {
        val b = backend().apply { transport.on("session.resume", obj(resumeJson)) }
        b.connection.value = ConnectionState.Disconnected
        b.transport.failNext["session.resume"] = null // not connected yet: the first open fails
        val vm = vm(b, stored)
        advanceUntilIdle()
        vm.openVoice(VoiceMode.Assist)
        advanceUntilIdle()
        assertFalse(vm.state.value.loading)
        assertFalse(b.speech.active)

        b.connection.value = ConnectionState.Connected
        advanceUntilIdle()
        val s = vm.state.value
        assertEquals(2, s.chat.items.size)
        assertEquals(2, s.voice?.startIndex)
        assertTrue(b.speechOutput.spoken.isEmpty())
    }

    @Test
    fun assistVoiceShowsWhyAChatThatFailedToOpenCannotListen() = runTest(dispatcher) {
        val b = backend().apply { transport.crashing += "session.resume" }
        val gate = CompletableDeferred<Unit>()
        b.transport.gateNext["session.resume"] = gate
        val vm = vm(b, stored)
        advanceUntilIdle()
        vm.openVoice(VoiceMode.Assist)
        advanceUntilIdle()
        assertNull(vm.state.value.voice?.error)

        gate.complete(Unit)
        advanceUntilIdle()
        val voice = vm.state.value.voice
        assertEquals("Couldn’t open the chat: boom", voice?.error)
        assertEquals(listOf("Couldn’t open the chat: boom"), b.toasts)
        vm.tapVoiceIndicator()
        advanceUntilIdle()
        assertEquals(0, b.speech.listenCount)
        assertTrue(b.speechOutput.spoken.isEmpty())
    }

    @Test
    fun voiceWaitingForTheHistoryShowsNoTranscript() = runTest(dispatcher) {
        val b = backend().apply { transport.on("session.resume", obj(resumeJson)) }
        val gate = CompletableDeferred<Unit>()
        b.transport.gateNext["session.resume"] = gate
        val vm = vm(b, stored)
        advanceUntilIdle()
        vm.openVoice(VoiceMode.Assist)
        advanceUntilIdle()
        assertEquals(Int.MAX_VALUE, vm.state.value.voice?.startIndex)
    }

    @Test
    fun voiceOpenedWithoutListeningRestsUntilTapped() = runTest(dispatcher) {
        val b = backend()
        val vm = vm(b)
        advanceUntilIdle()

        vm.openVoice(VoiceMode.Assist, listen = false)
        advanceUntilIdle()
        assertEquals(VoicePhase.Idle, vm.state.value.voice?.phase)
        assertFalse(b.speech.active)

        vm.tapVoiceIndicator()
        advanceUntilIdle()
        assertEquals(VoicePhase.Listening, vm.state.value.voice?.phase)
        assertTrue(b.speech.active)
    }

    @Test
    fun closeVoiceStopsSpeechAndClearsState() = runTest(dispatcher) {
        val b = backend()
        val vm = vm(b)
        advanceUntilIdle()
        vm.openVoice()
        advanceUntilIdle()
        assertTrue(b.speech.active)

        vm.closeVoice()
        advanceUntilIdle()

        assertTrue(b.speechOutput.stopped > 0)
        assertFalse(b.speech.active)
        assertNull(vm.state.value.voice)
        assertFalse("session.interrupt" in b.transport.methods())
    }

    @Test
    fun sendTextRefusedWhenOffline() = runTest(dispatcher) {
        val b = backend()
        val vm = vm(b)
        advanceUntilIdle()
        b.connection.value = ConnectionState.Disconnected
        advanceUntilIdle()

        assertFalse(vm.sendText("x"))
        advanceUntilIdle()
        assertEquals(listOf("Not connected to Hermes"), b.toasts)
        assertFalse("prompt.submit" in b.transport.methods())
        assertTrue(vm.state.value.chat.items.isEmpty())
    }

    @Test
    fun voiceDeniedToasts() = runTest(dispatcher) {
        val b = backend()
        val vm = vm(b)
        advanceUntilIdle()
        vm.voiceDenied()
        assertEquals(listOf("Microphone access is needed for voice mode"), b.toasts)
        assertNull(vm.state.value.voice)
    }

    @Test
    fun voiceDoesNotReadARebuiltTranscriptAloud() = runTest(dispatcher) {
        val b = backend()
        val vm = vm(b)
        advanceUntilIdle()
        vm.openVoice()
        advanceUntilIdle()
        b.speech.emit(SpeechEvent.Final("what kernel?"))
        advanceUntilIdle()
        b.events.emit(ev("message.start", 1))
        b.events.emit(ev("message.complete", 2, """{"text":"6.17.2"}"""))
        advanceUntilIdle()
        assertEquals(listOf("6.17.2"), b.speechOutput.spoken)

        // The server session expired: the resume answers with a new live id and the transcript is rebuilt (new ids).
        b.transport.on("session.resume", obj(resumeJson.replace(live, "beef0001")))
        b.transport.on("session.events.since", obj("""{"events":[],"latest_seq":0,"truncated":false,"open_requests":[]}"""))
        b.connection.value = ConnectionState.Reconnecting
        advanceUntilIdle()
        b.connection.value = ConnectionState.Connected
        advanceUntilIdle()

        val s = vm.state.value
        assertEquals(listOf("h-1", "h-2"), s.chat.items.map { it.id })
        assertEquals("nothing is read aloud again", listOf("6.17.2"), b.speechOutput.spoken)
        assertEquals(0, s.voice?.startIndex)
        assertEquals("6.17.2".length, s.voice?.revealed?.get("h-2"))
        assertEquals(VoicePhase.Listening, s.voice?.phase)
    }

    @Test
    fun voiceWaitsWhileCompactingAndSendTextIsRefused() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val b = backend().apply {
            transport.on("session.compress", obj("""{"status":"compressed","summary":{"headline":"ok"}}"""))
            transport.gateNext["session.compress"] = gate
        }
        val vm = vm(b)
        advanceUntilIdle()
        vm.openVoice()
        advanceUntilIdle()
        assertTrue(b.speech.active)

        vm.compact()
        advanceUntilIdle()
        assertEquals(VoicePhase.Thinking, vm.state.value.voice?.phase)
        assertFalse("the mic is released while compacting", b.speech.active)
        assertFalse(vm.sendText("hello"))
        assertEquals("Compacting the context, try again in a moment", b.toasts.last())
        assertFalse("prompt.submit" in b.transport.methods())

        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(VoicePhase.Listening, vm.state.value.voice?.phase)
        assertTrue(b.speech.active)
    }

    @Test
    fun voiceRestsWhileTheGatewayIsOffline() = runTest(dispatcher) {
        val b = backend()
        val vm = vm(b)
        advanceUntilIdle()
        vm.openVoice()
        advanceUntilIdle()
        assertTrue(b.speech.active)

        b.hermes.value = HermesState(reachable = false)
        advanceUntilIdle()
        assertEquals(AgentStatus.Idle, vm.state.value.chat.status) // only the gateway knows
        assertFalse("the mic is released", b.speech.active)
        val s = vm.state.value
        assertEquals(VoicePhase.Idle, s.voice?.phase)
        assertEquals(StatusTone.Red, voiceLine(s.voice!!.phase, s.status, VoiceMode.InApp).tone)

        b.hermes.value = HermesState()
        advanceUntilIdle()
        assertEquals(VoicePhase.Listening, vm.state.value.voice?.phase)
        assertTrue(b.speech.active)
    }

    @Test
    fun voiceStartsWhenALaterOpenSucceedsAfterAFailedOne() = runTest(dispatcher) {
        val b = backend().apply {
            transport.on("session.resume", obj(resumeJson))
            transport.crashing += "session.resume"
        }
        val vm = vm(b, stored)
        advanceUntilIdle()
        vm.openVoice()
        advanceUntilIdle()
        assertEquals("Couldn’t open the chat: boom", vm.state.value.voice?.error)
        assertFalse(b.speech.active)

        // The next Connected retries the open, which now succeeds.
        b.transport.crashing -= "session.resume"
        b.connection.value = ConnectionState.Reconnecting
        advanceUntilIdle()
        b.connection.value = ConnectionState.Connected
        advanceUntilIdle()
        val s = vm.state.value
        assertEquals(2, s.chat.items.size)
        assertNull(s.voice?.error)
        assertEquals(2, s.voice?.startIndex)
        assertEquals(VoicePhase.Listening, s.voice?.phase)
        assertTrue(b.speech.active)
        assertTrue(b.speechOutput.spoken.isEmpty())
    }

    @Test
    fun clearingTheViewModelClosesVoice() = runTest(dispatcher) {
        val b = backend()
        val store = ViewModelStore()
        val factory = viewModelFactory { initializer { ChatViewModel(b, null, dispatcher = dispatcher, io = dispatcher, flair = { "flair" }) } }
        val vm = ViewModelProvider.create(store, factory)[ChatViewModel::class].also { created += it }
        advanceUntilIdle()
        vm.openVoice()
        advanceUntilIdle()
        assertTrue(b.speech.active)

        store.clear()
        advanceUntilIdle()
        assertNull(vm.state.value.voice)
        assertTrue(b.speechOutput.stopped > 0)
        assertFalse(b.speech.active)
    }

    @Test
    fun voiceLanguagePickPersistsToastsAndRestartsListening() = runTest(dispatcher) {
        val b = backend(Settings(workspace = "/srv/work", model = "m-1", voiceLanguage = "en-US"))
        val vm = vm(b)
        advanceUntilIdle()
        assertEquals("en-US", vm.state.value.voiceLanguage)
        vm.openVoice()
        advanceUntilIdle()
        assertEquals(1, b.speech.listenCount)

        vm.showMenu(ChatMenu.VoiceLanguage)
        advanceUntilIdle()
        assertEquals(b.voiceLanguages(), vm.state.value.voiceLanguages)

        vm.setVoiceLanguage("pl-PL")
        advanceUntilIdle()
        assertEquals("pl-PL", b.settings.value?.voiceLanguage)
        assertEquals("pl-PL", vm.state.value.voiceLanguage)
        assertNull(vm.state.value.menu)
        assertEquals("Voice language: Polish (Poland)", b.toasts.last())
        assertEquals(2, b.speech.listenCount) // the run in progress was restarted
        assertTrue(b.speech.active)

        vm.setVoiceLanguage(null)
        advanceUntilIdle()
        assertNull(b.settings.value?.voiceLanguage)
        assertEquals("Voice language: System default", b.toasts.last())
    }

    @Test
    fun voiceLanguagePickThatIsNotSavedRevertsAndSaysSo() = runTest(dispatcher) {
        val b = backend(Settings(workspace = "/srv/work", model = "m-1", voiceLanguage = "en-US"))
        val vm = vm(b)
        advanceUntilIdle()
        vm.openVoice()
        advanceUntilIdle()
        b.dropSettingsWrites = true

        vm.setVoiceLanguage("pl-PL")
        advanceUntilIdle() // past the settle timeout (virtual time)
        assertEquals("en-US", vm.state.value.voiceLanguage)
        assertEquals("Couldn’t save the voice language", b.toasts.last())
        assertEquals(1, b.speech.listenCount) // nothing restarted
    }

    @Test
    fun voiceLanguagePickWithoutVoiceOpenOnlyPersists() = runTest(dispatcher) {
        val b = backend()
        val vm = vm(b)
        advanceUntilIdle()
        vm.setVoiceLanguage("pl-PL")
        advanceUntilIdle()
        assertEquals("pl-PL", b.settings.value?.voiceLanguage)
        assertEquals(0, b.speech.listenCount)
    }
}
