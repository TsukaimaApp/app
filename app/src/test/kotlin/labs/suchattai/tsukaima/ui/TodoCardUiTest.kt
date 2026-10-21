package labs.suchattai.tsukaima.ui

import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import labs.suchattai.tsukaima.domain.chat.ChatItem
import labs.suchattai.tsukaima.domain.chat.TodoEntry
import labs.suchattai.tsukaima.domain.chat.TodoStatus
import labs.suchattai.tsukaima.ui.chat.components.TodoCard
import org.junit.Assert.assertEquals
import org.junit.Test

class TodoCardUiTest : ComposeUiTest() {

    /** The merged row showing [content] with status [glyph]. */
    private fun row(glyph: String, content: String): SemanticsNodeInteraction =
        compose.onNode(hasText(glyph) and hasText(content))

    private val four = ChatItem.Todos(
        revision = 2,
        todos = listOf(
            TodoEntry("1", "Read the contract", TodoStatus.Completed),
            TodoEntry("2", "Wire the reducer", TodoStatus.InProgress),
            TodoEntry("2a", "Mirror tool.complete", TodoStatus.Pending, parent = "2"),
            TodoEntry("3", "Rewrite the app", TodoStatus.Cancelled),
        ),
    )

    @Test
    fun `four entries render with the icon of their status and the progress`() {
        setThemed { TodoCard(four) }

        compose.onNodeWithText("Tasks").assertIsDisplayed()
        compose.onNodeWithText("1 of 4 done").assertIsDisplayed() // cancelled counts in the total only
        row("●", "Read the contract").assertIsDisplayed()
        row("◐", "Wire the reducer").assertIsDisplayed()
        row("○", "Mirror tool.complete").assertIsDisplayed()
        row("×", "Rewrite the app").assertIsDisplayed()
        // Four rows: no done toggle.
        compose.onNode(hasText("Show done (1)")).assertDoesNotExist()
    }

    @Test
    fun `a child with a parent is indented under it`() {
        setThemed { TodoCard(four) }

        val parent = row("◐", "Wire the reducer").getUnclippedBoundsInRoot()
        val child = row("○", "Mirror tool.complete").getUnclippedBoundsInRoot()
        val top = row("×", "Rewrite the app").getUnclippedBoundsInRoot()
        assertEquals(16f, (child.left - parent.left).value, 0.5f)
        assertEquals(parent.left, top.left)
        // Display order: the subtask sits right under its parent.
        assertEquals(true, child.top > parent.top && child.top < top.top)
    }

    @Test
    fun `a child whose parent is missing renders at the top level`() {
        val orphan = four.copy(todos = four.todos + TodoEntry("9", "Orphaned step", TodoStatus.Pending, parent = "gone"))
        setThemed { TodoCard(orphan) }

        assertEquals(
            row("◐", "Wire the reducer").getUnclippedBoundsInRoot().left,
            row("○", "Orphaned step").getUnclippedBoundsInRoot().left,
        )
    }

    @Test
    fun `more than six rows hide the done ones behind a toggle`() {
        val long = ChatItem.Todos(
            revision = 5,
            todos = (1..7).map { TodoEntry("$it", "Step $it", if (it <= 3) TodoStatus.Completed else TodoStatus.Pending) },
        )
        setThemed { TodoCard(long) }

        compose.onNodeWithText("3 of 7 done").assertIsDisplayed()
        row("●", "Step 1").assertDoesNotExist()
        row("○", "Step 4").assertIsDisplayed()
        compose.onNodeWithText("Show done (3)").performClick()
        row("●", "Step 1").assertIsDisplayed()
        compose.onNodeWithText("Hide done").performClick()
        row("●", "Step 1").assertDoesNotExist()
    }
}
