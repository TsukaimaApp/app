package app.tsukaima.ui.chat

import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import app.tsukaima.data.HermesState
import app.tsukaima.data.RecentIds
import app.tsukaima.data.api.FileDownload
import app.tsukaima.data.api.HermesJson
import app.tsukaima.data.api.ModelOptionsDto
import app.tsukaima.data.api.TranscriptionDto
import app.tsukaima.data.api.VoiceLiveStatusDto
import app.tsukaima.data.api.WorkspacesDto
import app.tsukaima.data.files.FileSaver
import app.tsukaima.data.gateway.ConnectionState
import app.tsukaima.data.gateway.Frame
import app.tsukaima.data.gateway.RpcTransport
import app.tsukaima.data.prefs.Settings
import app.tsukaima.domain.chat.ChatItem
import app.tsukaima.domain.chat.queuesSends
import app.tsukaima.domain.livevoice.FakeLiveTransport
import app.tsukaima.domain.livevoice.LiveEvent
import app.tsukaima.domain.livevoice.LiveFragment
import app.tsukaima.domain.livevoice.LiveSpeaker
import app.tsukaima.domain.livevoice.LiveStartException
import app.tsukaima.domain.livevoice.LiveTransport
import app.tsukaima.domain.voice.FakeSpeechInput
import app.tsukaima.domain.voice.FakeSpeechOutput
import app.tsukaima.domain.voice.VoiceLanguage
import app.tsukaima.domain.voice.VoiceMode
import app.tsukaima.domain.voice.VoicePhase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

