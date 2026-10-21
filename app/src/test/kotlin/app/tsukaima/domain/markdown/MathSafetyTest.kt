package app.tsukaima.domain.markdown

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MathSafetyTest {
    /** The words the security ruling names; [MathSafety.BANNED] holds these and the library's global commands. */
    private val ruled = listOf(
        "includegraphics", "input", "include", "def", "edef", "gdef", "xdef", "let", "newcommand", "renewcommand",
        "providecommand", "newenvironment", "renewenvironment", "csname", "catcode", "loop", "write", "openin",
        "openout", "read", "DeclareMathOperator",
    )

    @Test
    fun everyBannedControlWordIsRefused() {
        assertTrue(MathSafety.BANNED.containsAll(ruled))
        for (name in MathSafety.BANNED) {
            assertFalse(name, MathSafety.allowed("x + \\$name{a}"))
            assertFalse("$name at the end", MathSafety.allowed("x\\$name"))
            assertFalse("$name before a non-letter", MathSafety.allowed("\\${name}1"))
        }
    }

    @Test
    fun ordinaryFormulasAndLookalikesAreAllowed() {
        val ok = listOf(
            "\\frac{1}{2}", "\\left( x \\right)", "\\inputx", "\\defined", "\\letter", "\\readme", "\\leq", "\\Delta",
        )
        for (tex in ok) {
            assertTrue(tex, MathSafety.allowed(tex))
        }
    }

    @Test
    fun aBannedWordFollowedByANonAsciiLetterIsStillRefused() {
        // JLaTeXMath would read `\inputé` as one (unknown) command; refusing it is the conservative side.
        assertFalse(MathSafety.allowed("\\inputé"))
        assertFalse(MathSafety.allowed("\\DeclareMathOperatorΩ"))
        assertFalse(MathSafety.allowed("\\definecolor{red}{rgb}{0,1,0}"))
    }

    @Test
    fun overlongFormulasAreRefused() {
        assertTrue(MathSafety.allowed("x".repeat(2000)))
        assertFalse(MathSafety.allowed("x".repeat(2001)))
    }
}
