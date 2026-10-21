package app.tsukaima.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.tsukaima.domain.chat.ChatItem
import app.tsukaima.domain.chat.ToolStatus
import app.tsukaima.ui.chat.components.ToolRow
import app.tsukaima.ui.chat.components.argsText
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ToolRowUiTest : ComposeUiTest() {
    private fun tool(status: ToolStatus, output: String? = null, orphanNote: String? = null) = ChatItem.ToolCall(
        id = "t1", name = "terminal", context = "sleep 60", args = null, status = status,
        output = output, exitCode = 130, durationS = null, orphanNote = orphanNote,
    )

    @Test
    fun `an interrupted row shows Interrupted and the partial output`() {
        setThemed { ToolRow(tool(ToolStatus.Interrupted, output = "partial line 1\npartial line 2")) }

        compose.onNodeWithText("Interrupted").assertIsDisplayed()
        compose.onNodeWithContentDescription("Failed").assertDoesNotExist()
        compose.onNodeWithText("terminal · sleep 60").performClick()
        compose.onNodeWithText("partial line 1\npartial line 2").assertIsDisplayed()
    }

    @Test
    fun `a row without a summary shows its arguments when opened`() {
        val args = buildJsonObject {
            put("command", "tail -n 50 /var/log/hermes/agent.log")
            put("timeout", 30)
        }
        setThemed { ToolRow(tool(ToolStatus.Done, output = "ok").copy(context = null, args = args)) }

        compose.onNodeWithText("command: tail -n 50 /var/log/hermes/agent.log\ntimeout: 30").assertDoesNotExist()
        compose.onNodeWithText("terminal").performClick()
        compose.onNodeWithText("command: tail -n 50 /var/log/hermes/agent.log\ntimeout: 30").assertIsDisplayed()
        compose.onNodeWithText("ok").assertIsDisplayed()
    }

    @Test
    fun `argsText renders strings verbatim and the rest as JSON`() {
        assertNull(argsText(null))
        assertNull(argsText(buildJsonObject {}))
        val text = argsText(buildJsonObject { put("path", "a b.txt"); put("flags", buildJsonObject { put("force", true) }) })
        assertEquals("path: a b.txt\nflags: {\"force\":true}", text)
    }

    @Test
    fun `an orphan note is a warning line shown without expanding`() {
        setThemed { ToolRow(tool(ToolStatus.Interrupted, orphanNote = "May have partially run — check before retrying")) }

        compose.onNodeWithText("Interrupted").assertIsDisplayed()
        compose.onNodeWithText("May have partially run — check before retrying").assertIsDisplayed()
    }
}