/**
 * The chat's engine choice on the Mic tap (GPT-Live when the server offers it, else the phone engine), the
 * voice-live turns the live engine submits ([ChatViewModel.sendLive]) and the live session's lifecycle ends.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelLiveVoiceTest {
    private val live = "d4fb0dea"

    private fun obj(s: String): JsonObject = HermesJson.parseToJsonElement(s).jsonObject

    private class Transport(private val results: Map<String, JsonObject>) : RpcTransport {
        val calls = mutableListOf<Pair<String, JsonObject>>()
        override suspend fun request(method: String, params: JsonObject): JsonObject {
            calls += method to params
            return results[method] ?: JsonObject(emptyMap())
        }
        override fun respond(id: String, result: JsonObject) = true
        override fun respondError(id: String, code: Int, message: String) = true
        fun last(method: String) = calls.last { it.first == method }.second
    }

    private class Backend(override val transport: Transport) : ChatBackend {
        override val events = MutableSharedFlow<Frame.Event>(extraBufferCapacity = 64)
        override val connection = MutableStateFlow(ConnectionState.Connected)
        override val serverRequests = MutableSharedFlow<Frame.ServerRequest>(replay = 16, extraBufferCapacity = 64)
        override val answeredRequestIds: MutableSet<String> = ConcurrentHashMap.newKeySet()
        override val closedRequestIds = RecentIds()
        override val hermes = MutableStateFlow(HermesState())
        override val settings = MutableStateFlow<Settings?>(Settings(workspace = "/w", model = "m-1"))
        override suspend fun updateSettings(block: (Settings) -> Settings) {
            settings.value = block(settings.value ?: Settings())
        }
        override suspend fun bindSession(live: String, stored: String?) = Unit
        override suspend fun modelOptions() = Result.success(ModelOptionsDto(model = "m-1", provider = "p"))
        override suspend fun workspaces() = Result.success(WorkspacesDto(defaultCwd = "/w"))
        override suspend fun renameSession(id: String, title: String) = Result.success(Unit)
        override suspend fun archiveSession(id: String) = Result.success(Unit)
        override suspend fun unarchiveSession(id: String) = Result.success(Unit)
        override suspend fun sessionArchived(id: String) = Result.success(false)
        override suspend fun deleteSession(id: String) = Result.success(Unit)
        override suspend fun download(path: String): Result<FileDownload> = offline()
        override suspend fun fetchImage(url: String): Result<ByteArray> = offline()
        override suspend fun media(path: String): Result<String> = offline()
        private fun <T> offline(): Result<T> = Result.failure(java.io.IOException("offline"))
        override val fileSaver = object : FileSaver {
            override fun save(name: String, mime: String, source: okio.BufferedSource): String = error("unused")
        }
        override fun openFile(uri: String, mime: String) = false
        override fun openUrl(url: String) = false
        override fun decodePreview(bytes: ByteArray): ImageBitmap? = null
        val toasts = mutableListOf<String>()
        override fun toast(text: String) {
            toasts += text
        }
        override suspend fun transcribe(wav: ByteArray): Result<TranscriptionDto> = offline()
        var speechInputs = 0
        override fun speechInput() = FakeSpeechInput().also { speechInputs++ }
        override fun speechOutput() = FakeSpeechOutput(instant = true)
        override fun voiceLanguages() = listOf(VoiceLanguage(null, "System default"))

        // ---- live voice ----
        override var liveVoiceEnabled = true
        var status: Result<VoiceLiveStatusDto> = Result.failure(java.io.IOException("404"))
        var statusGate: CompletableDeferred<Unit>? = null
        var statusCalls = 0
        override suspend fun voiceLiveStatus(): Result<VoiceLiveStatusDto> {
            statusCalls++
            statusGate?.await()
            return status
        }
        var nextTransport = FakeLiveTransport()
        val liveTransports = mutableListOf<FakeLiveTransport>()
        override fun liveTransport(): LiveTransport = nextTransport.also { liveTransports += it }

        /** "start" and "stop", in call order. */
        val service = mutableListOf<String>()
        override fun startLiveService() {
            service += "start"
        }
        override fun stopLiveService() {
            service += "stop"
        }
        override val liveVoiceEndRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        override val liveVoiceSeconds = MutableStateFlow(0)
    }

    private val dispatcher = StandardTestDispatcher()
    private val created = mutableListOf<ChatViewModel>()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() {
        // Cancel and join every VM scope before resetMain (the live meter loops until then).
        created.forEach { it.viewModelScope.cancel() }
        dispatcher.scheduler.advanceUntilIdle()
        created.forEach { check(it.viewModelScope.coroutineContext.job.isCompleted) { "VM scope still running" } }
        Dispatchers.resetMain()
    }

    /** Runs [body], then cancels the VMs before runTest drains the scheduler (a live meter loops until then). */
    private fun liveTest(body: suspend TestScope.() -> Unit) = runTest(dispatcher) {
        try {
            body()
        } finally {
            created.forEach { it.viewModelScope.cancel() }
        }
    }

    private fun backend() = Backend(
        Transport(
            mapOf(
                "session.create" to
                    obj("""{"session_id":"$live","stored_session_id":"s-1","info":{},"messages":[]}"""),
            ),
        ),
    )

    private fun gptLive(available: Boolean = true, voice: String = "marin") =
        Result.success(VoiceLiveStatusDto(mode = "gpt-live", available = available, voice = voice))

    private fun vm(b: Backend) =
        ChatViewModel(
            b, null, dispatcher = dispatcher, io = dispatcher, flair = { "flair" },
            now = { dispatcher.scheduler.currentTime },
        ).also { created += it }

    private fun ev(type: String, seq: Long, payload: String = "{}") = Frame.Event(type, live, seq, obj(payload))

    /** A chat with its session open and a live voice session started on it. */
    private fun TestScope.liveVoice(b: Backend): ChatViewModel {
        b.status = gptLive()
        val vm = vm(b)
        advanceUntilIdle()
        vm.openVoice()
        runCurrent()
        b.liveTransports.single().emit(LiveEvent.Started("ls-1"))
        runCurrent()
        return vm
    }

    @Test
    fun liveEngineWhenServerSaysGptLiveAndAvailable() = liveTest {
        val b = backend()
        b.status = gptLive()
        b.statusGate = CompletableDeferred()
        val vm = vm(b)
        advanceUntilIdle()
        vm.sendText("what kernel?")
        advanceUntilIdle()

        vm.openVoice()
        runCurrent()
        // The voice screen is up at once while the status call is in flight.
        assertEquals(VoicePhase.Idle, vm.state.value.voice?.phase)
        assertEquals("Connecting…", vm.state.value.voice?.caption)
        assertTrue(b.liveTransports.isEmpty())

        b.statusGate?.complete(Unit)
        runCurrent()
        val t = b.liveTransports.single()
        assertEquals(listOf("start"), b.service)
        assertEquals(0, b.speechInputs)
        assertEquals("marin", vm.state.value.voice?.live?.voice)
        assertEquals(listOf("what kernel?"), t.startedWith?.map { it.content.single().text })

        t.emit(LiveEvent.Started("ls-1"))
        runCurrent()
        assertEquals(VoicePhase.Listening, vm.state.value.voice?.phase)
        advanceTimeBy(3_100)
        runCurrent()
        assertEquals(3, b.liveVoiceSeconds.value)
        vm.openVoice() // already open: nothing restarts
        runCurrent()
        assertEquals(1, b.statusCalls)
        assertEquals(1, b.liveTransports.size)
    }

    @Test
    fun chainedWhenUnavailableOrChainedOrTimeoutOrSettingOff() = liveTest {
        val cases = listOf<Pair<String, (Backend) -> Unit>>(
            "unavailable" to { b -> b.status = gptLive(available = false) },
            "chained" to { b -> b.status = Result.success(VoiceLiveStatusDto(mode = "chained", available = true)) },
            "timeout" to { b ->
                b.status = gptLive()
                b.statusGate = CompletableDeferred() // never answers
            },
            "setting off" to { b ->
                b.status = gptLive()
                b.liveVoiceEnabled = false
            },
        )
        for ((name, setup) in cases) {
            val b = backend().also(setup)
            val vm = vm(b)
            advanceUntilIdle()
            vm.openVoice()
            advanceUntilIdle()
            assertEquals(name, 1, b.speechInputs)
            assertTrue(name, b.liveTransports.isEmpty())
            assertNull(name, vm.state.value.voice?.live)
            assertEquals(name, VoicePhase.Listening, vm.state.value.voice?.phase)
            assertTrue(name, b.toasts.isEmpty())
            assertEquals(name, if (name == "setting off") 0 else 1, b.statusCalls)
            vm.closeVoice()
            advanceUntilIdle()
        }
    }

    @Test
    fun assistModeIsAlwaysChained() = liveTest {
        val b = backend()
        b.status = gptLive()
        val vm = vm(b)
        advanceUntilIdle()
        vm.openVoice(VoiceMode.Assist)
        advanceUntilIdle()
        assertEquals(0, b.statusCalls)
        assertEquals(1, b.speechInputs)
    }

    @Test
    fun sendLiveIsNeverQueued() = liveTest {
        val b = backend()
        val vm = vm(b)
        advanceUntilIdle()
        vm.sendText("first")
        advanceUntilIdle()
        b.events.emit(ev("message.start", 1))
        b.events.emit(ev("message.delta", 2, """{"text":"Once"}"""))
        advanceUntilIdle()
        assertTrue(vm.state.value.chat.queuesSends)

        assertTrue(vm.sendLive("check my mail", "user: check my mail"))
        // The user bubble is in before sendLive returns (the controller counts the turn's items from it).
        assertEquals("check my mail", (vm.state.value.chat.items.last() as ChatItem.UserMessage).text)
        advanceUntilIdle()
        val params = b.transport.last("prompt.submit")
        assertEquals("check my mail", params["text"]?.jsonPrimitive?.content)
        assertEquals("voice-live", params["surface"]?.jsonPrimitive?.content)
        assertEquals("user: check my mail", params["voice_context"]?.jsonPrimitive?.content)
        assertFalse("queued" in params)
        assertFalse("voice_turn" in params)
        assertFalse("interrupted" in params)
        assertFalse((vm.state.value.chat.items.last() as ChatItem.UserMessage).queued)
    }

    @Test
    fun liveStartFailureFallsBackToChainedWithToast() = liveTest {
        val b = backend()
        b.status = gptLive()
        b.nextTransport.startResult = Result.failure(LiveStartException("502"))
        val vm = vm(b)
        advanceUntilIdle()
        vm.openVoice()
        advanceUntilIdle()

        assertEquals(listOf("Live voice couldn’t start — using phone voice"), b.toasts)
        assertEquals(1, b.speechInputs)
        assertEquals(listOf("start", "stop"), b.service)
        assertTrue(b.liveTransports.single().closed)
        assertNull(vm.state.value.voice?.live)
        assertEquals(VoicePhase.Listening, vm.state.value.voice?.phase)
    }

    @Test
    fun endRequestClosesVoice() = liveTest {
        val b = backend()
        val vm = liveVoice(b)
        val t = b.liveTransports.single()
        advanceTimeBy(84_100)
        runCurrent()

        b.liveVoiceEndRequests.tryEmit(Unit)
        runCurrent()
        assertNull("the screen leaves at once", vm.state.value.voice)
        assertEquals("session.close", t.sentTypes.last())
        assertFalse("the session closes gracefully", t.closed)
        vm.openVoice() // refused while the old session is closing
        runCurrent()
        assertNull(vm.state.value.voice)
        assertEquals(listOf("Ending live voice…"), b.toasts)

        t.emit(LiveEvent.Closed("close_requested", 84.0))
        runCurrent()
        assertTrue(t.closed)
        assertEquals(listOf("start", "stop"), b.service)
        assertEquals(listOf("Ending live voice…", "Live voice ended · 1:24"), b.toasts)
        assertNull(vm.state.value.voice)
    }

    @Test
    fun droppedSessionRestsOnTheScreenUntilClosed() = liveTest {
        val b = backend()
        val vm = liveVoice(b)
        advanceTimeBy(5_100)
        runCurrent()
        b.liveTransports.single().emit(LiveEvent.Closed("connection_lost", null))
        runCurrent()
        assertEquals(listOf("Live voice dropped · 0:05"), b.toasts)
        val v = vm.state.value.voice
        assertNotNull(v)
        assertEquals("connection_lost", v?.live?.ended)
        assertEquals(VoicePhase.Idle, v?.phase)
        vm.closeVoice()
        runCurrent()
        assertNull(vm.state.value.voice)
        assertEquals(listOf("start", "stop"), b.service)
    }

    private fun FakeLiveTransport.ofType(type: String) =
        sent.filter { it["type"]?.jsonPrimitive?.content == type }.map { it["content"]?.jsonPrimitive?.content }

    private fun TestScope.say(t: FakeLiveTransport, text: String, at: Long) {
        t.emit(LiveEvent.Transcript(LiveFragment(LiveSpeaker.User, text, at, at + 50)))
        runCurrent()
    }

    @Test
    fun delegationDuringARunningTurnWaitsForItsEnd() = liveTest {
        val b = backend()
        val vm = liveVoice(b)
        val t = b.liveTransports.single()
        vm.sendText("long task")
        runCurrent()
        say(t, "list my files", 100)
        t.emit(LiveEvent.Delegation("d1"))
        runCurrent()
        assertEquals("interrupted first, not sent yet", 1, b.transport.calls.count { it.first == "prompt.submit" })
        assertTrue(b.transport.calls.any { it.first == "session.interrupt" })

        // The old turn ends with its final text and no streaming bubble.
        b.events.emit(ev("message.complete", 1, """{"text":"old","status":"interrupted"}"""))
        runCurrent()
        val submit = b.transport.last("prompt.submit")
        assertEquals("voice-live", submit["surface"]?.jsonPrimitive?.content)
        assertEquals("list my files", submit["text"]?.jsonPrimitive?.content)

        b.events.emit(ev("message.start", 2))
        b.events.emit(ev("message.delta", 3, """{"text":"new"}"""))
        b.events.emit(ev("message.complete", 4, """{"text":"new"}"""))
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(listOf("new"), t.ofType("session.commentary.append"))
        assertFalse(t.ofType("session.thinking.append").any { it?.contains("without a spoken result") == true })
    }

    @Test
    fun twoDelegationsDuringARunningTurnSubmitOnlyTheSecond() = liveTest {
        val b = backend()
        val vm = liveVoice(b)
        val t = b.liveTransports.single()
        vm.sendText("long task")
        runCurrent()
        say(t, "list my files", 100)
        t.emit(LiveEvent.Delegation("d1"))
        runCurrent()
        say(t, "in the home folder", 200)
        t.emit(LiveEvent.Delegation("d2"))
        runCurrent()
        b.events.emit(ev("message.complete", 1, """{"text":"","status":"interrupted"}"""))
        advanceTimeBy(6_000)
        runCurrent()
        val submits = b.transport.calls.filter { it.first == "prompt.submit" }.map { it.second }
        assertEquals(2, submits.size)
        assertTrue(submits.last()["text"]?.jsonPrimitive?.content.orEmpty().contains("in the home folder"))
    }

    @Test
    fun endRequestLeavesAPhoneVoiceAlone() = liveTest {
        val b = backend()
        b.liveVoiceEnabled = false
        val vm = vm(b)
        advanceUntilIdle()
        vm.openVoice()
        advanceUntilIdle()
        b.liveVoiceEndRequests.tryEmit(Unit)
        advanceUntilIdle()
        assertNotNull(vm.state.value.voice)
    }
}
