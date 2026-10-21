package labs.suchattai.tsukaima.ui

import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.performKeyPress
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import labs.suchattai.tsukaima.ui.chat.components.Composer
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
    fun `text typed while a turn runs offers Stop and a 44dp Send when done`() {
        var sends = 0
        var stops = 0
        setThemed { Composer("And then?", {}, { sends++ }, {}, enabled = true, running = true, onStop = { stops++ }, onVoice = {}) }

        compose.onNodeWithContentDescription("Stop")
            .assertWidthIsAtLeast(44.dp)
            .assertHeightIsAtLeast(44.dp)
        compose.onNodeWithContentDescription("Send when done")
            .assertIsEnabled()
            .assertWidthIsAtLeast(44.dp)
            .assertHeightIsAtLeast(44.dp)
            .performClick()
        compose.onNodeWithContentDescription("Send").assertDoesNotExist()
        assertEquals(1, sends)
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
    fun `text typed while the turn waits on an approval offers Send when done without Stop`() {
        var sends = 0
        setThemed { Composer("Also this", {}, { sends++ }, {}, enabled = true, running = false, turnLive = true, onVoice = {}) }

        compose.onNodeWithContentDescription("Send when done")
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
        compose.onNodeWithContentDescription("Send when done").assertDoesNotExist()
    }
}
