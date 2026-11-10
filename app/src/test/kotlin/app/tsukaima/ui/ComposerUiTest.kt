package app.tsukaima.ui

import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.performKeyPress
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import app.tsukaima.domain.chat.AgentStatus
import app.tsukaima.domain.chat.ChatItem
import app.tsukaima.domain.chat.ChatState
import app.tsukaima.ui.chat.ChatActions
import app.tsukaima.ui.chat.ChatContent
import app.tsukaima.ui.chat.ChatUiState
import app.tsukaima.ui.chat.components.Composer
import org.junit.Assert.assertEquals
import org.junit.Test

class ComposerUiTest : ComposeUiTest() {

    @Test
    fun `an empty composer with voice shows a 44dp mic labelled Voice`() {
        var voiceTaps = 0
        setThemed { Composer("", {}, {}, {}, enabled = true, onVoice = { voiceTaps++ }) }

        compose.onNodeWithContentDescription("Voice")
            .assertIsEnabled()
            .assertWidthIsAtLeast(44.dp)
            .assertHeightIsAtLeast(44.dp)
            .performClick()
        compose.onNodeWithContentDescription("Send").assertDoesNotExist()
        assertEquals(1, voiceTaps)
    }

    @Test
    fun `typed text turns the mic into a 44dp Send`() {
        var sends = 0
        setThemed { Composer("Restart the agent", {}, { sends++ }, {}, enabled = true, onVoice = {}) }

        compose.onNodeWithContentDescription("Send")
            .assertIsEnabled()
            .assertWidthIsAtLeast(44.dp)
            .assertHeightIsAtLeast(44.dp)
            .performClick()
        compose.onNodeWithContentDescription("Voice").assertDoesNotExist()
        assertEquals(1, sends)
    }

    @Test
    fun `text typed while a turn runs offers Stop and a 44dp More and no plain Send`() {
        var sends = 0
        var stops = 0
        var mores = 0
        setThemed { Composer("And then?", {}, { sends++ }, {}, enabled = true, running = true, onStop = { stops++ }, onMore = { mores++ }, onVoice = {}) }

        compose.onNodeWithContentDescription("Stop")
            .assertWidthIsAtLeast(44.dp)
            .assertHeightIsAtLeast(44.dp)
        compose.onNodeWithContentDescription("More ways to send")
            .assertIsEnabled()
            .assertWidthIsAtLeast(44.dp)
            .assertHeightIsAtLeast(44.dp)
            .performClick()
        compose.onNodeWithContentDescription("Send").assertDoesNotExist()
        assertEquals("More opens the menu; it sends nothing itself", 0, sends)
        assertEquals(1, mores)
        assertEquals(0, stops)
    }

    @Test
    fun `hardware Enter while a turn runs sends the message to be queued`() {
        var sends = 0
        setThemed { Composer("And then?", {}, { sends++ }, {}, enabled = true, running = true, onVoice = {}) }

        // A physical keyboard's Enter (not the on-screen keyboard's device id, which performKeyInput would use).
        val enter = android.view.KeyEvent(0, 0, android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_ENTER, 0, 0, 1, 0)
        compose.onNode(hasSetTextAction()).performClick().performKeyPress(KeyEvent(enter))
        assertEquals(1, sends)
    }

    @Test
    fun `text typed while the turn waits on an approval offers More without Stop`() {
        var sends = 0
        setThemed { Composer("Also this", {}, {}, {}, enabled = true, running = false, turnLive = true, onMore = { sends++ }, onVoice = {}) }

        compose.onNodeWithContentDescription("More ways to send")
            .assertWidthIsAtLeast(44.dp)
            .assertHeightIsAtLeast(44.dp)
            .performClick()
        compose.onNodeWithContentDescription("Stop").assertDoesNotExist()
        assertEquals(1, sends)
    }

    @Test
    fun `an empty composer while a turn runs shows Stop only`() {
        setThemed { Composer("", {}, {}, {}, enabled = true, running = true, onVoice = {}) }

        compose.onNodeWithContentDescription("Stop").assertExists()
        compose.onNodeWithContentDescription("More ways to send").assertDoesNotExist()
    }

    @Test
    fun composerShowsMoreBetweenTurns() {
        // The live turn ended but a queued message still waits to run: a new send queues behind it.
        val chat = ChatState(
            items = listOf(
                ChatItem.UserMessage("u1", "first", rowId = 1),
                ChatItem.AssistantMessage("a1", "one", streaming = false, reasoning = null),
                ChatItem.UserMessage("u2", "second", queued = true),
            ),
            status = AgentStatus.Idle,
            liveSessionId = "d4fb0dea",
        )
        setThemed {
            ChatContent(
                state = ChatUiState(chat = chat, input = "third", isNew = false),
                showMenu = false,
                onDrawer = {},
                onStatus = {},
                actions = ChatActions(),
            )
        }

        compose.onNodeWithContentDescription("More ways to send")
            .assertWidthIsAtLeast(44.dp)
            .assertHeightIsAtLeast(44.dp)
        compose.onNodeWithContentDescription("Send").assertDoesNotExist()
        compose.onNodeWithContentDescription("Stop").assertDoesNotExist()
    }
}
