package app.tsukaima.domain.chat

import app.tsukaima.data.api.ProviderDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelTreeTest {
    @Test
    fun plainModelsStayLeaves() {
        val tree = ModelTree.build(listOf(ProviderDto("openai-codex", "OpenAI Codex", listOf("gpt-6.1-sol-900k", "gpt-6-sol"))))
        val p = tree.single()
        assertEquals("openai-codex", p.slug)
        assertEquals("OpenAI Codex", p.name)
        assertEquals(listOf("gpt-6.1-sol-900k", "gpt-6-sol"), p.models.map { it.base })
        assertTrue(p.models.all { it.flavors.isEmpty() })
        assertEquals(listOf("gpt-6.1-sol-900k"), p.models.first().models)
    }

    @Test
    fun flavorsGroupUnderTheirBase() {
        val tree = ModelTree.build(
            listOf(ProviderDto("melious", null, listOf("deepseek-v4.1-flash:speed", "deepseek-v4.1-flash", "deepseek-v4.1-flash:quality", "kimi-k3"))),
        )
        val p = tree.single()
        assertEquals("melious", p.name) // no display name: the slug
        assertEquals(listOf("deepseek-v4.1-flash", "kimi-k3"), p.models.map { it.base })
        val flash = p.models.first()
        // The bare name first as "default", then the flavors in the server's order.
        assertEquals(listOf("default", "speed", "quality"), flash.flavors.map { it.label })
        assertEquals(listOf("deepseek-v4.1-flash", "deepseek-v4.1-flash:speed", "deepseek-v4.1-flash:quality"), flash.flavors.map { it.model })
        assertEquals(flash.flavors.map { it.model }, flash.models)
    }

    @Test
    fun flavorsOnlyWithoutABareName() {
        val p = ModelTree.build(listOf(ProviderDto("x", "X", listOf("m:fast", "m:slow")))).single()
        assertEquals(listOf("fast", "slow"), p.models.single().flavors.map { it.label })
    }

    @Test
    fun unauthenticatedProvidersAndDuplicatesAreDropped() {
        val tree = ModelTree.build(
            listOf(
                ProviderDto("a", "A", listOf("m", "m"), authenticated = true),
                ProviderDto("b", "B", listOf("n"), authenticated = false),
                ProviderDto("c", "C", listOf("o"), authenticated = null),
            ),
        )
        assertEquals(listOf("a", "c"), tree.map { it.slug })
        assertEquals(1, tree.first().models.size)
    }

    @Test
    fun baseAndFlavorSplitOnTheFirstColon() {
        assertEquals("deepseek-v4.1-flash", ModelTree.base("deepseek-v4.1-flash:speed"))
        assertEquals("speed", ModelTree.flavor("deepseek-v4.1-flash:speed"))
        assertEquals("", ModelTree.flavor("gpt-6"))
        assertEquals("a", ModelTree.base("a:b:c"))
        assertEquals("b:c", ModelTree.flavor("a:b:c"))
    }
}
