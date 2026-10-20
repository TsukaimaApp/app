package labs.suchattai.tsukaima.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import labs.suchattai.tsukaima.domain.markdown.Markdown
import labs.suchattai.tsukaima.domain.markdown.MdBlock
import labs.suchattai.tsukaima.ui.chat.components.MarkdownTable
import labs.suchattai.tsukaima.ui.chat.components.MarkdownText
import labs.suchattai.tsukaima.ui.theme.TsukaimaType
import org.junit.Assert.assertEquals
import org.junit.Test

class MarkdownTableUiTest : ComposeUiTest() {

    /** The weight the node's text was laid out with (the style's; no span overrides it in these cells). */
    private fun SemanticsNodeInteraction.fontWeight(): FontWeight? {
        val results = mutableListOf<TextLayoutResult>()
        fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        return results.single().layoutInput.style.fontWeight
    }

    @Test
    fun `a three-column table renders six cells with a semibold header`() {
        val table = Markdown.parse("| Service | State | Uptime |\n|---|---|---|\n| hermes | running | 3 d |").single() as MdBlock.Table
        setThemed { MarkdownTable(table) }

        for (header in listOf("Service", "State", "Uptime")) {
            assertEquals(header, FontWeight.SemiBold, compose.onNodeWithText(header).fontWeight())
        }
        for (cell in listOf("hermes", "running", "3 d")) {
            assertEquals(cell, TsukaimaType.ui.fontWeight, compose.onNodeWithText(cell).fontWeight())
        }
    }

    @Test
    fun `a link is its own clickable node and opens its URL through the UriHandler`() {
        val opened = mutableListOf<String>()
        val handler = object : UriHandler {
            override fun openUri(uri: String) {
                opened += uri
            }
        }
        setThemed {
            CompositionLocalProvider(LocalUriHandler provides handler) {
                MarkdownText(Markdown.parse("See [the docs](https://example.com/docs) for more."))
            }
        }

        val paragraph = compose.onNodeWithText("See the docs for more.", useUnmergedTree = true)
        val text = paragraph.fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text).orEmpty().single()
        val links = text.getLinkAnnotations(0, text.length).map { (it.item as LinkAnnotation.Url).url }
        assertEquals(listOf("https://example.com/docs"), links)

        compose.onNode(
            hasClickAction() and hasAnyAncestor(hasText("See the docs for more.")),
            useUnmergedTree = true,
        ).performClick()
        assertEquals(listOf("https://example.com/docs"), opened)
    }
}
