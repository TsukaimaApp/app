package app.tsukaima.ui.chat.components

import app.tsukaima.domain.chat.ChatItem
import app.tsukaima.domain.chat.ToolStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SelectTextTest {
    private fun tool(output: String?, pending: Boolean) = ChatItem.ToolCall(
        id = "t", name = "terminal", context = "ls", args = null, status = ToolStatus.Done, output = output,
        exitCode = null, durationS = null, outputPending = pending,
    )

    @Test
    fun aReplyIsSelectableWithItsReasoning() {
        val reply = ChatItem.AssistantMessage("a", "- one\n- two", streaming = false, reasoning = "think")
        assertEquals(SelectableText("- one\n- two", markdown = true, reasoning = "think"), selectableText(reply))
        // A reasoning-only row (the thinking before a tool call) is still selectable.
        assertEquals("think", selectableText(reply.copy(text = ""))?.reasoning)
    }

    @Test
    fun aUserMessageIsSelectableAsPlainText() =
        assertEquals(SelectableText("**not bold**", markdown = false, reasoning = null), selectableText(ChatItem.UserMessage("u", "**not bold**")))

    @Test
    fun otherRowsAndEmptyMessagesAreNot() {
        assertNull(selectableText(tool("x", false)))
        assertNull(selectableText(ChatItem.UserMessage("u", "")))
        assertNull(selectableText(ChatItem.AssistantMessage("a", "", streaming = false, reasoning = " ")))
    }

    @Test
    fun aToolRowSaysWhetherItsOutputIsMissingOrNotLoadedYet() {
        assertEquals("out", outputText(tool("out", false)))
        assertEquals("No output", outputText(tool(null, false)))
        assertEquals("No output", outputText(tool("  ", false)))
        assertEquals("Loading output…", outputText(tool(null, true)))
    }
}
