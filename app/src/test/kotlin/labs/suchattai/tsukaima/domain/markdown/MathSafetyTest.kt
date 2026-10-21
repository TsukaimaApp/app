package labs.suchattai.tsukaima.domain.markdown

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MathSafetyTest {
    private val banned = listOf(
        "includegraphics", "input", "include", "def", "edef", "gdef", "xdef", "let", "newcommand", "renewcommand",
        "providecommand", "newenvironment", "renewenvironment", "csname", "catcode", "loop", "write", "openin",
        "openout", "read",
    )

    @Test
    fun everyBannedControlWordIsRefused() {
        for (name in banned) {
            assertFalse(name, MathSafety.allowed("x + \\$name{a}"))
            assertFalse("$name at the end", MathSafety.allowed("x\\$name"))
            assertFalse("$name before a non-letter", MathSafety.allowed("\\${name}1"))
        }
    }

    @Test
    fun ordinaryFormulasAndLookalikesAreAllowed() {
        for (tex in listOf("\\frac{1}{2}", "\\left( x \\right)", "\\inputx", "\\definecolor", "\\letter", "\\readme", "\\leq", "\\Delta")) {
            assertTrue(tex, MathSafety.allowed(tex))
        }
    }

    @Test
    fun overlongFormulasAreRefused() {
        assertTrue(MathSafety.allowed("x".repeat(2000)))
        assertFalse(MathSafety.allowed("x".repeat(2001)))
    }
}
