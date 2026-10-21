package labs.suchattai.tsukaima.ui

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import labs.suchattai.tsukaima.domain.chat.ChatItem
import labs.suchattai.tsukaima.domain.chat.ToolStatus
import labs.suchattai.tsukaima.ui.chat.components.MediaHost
import labs.suchattai.tsukaima.ui.chat.components.ToolRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolImageUiTest : ComposeUiTest() {
    private fun vision(imageUrl: String, status: ToolStatus = ToolStatus.Done) = ChatItem.ToolCall(
        id = "v1", name = "vision_analyze", context = "what is on screen", args = null, status = status,
        output = "a terminal", exitCode = null, durationS = null, imageUrl = imageUrl,
    )

    @Test
    fun `a refused image shows only image unavailable and the row is unchanged`() {
        setThemed { ToolRow(vision("/etc/shadow.png"), media = MediaHost(toolImage = { null })) }

        compose.onNodeWithText("image unavailable").assertIsDisplayed()
        compose.onNodeWithText("vision_analyze · what is on screen").assertIsDisplayed()
        compose.onNodeWithContentDescription("Done").assertIsDisplayed()
        compose.onNodeWithText("vision_analyze · what is on screen").performClick()
        compose.onNodeWithText("a terminal").assertIsDisplayed()
    }

    @Test
    fun `a loaded image is a 96dp thumbnail and a tap shows it full width`() {
        val bitmap = ImageBitmap(400, 200)
        setThemed { ToolRow(vision("~/.hermes/screenshots/s.png", ToolStatus.Running), media = MediaHost(toolImage = { bitmap })) }

        val thumb = compose.onNodeWithContentDescription("Image the tool looked at")
        assertEquals(96.dp, thumb.getUnclippedBoundsInRoot().let { it.right - it.left })
        thumb.performClick()
        assertTrue(compose.onNodeWithContentDescription("Image the tool looked at").getUnclippedBoundsInRoot().let { it.right - it.left } > 96.dp)
    }

    @Test
    fun `a foreign image waits for a tap`() {
        val allowed = mutableListOf<String>()
        var asked = 0
        val host = MediaHost(imageAllowed = { false }, allowImage = { allowed += it }, toolImage = { asked++; null })
        setThemed { ToolRow(vision("https://tiles.example.org/a.png"), media = host) }

        compose.onNodeWithText("From tiles.example.org — tap to load").assertIsDisplayed()
        assertEquals(0, asked)
        compose.onNodeWithText("From tiles.example.org — tap to load").performClick()
        compose.onNodeWithText("image unavailable").assertIsDisplayed()
        assertEquals(listOf("https://tiles.example.org/a.png"), allowed)
        assertEquals(1, asked)
    }
}
