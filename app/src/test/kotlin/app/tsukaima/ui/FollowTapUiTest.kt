package app.tsukaima.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import app.tsukaima.domain.chat.ChatItem
import app.tsukaima.domain.chat.ChatState
import app.tsukaima.domain.chat.ToolStatus
import app.tsukaima.ui.chat.ChatActions
import app.tsukaima.ui.chat.ChatContent
import app.tsukaima.ui.chat.ChatUiState
import org.junit.Assert.assertEquals
import org.junit.Test

/** A transcript that follows a streaming reply still takes taps on the rows above it. */
class FollowTapUiTest : ComposeUiTest() {
    private fun items(reply: String) = listOf(
        ChatItem.UserMessage("u1", "Is the backup healthy?", rowId = 1),
        ChatItem.ToolCall("t1", "terminal", "journalctl -u backup", null, ToolStatus.Done, "the output text", 0, 1.0),
        ChatItem.AssistantMessage("a1", reply, streaming = true, reasoning = null),
    )

    private fun para(n: Int) = (1..n).joinToString("\n\n") { "Paragraph $it of the reply, long enough to take a line." }

    private var reply by mutableStateOf(para(1))

    private fun show() = setThemed {
        ChatContent(
            state = ChatUiState(chat = ChatState(items = items(reply), liveSessionId = "live"), isNew = false),
            showMenu = false, onDrawer = {}, onStatus = {}, actions = ChatActions(),
        )
    }.also {
        // The reply outgrows the window while it streams: the transcript is following its end.
        reply = para(4)
        compose.waitForIdle()
    }

    private fun row() = compose.onNodeWithText("journalctl -u backup", substring = true)

    @Test
    fun `the reply growing while a finger is down does not move the row or lose the tap`() {
        show()
        val before = row().fetchSemanticsNode().boundsInRoot
        row().performTouchInput { down(center) }
        for (k in 1..3) {
            reply += "\n\nMore of it, arriving while the finger is down."
            compose.waitForIdle()
            row().performTouchInput { moveBy(Offset(0f, 0.5f)) } // a finger is never perfectly still
        }
        assertEquals(before, row().fetchSemanticsNode().boundsInRoot)
        row().performTouchInput { up() }
        compose.waitForIdle()
        compose.onNodeWithText("the output text", substring = true).assertExists()
    }

    @Test
    fun `an opened row stays in place while the reply goes on, and Latest leads back`() {
        show()
        row().performClick()
        compose.waitForIdle()
        val opened = row().fetchSemanticsNode().boundsInRoot
        reply += "\n\nMore of it.\n\nAnd more."
        compose.waitForIdle()
        assertEquals(opened, row().fetchSemanticsNode().boundsInRoot)
        compose.onNodeWithContentDescription("Jump to latest", substring = true).assertExists()
    }

    @Test
    fun `after the finger lifts from a plain touch the transcript catches up with the reply`() {
        show()
        val before = row().fetchSemanticsNode().boundsInRoot
        // Down and up on the reply's own text: nothing to open, so following goes on.
        compose.onNodeWithText("Paragraph 1", substring = true).performTouchInput { down(center) }
        reply += "\n\nMore of it.\n\nAnd more."
        compose.waitForIdle()
        assertEquals(before, row().fetchSemanticsNode().boundsInRoot)
        compose.onNodeWithText("Paragraph 1", substring = true).performTouchInput { up() }
        compose.waitForIdle()
        compose.onNodeWithText("And more.", substring = true).assertIsDisplayed()
    }
}
