package app.tsukaima.domain.chat

import app.tsukaima.data.api.HermesJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolOutputTest {
    private fun text(json: String) = ToolOutput.text(HermesJson.parseToJsonElement(json))

    @Test
    fun terminalShowsItsOutput() = assertEquals("Linux", text("""{"output":"Linux","exit_code":0,"error":null}"""))

    @Test
    fun readFileShowsItsContent() =
        assertEquals("1|claude", text("""{"content":"1|claude","total_lines":1,"file_size":7,"truncated":false}"""))

    @Test
    fun aRawStringResultIsShownAsIs() = assertEquals("plain words", text("\"plain words\""))

    /** The stored history holds a result as the JSON text the tool returned. */
    @Test
    fun aJsonStringIsReadAsItsObject() = assertEquals("Linux", text("\"{\\\"output\\\": \\\"Linux\\\", \\\"exit_code\\\": 0}\""))

    @Test
    fun anErrorFollowsTheOutput() = assertEquals("partial\nboom", text("""{"output":"partial","error":"boom"}"""))

    @Test
    fun anErrorAloneIsTheOutput() = assertEquals("File not found", text("""{"error":"File not found","success":false}"""))

    @Test
    fun stderrFollowsStdout() = assertEquals("out\nwarn", text("""{"stdout":"out","stderr":"warn"}"""))

    /** No text field the app knows: the whole result, pretty-printed, instead of "No output". */
    @Test
    fun anUnknownShapeIsPrettyPrinted() {
        val shown = text("""{"data":{"web":[{"title":"A","url":"https://a.example"}]},"success":true}""")!!
        assertTrue(shown, shown.startsWith("{\n"))
        assertTrue(shown, "\"title\": \"A\"" in shown)
    }

    @Test
    fun aBlankTextFieldFallsThrough() = assertEquals("x", text("""{"output":"  ","content":"x"}"""))

    @Test
    fun nothingGivesNull() {
        assertNull(ToolOutput.text(null))
        assertNull(text("null"))
        assertNull(text("\"\""))
        assertNull(text("{}"))
    }

    @Test
    fun aHugeResultIsCut() {
        val shown = ToolOutput.text(kotlinx.serialization.json.JsonPrimitive("x".repeat(ToolOutput.MAX_CHARS + 500)))!!
        assertTrue(shown.endsWith("… 500 more characters"))
        assertEquals(ToolOutput.MAX_CHARS, shown.indexOf('\n'))
    }
}
