package app.tsukaima.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.tsukaima.share.ShareCard
import org.junit.Assert.assertEquals
import org.junit.Test

class ShareCardUiTest : ComposeUiTest() {
    @Test
    fun `the card shows the summary and both choices, each calling its action`() {
        val calls = mutableListOf<String>()
        setThemed {
            ShareCard(
                summary = "report.pdf",
                hasLastChat = true,
                onLastChat = { calls += "last" },
                onNewChat = { calls += "new" },
                onDismiss = { calls += "dismiss" },
            )
        }

        compose.onNodeWithText("Send to Hermes").assertIsDisplayed()
        compose.onNodeWithText("report.pdf").assertIsDisplayed()
        compose.onNodeWithText("Continue last chat").assertIsDisplayed().performClick()
        compose.onNodeWithText("New chat").assertIsDisplayed().performClick()
        assertEquals(listOf("last", "new"), calls)
    }

    @Test
    fun `without a last chat only New chat is offered`() {
        var newChat = 0
        setThemed(dark = true) {
            ShareCard(summary = "Link", hasLastChat = false, onLastChat = {}, onNewChat = { newChat++ }, onDismiss = {})
        }

        compose.onNodeWithText("Link").assertIsDisplayed()
        compose.onNodeWithText("Continue last chat").assertDoesNotExist()
        compose.onNodeWithText("New chat").assertIsDisplayed().performClick()
        assertEquals(1, newChat)
    }
}
