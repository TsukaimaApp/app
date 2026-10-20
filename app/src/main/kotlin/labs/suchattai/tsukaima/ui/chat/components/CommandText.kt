package labs.suchattai.tsukaima.ui.chat.components

/** Makes invisible or direction-changing characters in an approval command visible. */
object CommandText {
    data class Revealed(val text: String, val hadHiddenChars: Boolean)

    private fun hidden(c: Char): Boolean = when {
        c == '\n' || c == '\t' -> false
        c.code <= 0x1F || c.code in 0x7F..0x9F -> true
        c.code in 0x200B..0x200F -> true
        c.code in 0x202A..0x202E -> true
        c.code in 0x2060..0x2064 -> true
        c.code in 0x2066..0x2069 -> true
        c.code == 0xFEFF -> true
        else -> false
    }

    fun reveal(raw: String): Revealed {
        var found = false
        val out = StringBuilder(raw.length)
        for (c in raw) {
            if (hidden(c)) {
                found = true
                out.append("⟨U+").append("%04X".format(c.code)).append('⟩')
            } else {
                out.append(c)
            }
        }
        return Revealed(out.toString(), found)
    }
}
