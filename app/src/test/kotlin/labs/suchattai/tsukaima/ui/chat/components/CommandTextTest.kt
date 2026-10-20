package labs.suchattai.tsukaima.ui.chat.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandTextTest {
    private fun cp(c: Int) = String(Character.toChars(c))

    /** Expected marker text for a code point, e.g. `\u27E8U+202E\u27E9`. */
    private fun m(hex: String) = "\u27E8U+$hex\u27E9"

    @Test
    fun plainCommandUnchanged() {
        val r = CommandText.reveal("rm -rf build/ && echo ok")
        assertEquals("rm -rf build/ && echo ok", r.text)
        assertFalse(r.hadHiddenChars)
    }

    @Test
    fun bidiOverrideRevealed() {
        val r = CommandText.reveal("ls \u202Etxt.exe")
        assertTrue(r.text.contains(m("202E")))
        assertTrue(r.hadHiddenChars)
    }

    @Test
    fun zeroWidthRevealed() {
        val r = CommandText.reveal("r\u200Bm\uFEFF -rf \u2060/\u0007")
        assertTrue(r.text.contains(m("200B")))
        assertTrue(r.text.contains(m("FEFF")))
        assertTrue(r.text.contains(m("2060")))
        assertTrue(r.text.contains(m("0007")))
        assertTrue(r.hadHiddenChars)
    }

    @Test
    fun newlinesAndTabsKept() {
        val r = CommandText.reveal("a\n\tb")
        assertEquals("a\n\tb", r.text)
        assertFalse(r.hadHiddenChars)
    }

    @Test
    fun nonAsciiLettersUnchanged() {
        val s = "za\u017C\u00F3\u0142\u0107 \u015Bcie\u017Cka/\u65E5\u672C\u8A9E.txt " + cp(0x1F642)
        val r = CommandText.reveal(s)
        assertEquals(s, r.text)
        assertFalse(r.hadHiddenChars)
    }

    @Test
    fun tagCharactersRevealed() {
        val r = CommandText.reveal("ls " + cp(0xE0041))
        assertTrue(r.text.contains(m("E0041")))
        assertTrue(r.hadHiddenChars)
    }

    @Test
    fun nbspRevealed() {
        val r = CommandText.reveal("rm\u00A0-rf\u3000/")
        assertTrue(r.text.contains(m("00A0")))
        assertTrue(r.text.contains(m("3000")))
        assertTrue(r.hadHiddenChars)
    }

    @Test
    fun lineSeparatorRevealed() {
        val r = CommandText.reveal("a\u2028b\u2029c")
        assertTrue(r.text.contains(m("2028")))
        assertTrue(r.text.contains(m("2029")))
        assertTrue(r.hadHiddenChars)
    }

    @Test
    fun variationSelectorRevealed() {
        val r = CommandText.reveal("a\uFE0Fb" + cp(0xE0100) + "\u3164\u115F")
        assertTrue(r.text.contains(m("FE0F")))
        assertTrue(r.text.contains(m("E0100")))
        assertTrue(r.text.contains(m("3164")))
        assertTrue(r.text.contains(m("115F")))
    }

    @Test
    fun loneSurrogateRevealed() {
        val r = CommandText.reveal("a\uD800b")
        assertTrue(r.text.contains(m("D800")))
    }

    @Test
    fun defaultIgnorablesRevealed() {
        val r = CommandText.reveal("a\u034Fb\u17B4c\u180Bd" + cp(0xE0FFF) + "e\u2800f")
        for (h in listOf("034F", "17B4", "180B", "E0FFF", "2800")) assertTrue(h, r.text.contains(m(h)))
        assertTrue(r.hadHiddenChars)
    }

    @Test
    fun blankLineWithOnlyInvisiblesCollapsed() {
        val r = CommandText.reveal("a\n\u2800\n\u200B \n\u034F\nb")
        assertEquals("a\n\u27E83 blank lines\u27E9\nb", r.text)
        assertTrue(r.hadHiddenChars)
    }

    @Test
    fun blankLineRunsCollapsed() {
        val r = CommandText.reveal("a" + "\n".repeat(5) + "b")
        assertEquals("a\n\u27E84 blank lines\u27E9\nb", r.text)
        assertTrue(r.hadHiddenChars)
        // Two blank lines are ordinary.
        val ok = CommandText.reveal("a\n\n\nb")
        assertEquals("a\n\n\nb", ok.text)
        assertFalse(ok.hadHiddenChars)
    }

    @Test
    fun emojiPresentationNotFlagged() {
        val heart = "\u2764\uFE0F"
        val family = cp(0x1F468) + "\u200D" + cp(0x1F469) + "\u200D" + cp(0x1F467)
        val skin = cp(0x1F44D) + cp(0x1F3FD)
        val technologist = cp(0x1F9D1) + cp(0x1F3FD) + "\u200D" + cp(0x1F4BB)
        for (s in listOf(heart, family, skin, technologist)) {
            val r = CommandText.reveal("echo $s")
            assertEquals("echo $s", r.text)
            assertFalse(r.hadHiddenChars)
        }
    }

    @Test
    fun zwjBetweenLettersFlagged() {
        val r = CommandText.reveal("a\u200Db")
        assertTrue(r.text.contains(m("200D")))
        assertTrue(r.hadHiddenChars)
        assertTrue(CommandText.reveal("a\uFE0F").hadHiddenChars)
    }
}
