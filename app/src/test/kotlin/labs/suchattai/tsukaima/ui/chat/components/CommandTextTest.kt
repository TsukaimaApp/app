package labs.suchattai.tsukaima.ui.chat.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandTextTest {
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
}
