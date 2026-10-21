package app.tsukaima.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.mutableStateSetOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import app.tsukaima.domain.chat.ChatItem
import app.tsukaima.domain.chat.ToolStatus
import app.tsukaima.ui.chat.ImageResult
import app.tsukaima.ui.chat.components.ImageCard
import app.tsukaima.ui.chat.components.MediaHost
import app.tsukaima.ui.chat.components.ToolRow
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
        setThemed { ToolRow(vision("/etc/shadow.png"), media = MediaHost(toolImage = { ImageResult.Unavailable })) }

        compose.onNodeWithText("image unavailable").assertIsDisplayed()
        compose.onNodeWithText("vision_analyze · what is on screen").assertIsDisplayed()
        compose.onNodeWithContentDescription("Done").assertIsDisplayed()
        compose.onNodeWithText("vision_analyze · what is on screen").performClick()
        compose.onNodeWithText("a terminal").assertIsDisplayed()
    }

    @Test
    fun `a loaded image is a 96dp thumbnail and a tap shows it full width`() {
        val bitmap = ImageBitmap(400, 200)
        setThemed { ToolRow(vision("~/.hermes/screenshots/s.png", ToolStatus.Running), media = MediaHost(toolImage = { ImageResult.Loaded(bitmap) })) }

        val thumb = compose.onNodeWithContentDescription("Image the tool looked at")
        assertEquals(96.dp, thumb.getUnclippedBoundsInRoot().let { it.right - it.left })
        thumb.performClick()
        assertTrue(compose.onNodeWithContentDescription("Image the tool looked at").getUnclippedBoundsInRoot().let { it.right - it.left } > 96.dp)
    }

    @Test
    fun `a foreign image waits for a tap`() {
        val allowed = mutableStateSetOf<String>()
        var asked = 0
        val host = MediaHost(imageAllowed = { it in allowed }, allowImage = { allowed += it }, toolImage = { asked++; ImageResult.Unavailable })
        setThemed { ToolRow(vision("https://tiles.example.org/a.png"), media = host) }

        compose.onNodeWithText("From tiles.example.org — tap to load").assertIsDisplayed()
        assertEquals(0, asked)
        compose.onNodeWithText("From tiles.example.org — tap to load").performClick()
        compose.onNodeWithText("image unavailable").assertIsDisplayed()
        assertEquals(setOf("https://tiles.example.org/a.png"), allowed.toSet())
        assertEquals(1, asked)
    }

    @Test
    fun `a dropped connection offers a retry and the tap loads it again`() {
        var asked = 0
        val bitmap = ImageBitmap(40, 40)
        val host = MediaHost(toolImage = { if (++asked == 1) ImageResult.Retry else ImageResult.Loaded(bitmap) })
        setThemed { ToolRow(vision("~/.hermes/screenshots/s.png"), media = host) }

        compose.onNodeWithText("image didn’t load — tap to retry").assertIsDisplayed()
        compose.onNodeWithText("image unavailable").assertDoesNotExist()
        compose.onNodeWithText("image didn’t load — tap to retry").performClick()
        compose.onNodeWithContentDescription("Image the tool looked at").assertIsDisplayed()
        assertEquals(2, asked)
    }

    @Test
    fun `one tap loads a foreign url in the reply and the tool row`() {
        val url = "https://tiles.example.org/a.png"
        val allowed = mutableStateSetOf<String>()
        var previews = 0
        var tools = 0
        val bitmap = ImageBitmap(40, 40)
        val host = MediaHost(
            imageAllowed = { it in allowed },
            allowImage = { allowed += it },
            previewImage = { previews++; bitmap },
            toolImage = { tools++; ImageResult.Loaded(bitmap) },
        )
        setThemed {
            Column {
                ImageCard("Tiles", url, host)
                ToolRow(vision(url), media = host)
            }
        }

        compose.onAllNodesWithText("From tiles.example.org — tap to load").assertCountEquals(2)
        compose.onAllNodesWithText("From tiles.example.org — tap to load")[0].performClick()
        compose.onAllNodesWithText("From tiles.example.org — tap to load").assertCountEquals(0)
        compose.onNodeWithContentDescription("Image the tool looked at").assertIsDisplayed()
        assertEquals(1, previews)
        assertEquals(1, tools)
    }
}
