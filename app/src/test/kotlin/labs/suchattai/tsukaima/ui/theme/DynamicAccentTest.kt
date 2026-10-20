package labs.suchattai.tsukaima.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import org.junit.Test

class DynamicAccentTest {
    private val primary = Color(0xFF3F7F5F)

    @Test
    fun pressedIsDarkerInLightMode() =
        assertTrue(dynamicAccentPressed(primary, dark = false).luminance() < primary.luminance())

    @Test
    fun pressedIsLighterInDarkMode() =
        assertTrue(dynamicAccentPressed(primary, dark = true).luminance() > primary.luminance())
}
