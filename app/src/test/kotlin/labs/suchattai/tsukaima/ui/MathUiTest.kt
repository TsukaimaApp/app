package labs.suchattai.tsukaima.ui

import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import labs.suchattai.tsukaima.domain.markdown.Markdown
import labs.suchattai.tsukaima.domain.markdown.MdBlock
import labs.suchattai.tsukaima.ui.chat.components.MarkdownText
import org.junit.Assert.assertTrue
import org.junit.Test

class MathUiTest : ComposeUiTest() {

    @Test
    fun `a display formula renders as a non-empty image`() {
        setThemed { MarkdownText(listOf(MdBlock.Math("\\frac{1}{2}"))) }

        val size = compose.onNodeWithContentDescription("\\frac{1}{2}").fetchSemanticsNode().size
        assertTrue("formula image is empty: $size", size.width > 0 && size.height > 0)
    }

    @Test
    fun `inline math renders as an image inside the paragraph`() {
        setThemed { MarkdownText(Markdown.parse("half is \$\\frac{1}{2}\$ here")) }

        val size = compose.onNodeWithContentDescription("\\frac{1}{2}", useUnmergedTree = true).fetchSemanticsNode().size
        assertTrue("inline formula image is empty: $size", size.width > 0 && size.height > 0)
    }

    @Test
    fun `invalid TeX renders as its source text`() {
        setThemed {
            MarkdownText(listOf(MdBlock.Math("\\nosuchmacro{1}")) + Markdown.parse("x \$\\left(\$ y"))
        }

        // JLatexMath throws a ParseException for both (it is lenient about unclosed groups, so those draw).
        compose.onNodeWithText("\\nosuchmacro{1}").assertExists()
        compose.onNodeWithText("x \\left( y").assertExists()
    }
}
