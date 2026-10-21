package labs.suchattai.tsukaima.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import labs.suchattai.tsukaima.domain.chat.ChatItem
import labs.suchattai.tsukaima.domain.chat.ToolStatus
import labs.suchattai.tsukaima.ui.chat.components.ToolRow
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
    fun `an orphan note is a warning line shown without expanding`() {
        setThemed { ToolRow(tool(ToolStatus.Interrupted, orphanNote = "May have partially run — check before retrying")) }

        compose.onNodeWithText("Interrupted").assertIsDisplayed()
        compose.onNodeWithText("May have partially run — check before retrying").assertIsDisplayed()
    }
}
