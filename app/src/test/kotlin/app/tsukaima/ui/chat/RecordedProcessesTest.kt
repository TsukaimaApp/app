package app.tsukaima.ui.chat

import app.tsukaima.data.api.HermesJson
import app.tsukaima.data.gateway.ProcessList
import app.tsukaima.domain.chat.OutboxMessage
import app.tsukaima.domain.chat.ChatItem
import app.tsukaima.domain.chat.SavedChat
import app.tsukaima.domain.chat.ToolStatus
import app.tsukaima.notifications.ApprovalNotifier
import app.tsukaima.ui.chat.components.endedText
import app.tsukaima.ui.chat.components.savedCopyText
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** `process.list` as recorded on Hermes 0.23.1 (`recorded-processes.json`), and the 0.14 pure helpers. */
class RecordedProcessesTest {
    private val recorded = HermesJson.parseToJsonElement(
        checkNotNull(javaClass.getResourceAsStream("/chat/recorded-processes.json")).bufferedReader().readText(),
    ).jsonObject
    private fun list(name: String) =
        HermesJson.decodeFromJsonElement<ProcessList>(recorded.getValue("snaps").jsonObject.getValue(name)).processes

    @Test
    fun runningProcessesBecomeRowsWithTheirOutputAndStartTime() {
        val now = 1_800_000_000_000L
        val rows = ChatProcesses.rows(list("list2"), now)
        assertEquals(2, rows.size)
        assertTrue(rows.all { it.running })
        val loop = rows.first { it.command.startsWith("for i in") }
        assertEquals("tick 1\ntick 2\ntick 3\n", loop.tail)
        assertEquals(now - 11_000, loop.since)
        assertNull(loop.exitCode)
    }

    @Test
    fun aKilledProcessStaysListedAsStoppedBelowTheRunningOnes() {
        val rows = ChatProcesses.rows(list("list3"), 1_800_000_000_000L)
        assertEquals(listOf(true, false), rows.map { it.running })
        assertEquals("sleep 300", rows[1].command)
        assertEquals(-15, rows[1].exitCode)
        assertEquals("Stopped", endedText(rows[1].exitCode))
        assertEquals("Finished", endedText(0))
        assertEquals("Exit 2", endedText(2))
    }

    @Test
    fun aRowKeepsOutputThePhoneAlreadyHasAndItsStoppingMark() {
        val first = ChatProcesses.rows(list("list1"), 0)
        val loop = first.first { it.command.startsWith("for i in") }
        // An output event arrived after the list was read; the next list is older than what is shown.
        val shown = first.map { if (it.id == loop.id) it.copy(tail = it.tail + "tick 2\n", stopping = true) else it }
        val again = ChatProcesses.rows(list("list1").map { if (it.id == loop.id) it.copy(outputTail = "tick 2\n") else it }, 0, shown)
        assertEquals("tick 1\ntick 2\n", again.first { it.id == loop.id }.tail)
        assertTrue(again.first { it.id == loop.id }.stopping)
    }

    @Test
    fun theSavedCopyKeepsWhatHermesStoredAndComesBackWithItsIds() {
        val items = listOf(
            ChatItem.UserMessage("h-1", "Why does it fail?", rowId = 1, at = 5),
            ChatItem.AssistantMessage("h-2", "", streaming = false, reasoning = "Check the lock.", rowId = 2, thoughtMs = 4_000),
            ChatItem.ToolCall("call_1", "terminal", "journalctl", null, ToolStatus.Done, "x".repeat(SavedChat.MAX_OUTPUT + 10), 0, 3.2),
            ChatItem.ToolCall("call_2", "terminal", "sleep 9", null, ToolStatus.Running, null, null, null),
            ChatItem.AssistantMessage("a-live", "The snap", streaming = true, reasoning = null),
            ChatItem.UserMessage("u-l1", "not sent", failed = true),
            ChatItem.Notice("n1", "Context compacted", warning = false),
        )
        val saved = SavedChat.save(items, now = 99)
        assertEquals(99, saved.at)
        assertEquals(listOf("h-1", "h-2", "call_1"), saved.items.map { it.id })
        val back = SavedChat.items(saved)
        assertEquals(items[0], back[0])
        assertEquals(items[1], back[1])
        val tool = back[2] as ChatItem.ToolCall
        assertEquals(SavedChat.MAX_OUTPUT, tool.output?.length)
        assertEquals(3.2, tool.durationS)
        assertFalse(tool.outputPending)
    }

    @Test
    fun anOfflineMessageExpiresAfterTheSetMinutes() {
        assertEquals(1_000 + 10 * 60_000L, SavedChat.expiresAt(OutboxMessage("o", "t", 1_000), 10))
    }

    @Test
    fun theOfflineBarSaysHowOldTheCopyIs() {
        val zone = ZoneId.of("Europe/Warsaw")
        fun at(day: Int, h: Int, m: Int) = ZonedDateTime.of(2026, 11, day, h, m, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals("saved copy from 14:02", savedCopyText(at(11, 14, 2), at(11, 15, 0), zone, hour24 = true))
        assertEquals("saved copy from yesterday 21:14", savedCopyText(at(10, 21, 14), at(11, 15, 0), zone, hour24 = true))
    }

    @Test
    fun theReplyNotificationNamesTheBackgroundWorkThatFinished() {
        val plain = ApprovalNotifier.forTaskDone("Backups", "live")
        val named = ApprovalNotifier.forTaskDone("Backups", "live", "Background Process Finished: npm run build")
        assertEquals("Hermes finished the task", plain.text)
        assertEquals("Background Process Finished: npm run build", named.text)
        assertEquals(plain.id, named.id)
    }
}
