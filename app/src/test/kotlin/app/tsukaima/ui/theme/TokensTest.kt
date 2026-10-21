package app.tsukaima.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Test

class TokensTest {
    @Test
    fun lightAccentIs7C6FCF() {
        assertEquals(Color(0xFF7C6FCF), LightColors.accent)
    }

    @Test
    fun darkPageIs1B1917() {
        assertEquals(Color(0xFF1B1917), DarkColors.surfacePage)
    }

    @Test
    fun bodyIs15_5sp() {
        assertEquals(15.5.sp, TsukaimaType.body.fontSize)
        assertEquals((15.5 * 1.55).sp, TsukaimaType.body.lineHeight)
    }

    @Test
    fun labelIsUppercaseLetterSpaced() {
        assertEquals(0.08.em, TsukaimaType.label.letterSpacing)
    }
}
