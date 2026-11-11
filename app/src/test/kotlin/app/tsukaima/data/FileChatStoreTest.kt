package app.tsukaima.data

import app.tsukaima.data.api.SessionDto
import app.tsukaima.diagnostics.BackgroundLog
import app.tsukaima.domain.chat.OutboxMessage
import app.tsukaima.domain.chat.SavedItem
import app.tsukaima.domain.chat.SavedTranscript
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class FileChatStoreTest {
    @get:Rule val tmp = TemporaryFolder()
    private val dispatcher = StandardTestDispatcher()
    private var account: String? = "http://hermes.test|ada|default"
    private fun store() = FileChatStore(tmp.root.resolve("chats"), dispatcher) { account }

    private val transcript = SavedTranscript(7, listOf(SavedItem("user", "h-1", "hello", rowId = 1)))

    @Test
    fun whatIsSavedForAChatComesBackAndBlankForgetsTheDraft() = runTest(dispatcher) {
        val s = store()
        s.saveTranscript("chat-a", transcript)
        s.saveDraft("chat-a", "half a thought")
        s.saveOutbox("chat-a", listOf(OutboxMessage("o1", "later", 5)))
        val again = store() // a new process reads the files
        assertEquals(transcript, again.transcript("chat-a"))
        assertEquals("half a thought", again.draft("chat-a"))
        assertEquals(listOf(OutboxMessage("o1", "later", 5)), again.outbox("chat-a"))
        assertNull(again.transcript("chat-b"))

        again.saveDraft("chat-a", "  ")
        assertNull(again.draft("chat-a"))
        again.forget("chat-a")
        assertNull(again.transcript("chat-a"))
    }

    @Test
    fun anotherAccountSeesNothingOfThisOne() = runTest(dispatcher) {
        val s = store()
        s.saveTranscript("chat-a", transcript)
        s.saveSessions(listOf(SessionDto(id = "chat-a", title = "Backups")))
        account = "http://hermes.test|grace|default"
        assertNull(s.transcript("chat-a"))
        assertTrue(s.sessions().isEmpty())
        account = "http://hermes.test|ada|default"
        assertEquals(transcript, s.transcript("chat-a"))
        assertEquals(listOf("Backups"), s.sessions().map { it.title })
    }

    @Test
    fun signedOutNothingIsReadOrWritten() = runTest(dispatcher) {
        val s = store()
        account = null
        s.saveTranscript("chat-a", transcript)
        s.saveDraft("chat-a", "text")
        s.saveSessions(listOf(SessionDto(id = "chat-a")))
        assertNull(s.transcript("chat-a"))
        account = "http://hermes.test|ada|default"
        assertNull(s.transcript("chat-a"))
        assertNull(s.draft("chat-a"))
        assertTrue(s.sessions().isEmpty())
    }

    @Test
    fun clearRemovesEveryAccountAndWinsOverAWriteAskedBeforeIt() = runTest(dispatcher) {
        val s = store()
        s.saveTranscript("chat-a", transcript)
        // A chat still open at sign-out asks to save; the wipe is asked after it but must not be undone by it.
        val late = backgroundScope.launchSave(s)
        s.clear()
        advanceUntilIdle()
        late.join()
        assertNull(s.transcript("chat-a"))
        assertNull(s.transcript("chat-late"))
        assertTrue(tmp.root.resolve("chats").listFiles().isNullOrEmpty())
    }

    private fun kotlinx.coroutines.CoroutineScope.launchSave(s: FileChatStore) =
        launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { s.saveTranscript("chat-late", transcript) }

    @Test
    fun theBackgroundLogKeepsItsNewestLinesInOrder() {
        val dir = tmp.newFolder("files")
        repeat(205) { BackgroundLog.note(dir, "event $it\nsecond line", 1_800_000_000_000L + it * 1000) }
        val lines = BackgroundLog.read(dir).lines()
        assertEquals(200, lines.size)
        assertTrue(lines.first().endsWith("event 5 second line"))
        assertTrue(lines.last().endsWith("event 204 second line"))
        assertTrue(lines.last().startsWith("2027-"))
    }
}
