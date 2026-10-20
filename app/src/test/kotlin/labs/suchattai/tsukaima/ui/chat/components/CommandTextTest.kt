package labs.suchattai.tsukaima.ui.chat.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandTextTest {
    private fun cp(c: Int) = String(Character.toChars(c))

    @Test
    fun plainCommandUnchanged() {
        val r = CommandText.reveal("rm -rf build/ && echo ok")
        assertEquals("rm -rf build/ && echo ok", r.text)
        assertFalse(r.hadHiddenChars)
    }

    @Test
    fun bidiOverrideRevealed() {
        val r = CommandText.reveal("ls ‮txt.exe")
        assertTrue(r.text.contains("⟨U+202E⟩"))
        assertTrue(r.hadHiddenChars)
    }

    @Test
    fun zeroWidthRevealed() {
        val r = CommandText.reveal("r​m﻿ -rf ⁠/\u0007")
        assertTrue(r.text.contains("⟨U+200B⟩"))
        assertTrue(r.text.contains("⟨U+FEFF⟩"))
        assertTrue(r.text.contains("⟨U+2060⟩"))
        assertTrue(r.text.contains("⟨U+0007⟩"))
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
        val s = "zażółć ścieżka/日本語.txt " + cp(0x1F642)
        val r = CommandText.reveal(s)
        assertEquals(s, r.text)
        assertFalse(r.hadHiddenChars)
    }

    @Test
    fun tagCharactersRevealed() {
        val r = CommandText.reveal("ls " + cp(0xE0041))
        assertTrue(r.text.contains("⟨U+E0041⟩"))
        assertTrue(r.hadHiddenChars)
    }

    @Test
    fun nbspRevealed() {
        val r = CommandText.reveal("rm -rf　/")
        assertTrue(r.text.contains("⟨U+00A0⟩"))
        assertTrue(r.text.contains("⟨U+3000⟩"))
        assertTrue(r.hadHiddenChars)
    }

    @Test
    fun lineSeparatorRevealed() {
        val r = CommandText.reveal("a b c")
        assertTrue(r.text.contains("⟨U+2028⟩"))
        assertTrue(r.text.contains("⟨U+2029⟩"))
        assertTrue(r.hadHiddenChars)
    }

    @Test
    fun variationSelectorRevealed() {
        val r = CommandText.reveal("a️b" + cp(0xE0100) + "ㅤᅟ")
        assertTrue(r.text.contains("⟨U+FE0F⟩"))
        assertTrue(r.text.contains("⟨U+E0100⟩"))
        assertTrue(r.text.contains("⟨U+3164⟩"))
        assertTrue(r.text.contains("⟨U+115F⟩"))
    }

    @Test
    fun loneSurrogateRevealed() {
        val r = CommandText.reveal("a\uD800b")
        assertTrue(r.text.contains("⟨U+D800⟩"))
    }

    @Test
    fun blankLineRunsCollapsed() {
        val r = CommandText.reveal("a" + "\n".repeat(5) + "b")
        assertEquals("a\n⟨4 blank lines⟩\nb", r.text)
        assertTrue(r.hadHiddenChars)
        // Two blank lines are ordinary.
        val ok = CommandText.reveal("a\n\n\nb")
        assertEquals("a\n\n\nb", ok.text)
        assertFalse(ok.hadHiddenChars)
    }
}
