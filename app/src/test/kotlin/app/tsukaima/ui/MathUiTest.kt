package app.tsukaima.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.tsukaima.domain.markdown.Markdown
import app.tsukaima.domain.markdown.MdBlock
import app.tsukaima.ui.chat.components.MarkdownText
import app.tsukaima.ui.chat.components.formulaDescription
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MathUiTest : ComposeUiTest() {

    /** Waits for the off-main draw of a formula: until a node with [description] exists. */
    private fun awaitDescription(description: String) =
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasContentDescription(description), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }

    private fun awaitText(text: String) =
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasText(text), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }

    @Test
    fun `a display formula renders as a non-empty image`() {
        setThemed { MarkdownText(listOf(MdBlock.Math("\\frac{1}{2}"))) }

        awaitDescription("Formula: 1 over 2")
        val size = compose.onNodeWithContentDescription("Formula: 1 over 2").fetchSemanticsNode().size
        assertTrue("formula image is empty: $size", size.width > 0 && size.height > 0)
    }

    @Test
    fun `inline math renders as an image inside the paragraph`() {
        setThemed { MarkdownText(Markdown.parse("half is \$\\frac{1}{2}\$ here")) }

        awaitDescription("Formula: 1 over 2")
        val size = compose.onNodeWithContentDescription("Formula: 1 over 2", useUnmergedTree = true).fetchSemanticsNode().size
        assertTrue("inline formula image is empty: $size", size.width > 0 && size.height > 0)
    }

    @Test
    fun contentDescriptionIsReadable() {
        setThemed { MarkdownText(Markdown.parse("so \$x^{2} + \\alpha\$ and") + MdBlock.Math("\\sqrt{x}")) }

        awaitDescription("Formula: x to the power 2 + alpha")
        awaitDescription("Formula: square root of x")
    }

    @Test
    fun inlineFormulaScalesDown() {
        val tex = "a+b+c+d+e+f+g+h+i+j+k+l+m+n+o+p"
        val description = "Formula: $tex"
        var width: Dp? by mutableStateOf(null)
        setThemed {
            val box = width?.let { Modifier.width(it) } ?: Modifier.wrapContentWidth(unbounded = true)
            Box(box) { MarkdownText(Markdown.parse("\$$tex\$")) }
        }
        awaitDescription(description)
        val full = compose.onNodeWithContentDescription(description, useUnmergedTree = true).fetchSemanticsNode().size
        val narrow = with(compose.density) { (full.width * 0.85f).toDp() }

        width = narrow
        compose.waitUntil(5_000) {
            val node = compose.onAllNodes(hasContentDescription(description), useUnmergedTree = true).fetchSemanticsNodes()
            node.isNotEmpty() && node.single().size.width < full.width
        }

        val scaled = compose.onNodeWithContentDescription(description, useUnmergedTree = true).fetchSemanticsNode().size
        val limit = with(compose.density) { narrow.roundToPx() }
        assertTrue("scaled formula ${scaled.width}px is wider than the column ${limit}px", scaled.width <= limit)
        assertTrue("formula was not scaled: $scaled vs $full", scaled.height < full.height)
        assertEquals(
            0,
            compose.onAllNodes(hasContentDescription("Show formula"), useUnmergedTree = true).fetchSemanticsNodes().size,
        )
    }

    @Test
    fun `a formula too wide even at 70 percent is clipped behind a chip that opens it in a dialog`() {
        val tex = "a+b+c+d+e+f+g+h+i+j+k+l+m+n+o+p+q+r+s+t+u+v+w+x+y+z"
        setThemed { Box(Modifier.width(120.dp)) { MarkdownText(Markdown.parse("see \$$tex\$")) } }

        awaitDescription("Show formula")
        compose.onNodeWithContentDescription("Show formula", useUnmergedTree = true).performClick()

        awaitText("Formula")
        // The clipped inline image and the dialog's whole one.
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasContentDescription("Formula: $tex"), useUnmergedTree = true).fetchSemanticsNodes().size == 2
        }
        compose.onNodeWithContentDescription("Close").assertExists()
        compose.onNodeWithContentDescription("Close").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Formula").assertDoesNotExist()
    }

    @Test
    fun `a cached formula draws in its first frame, also at another width it fits`() {
        val description = "Formula: x to the power 2"
        var copies by mutableStateOf(1)
        var width by mutableStateOf(400.dp)
        setThemed {
            Column(Modifier.width(width)) { repeat(copies) { MarkdownText(Markdown.parse("so \$x^{2}\$ ok")) } }
        }
        awaitDescription(description)

        // Without frames advancing on their own, an off-main draw could not land: only a synchronous cache hit shows.
        compose.mainClock.autoAdvance = false
        copies = 2
        compose.waitForIdle() // the recomposer sees the change and asks for a frame (none runs)
        compose.mainClock.advanceTimeByFrame()
        assertEquals(
            "new copy",
            2,
            compose.onAllNodes(hasContentDescription(description), useUnmergedTree = true).fetchSemanticsNodes().size,
        )
        width = 300.dp
        compose.waitForIdle() // the recomposer sees the change and asks for a frame (none runs)
        compose.mainClock.advanceTimeByFrame()
        assertEquals(
            "narrower",
            2,
            compose.onAllNodes(hasContentDescription(description), useUnmergedTree = true).fetchSemanticsNodes().size,
        )
    }

    @Test
    fun `an empty reading labels the image just Formula`() {
        assertEquals("Formula", formulaDescription("{}"))
        assertEquals("Formula: 1 over 2", formulaDescription("\\frac{1}{2}"))
    }

    @Test
    fun `invalid TeX renders as its source text`() {
        setThemed {
            MarkdownText(listOf(MdBlock.Math("\\nosuchmacro{1}")) + Markdown.parse("x \$\\left(\$ y"))
        }

        // JLatexMath throws a ParseException for both (it is lenient about unclosed groups, so those draw).
        awaitText("\\nosuchmacro{1}")
        awaitText("x \\left( y")
    }

    @Test
    fun `a refused formula renders as its source text`() {
        setThemed { MarkdownText(listOf(MdBlock.Math("\\input{/etc/passwd}"))) }

        // Refused synchronously, before any off-main draw.
        compose.onNodeWithText("\\input{/etc/passwd}").assertExists()
    }
}
