package app.tsukaima.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.tsukaima.domain.chat.ChatItem
import app.tsukaima.domain.chat.ClarifyQuestion
import app.tsukaima.ui.chat.components.ClarifyCard
import org.junit.Assert.assertEquals
import org.junit.Test

class ClarifyCardUiTest : ComposeUiTest() {
    private val longChoice = "Merge to main now (Recommended) — the branch is green, the release notes are written and " +
        "the keystore is backed up, so nothing is left that a pull request would catch"

    private fun card(vararg questions: ClarifyQuestion) = ChatItem.Clarify("srq-1", questions.toList(), answered = false)

    @Test
    fun `a long choice is shown in full and picked by tapping it`() {
        var sent: Map<String, String?>? = null
        setThemed {
            ClarifyCard(card(ClarifyQuestion("q0", "How do we land it?", listOf(longChoice, "Open a pull request"), multiSelect = false)), { sent = it }, {})
        }

        // The whole label is one text node (no chip ellipsis); the row carries selection semantics.
        compose.onNodeWithText(longChoice).assertIsDisplayed().assertIsNotSelected()
        compose.onNodeWithText("Send").assertIsNotEnabled()
        compose.onNodeWithText(longChoice).performClick()
        compose.onNodeWithText(longChoice).assertIsSelected()
        compose.onNodeWithText("Open a pull request").assertIsNotSelected()
        compose.onNodeWithText("Send").assertIsEnabled().performClick()
        assertEquals(mapOf("q0" to longChoice), sent)
    }

    @Test
    fun `multi-select rows toggle and Other opens the text field`() {
        setThemed {
            ClarifyCard(card(ClarifyQuestion("q0", "Which toppings?", listOf("ham", "olives"), multiSelect = true)), {}, {})
        }

        compose.onNodeWithText("ham").performClick()
        compose.onNodeWithText("olives").performClick()
        compose.onNodeWithText("ham").assertIsSelected()
        compose.onNodeWithText("olives").assertIsSelected()
        compose.onNodeWithText("ham").performClick()
        compose.onNodeWithText("ham").assertIsNotSelected()
        compose.onNodeWithText("olives").assertIsSelected()

        compose.onNodeWithText("Something else").assertDoesNotExist()
        compose.onNodeWithText("Other…").performClick()
        compose.onNodeWithText("Other…").assertIsSelected()
        compose.onNodeWithText("Something else").assertIsDisplayed()
    }
}
