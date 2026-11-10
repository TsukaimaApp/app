package app.tsukaima.domain.chat

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import app.tsukaima.data.api.HermesJson
import app.tsukaima.ui.chat.components.COMMAND_PREVIEW_LINES
import app.tsukaima.ui.chat.components.commandPreview
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Slash commands against what the test Hermes (v0.23.1, 2026-11-10) answered a session of source `tsukaima`:
 * `recorded-slash-commands.json` holds its `commands.catalog` and the results of several commands (long texts cut).
 */
class SlashCommandsTest {
    private val recorded: JsonObject = HermesJson.parseToJsonElement(
        checkNotNull(javaClass.getResource("/chat/recorded-slash-commands.json")).readText(),
    ).jsonObject
    private val catalog = SlashCatalog.parse(recorded["catalog"]!!.jsonObject)
    private fun result(key: String) = SlashResult.of(recorded[key]!!.jsonObject)

    @Test
    fun theCatalogOffersTheServersCommandsAndSkills() {
        val names = catalog.offered.map { it.name }
        assertTrue(names.containsAll(listOf("/goal", "/undo", "/personality", "/help", "/steer")))
        assertTrue("a skill's own command", catalog.offered.first { it.name == "/airtable" }.skill)
        assertFalse(catalog.offered.first { it.name == "/goal" }.skill)
        assertTrue(catalog.offered.first { it.name == "/goal" }.description.startsWith("Set a standing goal"))
        assertEquals(names.size, names.toSet().size)
    }

    @Test
    fun terminalOnlyAndAppOwnedCommandsAreNotOffered() {
        val names = catalog.offered.map { it.name }.toSet()
        // desktop: terminal / hidden / composer-voice in the catalog's own metadata
        assertTrue(listOf("/clear", "/redraw", "/model", "/voice", "/quit").none { it in names })
        // the app has its own control for these
        assertTrue(Slash.NATIVE.none { it in names })
        assertTrue("still known, so typing one is not sent to the model", "/new" in catalog.known && "/reset" in catalog.known)
    }

    @Test
    fun suggestionsFollowTheWordBeingTyped() {
        assertEquals("/goal", catalog.matches("/go").first().name)
        assertTrue(catalog.matches("/go").any { it.name == "/subgoal" })
        assertEquals(SlashCatalog.MAX_MATCHES, catalog.matches("/").size)
        assertTrue(catalog.matches("/goal ship it").isEmpty())
        assertTrue(catalog.matches("goal").isEmpty())
        assertTrue(catalog.matches("/zzzz").isEmpty())
    }

    @Test
    fun onlyAKnownFirstWordIsACommand() {
        assertTrue(catalog.isCommand("/goal ship the release"))
        assertTrue(catalog.isCommand("  /GOAL"))
        assertTrue("an alias", catalog.isCommand("/reset"))
        assertFalse(catalog.isCommand("/etc/hosts is empty"))
        assertFalse(catalog.isCommand("/nosuchcommand"))
        assertFalse(catalog.isCommand("what does /goal do?"))
        assertEquals("/goal" to "ship it", Slash.split("/Goal  ship it"))
        assertEquals("/undo" to "", Slash.split("/undo"))
        assertNull(Slash.split("/ x"))
        assertNull(Slash.split("/"))
    }

    @Test
    fun plainOutputAndExecDirectivesAreShown() {
        assertEquals(SlashResult.Output("No active goal. Set one with /goal <text>."), result("goal"))
        assertTrue((result("status_output") as SlashResult.Output).text.startsWith("Hermes TUI Status"))
        assertTrue((result("unknown") as SlashResult.Output).text.startsWith("Unknown command /nosuchcommand"))
        assertEquals(SlashResult.Output(SlashResult.NO_OUTPUT), SlashResult.of(JsonObject(emptyMap())))
    }

    @Test
    fun aSkillAndAPlanBecomeATurnThatShowsTheCommand() {
        assertEquals(4018, HermesJson.parseToJsonElement(recorded["skill_refusal"].toString()).jsonObject["code"].toString().toInt())
        val skill = result("skill") as SlashResult.Send
        assertEquals("/airtable cats", skill.shown)
        assertTrue(skill.prompt.startsWith("[IMPORTANT: The user has invoked the \"airtable\" skill"))
        val plan = result("plan") as SlashResult.Send
        assertNull("no display: the bubble shows what was typed", plan.shown)
        assertTrue(plan.prompt.startsWith("[/plan — plan mode]"))
    }

    @Test
    fun terminalColourCodesAreRemoved() =
        assertEquals("ok done", Slash.clean("\n\u001B[1;32mok\u001B[0m done\n"))

    @Test
    fun longOutputIsCutToItsFirstLines() {
        val long = (1..40).joinToString("\n") { "line $it" }
        val (preview, more) = commandPreview(long)
        assertEquals(COMMAND_PREVIEW_LINES, preview.lines().size)
        assertEquals(40 - COMMAND_PREVIEW_LINES, more)
        assertEquals("short" to 0, commandPreview("short"))
    }
}
