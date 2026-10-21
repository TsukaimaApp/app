package app.tsukaima.domain.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TexSpeechTest {
    private fun read(tex: String) = TexSpeech.readable(tex)

    @Test
    fun fractionsReadAsOver() {
        assertEquals("a over b", read("\\frac{a}{b}"))
        assertEquals("1 over 2", read("\\frac12"))
    }

    @Test
    fun powersAndSubscripts() {
        assertEquals("x to the power n", read("x^{n}"))
        assertEquals("x to the power 2", read("x^2"))
        assertEquals("a sub i", read("a_{i}"))
        assertEquals("x sub i to the power 2", read("x_i^2"))
    }

    @Test
    fun squareRoots() {
        assertEquals("square root of x", read("\\sqrt{x}"))
    }

    @Test
    fun greekLettersByName() {
        assertEquals("alpha + beta = Delta", read("\\alpha + \\beta = \\Delta"))
        assertEquals("epsilon phi", read("\\varepsilon \\varphi"))
    }

    @Test
    fun timesAndCdot() {
        assertEquals("a times b", read("a \\cdot b"))
        assertEquals("a times b", read("a\\times b"))
    }

    @Test
    fun nestingReadsInsideOut() {
        assertEquals(
            "square root of x to the power 2 over y sub i",
            read("\\frac{\\sqrt{x^{2}}}{y_{i}}"),
        )
        assertEquals("e to the power x sub 1 over 2", read("e^{\\frac{x_1}{2}}"))
    }

    @Test
    fun unknownCommandsReadAsTheirName() {
        assertEquals("mathbf v", read("\\mathbf{v}"))
        assertEquals("sum sub i=1 to the power n i", read("\\sum_{i=1}^{n} i"))
    }

    @Test
    fun bracesDroppedAndWhitespaceCollapsed() {
        assertEquals("a b", read("  {a}\n\t {b}  "))
        assertEquals("", read("{}"))
        assertEquals("a b", read("a\\,b"))
    }

    @Test
    fun unbalancedInputDoesNotThrow() {
        assertEquals("a over", read("\\frac{a}{"))
        assertEquals("x to the power", read("x^"))
        assertEquals("a", read("}a}"))
    }

    @Test
    fun lineBreaksAreSpacesAndOtherControlSymbolsDropped() {
        assertEquals("a b", read("a\\\\b"))
        assertEquals("50 x", read("50\\% \\{x\\}"))
    }

    @Test
    fun braceLessChainsDoNotOverflowTheStack() {
        for (tex in listOf("\\frac".repeat(10_000), "^".repeat(10_000), "\\sqrt".repeat(10_000), "_{".repeat(10_000))) {
            assertTrue(read(tex).length <= 300)
        }
    }

    @Test
    fun lengthIsCappedAt300() {
        assertTrue(read("x+".repeat(1000)).length <= 300)
        assertTrue(read("\\frac{".repeat(5000)).length <= 300)
    }
}
