package app.tsukaima.domain.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class ChatSearchExportTest {
    private val zone = ZoneId.of("Europe/Warsaw")
    private val sent = ZonedDateTime.of(2026, 11, 11, 9, 2, 0, 0, zone).toInstant().toEpochMilli()
    private val items = listOf(
        ChatItem.UserMessage("u1", "Why does the backup fail on Sundays?", at = sent, attachments = listOf("cron.log")),
        ChatItem.AssistantMessage("r1", "", streaming = false, reasoning = "Both units may want the same lock."),
        ChatItem.ToolCall("t1", "terminal", "journalctl -u `backup`", null, ToolStatus.Done, "Lock held, exiting", 0, 1.0),
        ChatItem.ToolCall("t2", "read_file", null, null, ToolStatus.Failed, null, null, null),
        ChatItem.AssistantMessage("a1", "The weekly snapshot holds the **lock** at 03:00.", streaming = false, reasoning = null),
        ChatItem.UserMessage("u2", "not sent", failed = true),
        ChatItem.AssistantMessage("a2", "Still wri", streaming = true, reasoning = null),
        ChatItem.Notice("n1", "lock lock lock", warning = false),
    )

    @Test
    fun searchFindsRowsByTextReasoningAndToolOutputInAnyCase() {
        assertEquals(listOf("r1", "t1", "a1"), ChatSearch.matches(items, "LOCK"))
        assertEquals(listOf("u1"), ChatSearch.matches(items, " sundays "))
        assertEquals(emptyList<String>(), ChatSearch.matches(items, "l")) // too short
        assertEquals(emptyList<String>(), ChatSearch.matches(items, "zebra"))
        assertTrue(ChatSearch.onlyInReasoning(items[1], "lock"))
        assertFalse(ChatSearch.onlyInReasoning(items[4], "lock"))
    }

    @Test
    fun rangesAreEveryOccurrenceWithoutOverlap() {
        assertEquals(listOf(0..3, 9..12), ChatSearch.ranges("Lock the lock", "lock"))
        assertEquals(listOf(0..1, 2..3), ChatSearch.ranges("aaaa", "aa"))
        assertEquals(emptyList<IntRange>(), ChatSearch.ranges("lock", "x"))
    }

    @Test
    fun theExportIsTheConversationWithoutItsLog() {
        val md = ChatExport.markdown("Backup cron", items, zone)
        assertEquals(
            """
            # Backup cron

            ## You · 2026-11-11 09:02

            Why does the backup fail on Sundays?
            - attached: cron.log

            ## Hermes

            - tool `terminal` · `journalctl -u 'backup'`
            - tool `read_file` (failed)
            The weekly snapshot holds the **lock** at 03:00.

            """.trimIndent(),
            md,
        )
        assertFalse("reasoning" in md.lowercase() || "Both units" in md)
        assertTrue(ChatExport.markdown(null, emptyList(), zone).startsWith("# Chat with Hermes"))
    }

    @Test
    fun exportFileNamesAreSafe() {
        assertEquals("deal-watch-triage-pepper-pl-mydealz-de.md", ChatExport.fileName("Deal watch triage (pepper.pl + mydealz.de)"))
        assertEquals("chat.md", ChatExport.fileName("  ../../ "))
        assertEquals("chat.md", ChatExport.fileName(null))
    }

    @Test
    fun aSearchUnfoldsEarlierTurnsSteps() {
        val turn = listOf(
            ChatItem.UserMessage("u1", "q"), ChatItem.ToolCall("t1", "terminal", "a", null, ToolStatus.Done, null, null, null),
            ChatItem.ToolCall("t2", "terminal", "b", null, ToolStatus.Done, null, null, null),
            ChatItem.ToolCall("t3", "terminal", "c", null, ToolStatus.Done, null, null, null), ChatItem.UserMessage("u2", "q2"),
        )
        assertEquals(listOf("u1", "steps-t1", "u2"), TranscriptRows.build(turn, emptySet(), zone).map { it.key })
        assertEquals(listOf("u1", "steps-t1", "t1", "t2", "t3", "u2"), TranscriptRows.build(turn, emptySet(), zone, unfold = true).map { it.key })
    }
}
