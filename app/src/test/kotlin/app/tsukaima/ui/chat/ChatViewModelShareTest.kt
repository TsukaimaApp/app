package app.tsukaima.ui.chat

import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.viewModelScope
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
import app.tsukaima.data.HermesState
import app.tsukaima.data.RecentIds
import app.tsukaima.data.api.FileDownload
import app.tsukaima.data.api.HermesJson
import app.tsukaima.data.api.ModelOptionsDto
import app.tsukaima.data.api.TranscriptionDto
import app.tsukaima.data.api.WorkspacesDto
import app.tsukaima.data.files.FileSaver
import app.tsukaima.data.gateway.ConnectionState
import app.tsukaima.data.gateway.Frame
import app.tsukaima.data.gateway.RpcTransport
import app.tsukaima.data.prefs.Settings
import app.tsukaima.domain.voice.FakeSpeechInput
import app.tsukaima.domain.voice.FakeSpeechOutput
import app.tsukaima.domain.voice.VoiceLanguage
import app.tsukaima.share.ShareTarget
import app.tsukaima.share.SharedContent
import app.tsukaima.share.SharedFile
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * A share ([ChatBackend.pendingShare]) lands in the right chat's composer, once, when that chat's screen resumes
 * ([ChatViewModel.takePendingShare]), and its cached copy is released.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelShareTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val live = "d4fb0dea"

    private fun obj(s: String): JsonObject = HermesJson.parseToJsonElement(s).jsonObject

    private class Transport(private val results: Map<String, JsonObject>) : RpcTransport {
        val methods = mutableListOf<String>()
        override suspend fun request(method: String, params: JsonObject): JsonObject {
            methods += method
            return results[method] ?: JsonObject(emptyMap())
        }
        override fun respond(id: String, result: JsonObject) = true
        override fun respondError(id: String, code: Int, message: String) = true
    }

    private class Backend(override val transport: RpcTransport) : ChatBackend {
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
        override fun speechInput() = FakeSpeechInput()
        override fun speechOutput() = FakeSpeechOutput(instant = true)
        override fun voiceLanguages() = listOf(VoiceLanguage(null, "System default"))

        override val pendingShare = MutableStateFlow<SharedContent?>(null)
        override fun takeShare(content: SharedContent) = pendingShare.compareAndSet(content, null)
        val deleted = mutableListOf<SharedFile>()
        override fun deleteSharedFile(file: SharedFile) {
            deleted += file
        }
    }

    private val dispatcher = StandardTestDispatcher()
    private val created = mutableListOf<ChatViewModel>()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() {
        created.forEach { it.viewModelScope.cancel() }
        dispatcher.scheduler.advanceUntilIdle()
        created.forEach { check(it.viewModelScope.coroutineContext.job.isCompleted) { "VM scope still running" } }
        Dispatchers.resetMain()
    }

    private fun backend(stored: String = "s-new") = Backend(
        Transport(
            mapOf(
                "session.create" to
                    obj("""{"session_id":"$live","stored_session_id":"$stored","info":{},"messages":[]}"""),
                "session.resume" to
                    obj("""{"session_id":"$live","stored_session_id":"$stored","running":false,"messages":[]}"""),
                "file.attach" to
                    obj("""{"attached":true,"name":"notes.txt","path":"/w/notes.txt","ref_text":"@file:notes.txt"}"""),
            ),
        ),
    )

    private fun vm(b: Backend, storedId: String? = null, sharing: Boolean = true) =
        ChatViewModel(b, storedId, dispatcher = dispatcher, io = dispatcher, flair = { "flair" }, sharing = sharing)
            .also { created += it }

    private fun sharedFile(name: String = "notes.txt"): SharedFile {
        val dir = tmp.newFolder()
        val f = File(dir, name).apply { writeText("line one\n") }
        return SharedFile(f.path, name, "text/plain", f.length())
    }

    @Test
    fun shareForANewChatFillsInputAndAttaches() = runTest(dispatcher) {
        val b = backend()
        val file = sharedFile()
        b.pendingShare.value = SharedContent("hello", listOf(file), ShareTarget.NewChat, sessionId = null)

        val vm = vm(b)
        vm.takePendingShare()
        advanceUntilIdle()

        assertEquals("hello", vm.state.value.input)
        val chip = vm.state.value.attachments.single()
        assertEquals("notes.txt", chip.name)
        assertNull("consumed once", b.pendingShare.value)
        assertTrue(b.deleted.isEmpty())

        vm.takePendingShare() // a rotation resumes the screen again: nothing more
        advanceUntilIdle()
        assertEquals("hello", vm.state.value.input)
        assertEquals(1, vm.state.value.attachments.size)

        vm.send()
        advanceUntilIdle()
        assertEquals("the cached copy goes once the send took it", listOf(file), b.deleted)
    }

    @Test
    fun shareForAnotherChatIsLeftAlone() = runTest(dispatcher) {
        val b = backend(stored = "s-mine")
        val share = SharedContent("hello", listOf(sharedFile()), ShareTarget.LastChat, sessionId = "s-other")
        b.pendingShare.value = share

        val mine = vm(b, storedId = "s-mine")
        val fresh = vm(b) // a new chat takes only a NewChat share
        advanceUntilIdle()
        mine.takePendingShare()
        fresh.takePendingShare()
        advanceUntilIdle()

        assertEquals("", mine.state.value.input)
        assertTrue(mine.state.value.attachments.isEmpty())
        assertEquals("", fresh.state.value.input)
        assertTrue(fresh.state.value.attachments.isEmpty())
        assertSame("still pending", share, b.pendingShare.value)
    }

    @Test
    fun existingInputGetsABlankLineThenTheSharedText() = runTest(dispatcher) {
        val b = backend(stored = "s-mine")
        val vm = vm(b, storedId = "s-mine")
        advanceUntilIdle()
        vm.setInput("draft")

        // The share arrives while this chat is open (the app was in the background), then the chat resumes.
        b.pendingShare.value =
            SharedContent("https://example.com/a", emptyList(), ShareTarget.LastChat, sessionId = "s-mine")
        vm.takePendingShare()
        advanceUntilIdle()

        assertEquals("draft\n\nhttps://example.com/a", vm.state.value.input)
        assertNull(b.pendingShare.value)
    }

    @Test
    fun aChatUnderAnotherScreenLeavesItsShareToTheFreshChatTheShareOpens() = runTest(dispatcher) {
        val b = backend(stored = "s-x")
        val buried = vm(b, storedId = "s-x") // Status → Chat(X) → Settings: never resumed while the share waits
        advanceUntilIdle()
        val file = sharedFile()
        b.pendingShare.value = SharedContent("hello", listOf(file), ShareTarget.LastChat, sessionId = "s-x")
        advanceUntilIdle()

        val fresh = vm(b, storedId = "s-x") // navigateTop(Chat(X)): a new entry, resumed
        fresh.takePendingShare()
        advanceUntilIdle()

        assertEquals("", buried.state.value.input)
        assertTrue(buried.state.value.attachments.isEmpty())
        assertEquals("hello", fresh.state.value.input)
        assertEquals("notes.txt", fresh.state.value.attachments.single().name)
        assertNull(b.pendingShare.value)
        assertTrue(b.deleted.isEmpty())
    }

    @Test
    fun aNewChatOnScreenBeforeTheShareLeavesItToTheFreshOne() = runTest(dispatcher) {
        val b = backend()
        val earlier = vm(b) // the new chat on screen before the user shared from another app
        advanceUntilIdle()
        val share = SharedContent("hello", emptyList(), ShareTarget.NewChat, sessionId = null)
        b.pendingShare.value = share
        earlier.takePendingShare() // the app comes back: it resumes before the share's new chat replaces it
        advanceUntilIdle()
        assertEquals("", earlier.state.value.input)
        assertSame(share, b.pendingShare.value)

        val fresh = vm(b)
        fresh.takePendingShare()
        advanceUntilIdle()
        assertEquals("hello", fresh.state.value.input)
        assertNull(b.pendingShare.value)
    }

    @Test
    fun bindingAStoredIdRemembersTheLastChatAndDeletingItForgetsIt() = runTest(dispatcher) {
        val b = backend(stored = "s-new")
        val vm = vm(b)
        advanceUntilIdle()
        assertEquals("s-new", b.settings.value?.lastChatId)

        vm.delete()
        advanceUntilIdle()
        assertNull(b.settings.value?.lastChatId)
    }

    @Test
    fun theAssistChatNeitherBecomesTheLastChatNorTakesShares() = runTest(dispatcher) {
        val b = backend(stored = "s-assist")
        val share = SharedContent("hello", emptyList(), ShareTarget.NewChat, sessionId = null)
        b.pendingShare.value = share
        val assist = vm(b, sharing = false)
        assist.takePendingShare()
        advanceUntilIdle()

        assertNull(b.settings.value?.lastChatId)
        assertEquals("", assist.state.value.input)
        assertSame(share, b.pendingShare.value)
    }

    @Test
    fun removingASharedAttachmentDeletesItsCopy() = runTest(dispatcher) {
        val b = backend()
        val file = sharedFile()
        b.pendingShare.value = SharedContent("", listOf(file), ShareTarget.NewChat, sessionId = null)
        val vm = vm(b)
        vm.takePendingShare()
        advanceUntilIdle()

        vm.removeAttachment(vm.state.value.attachments.single().localId)
        advanceUntilIdle()
        assertEquals(listOf(file), b.deleted)
        assertFalse(b.toasts.any { it.startsWith("Couldn’t") })
    }
}
