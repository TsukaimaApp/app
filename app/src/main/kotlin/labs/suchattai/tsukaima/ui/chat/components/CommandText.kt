package labs.suchattai.tsukaima.ui.chat.components

import java.util.Locale

/** Makes invisible, direction-changing or padding characters in an approval command visible. */
object CommandText {
    data class Revealed(val text: String, val hadHiddenChars: Boolean)

    private const val MaxPlainBlankLines = 2
    private const val Zwj = 0x200D
    private const val Vs16 = 0xFE0F

    private fun inRanges(cp: Int): Boolean =
        cp in 0xFE00..0xFE0F || cp in 0x180B..0x180F || cp in 0x17B4..0x17B5 ||
            cp in 0xE0000..0xE0FFF || cp == 0x034F || cp == 0x2800 ||
            cp == 0x115F || cp == 0x1160 || cp == 0x3164 || cp == 0xFFA0

    private fun isEmoji(cp: Int): Boolean {
        if (cp < 0x2000) return false
        val t = Character.getType(cp).toByte()
        return t == Character.OTHER_SYMBOL || t == Character.MODIFIER_SYMBOL
    }

    /** [prev] is the previous code point on the line, or -1; it exempts ZWJ and VS16 inside emoji. */
    private fun flagged(cp: Int, prev: Int): Boolean {
        if (cp == '\n'.code || cp == '\t'.code) return false
        if ((cp == Zwj || cp == Vs16) && prev >= 0 && isEmoji(prev)) return false
        if (inRanges(cp)) return true
        return when (Character.getType(cp).toByte()) {
            Character.FORMAT, Character.CONTROL, Character.LINE_SEPARATOR,
            Character.PARAGRAPH_SEPARATOR, Character.PRIVATE_USE, Character.UNASSIGNED,
            Character.SURROGATE,
            -> true
            Character.SPACE_SEPARATOR -> cp > 0x7F
            else -> false
        }
    }

    private fun marker(cp: Int) =
        "\u27E8U+" + Integer.toHexString(cp).uppercase(Locale.ROOT).padStart(4, '0') + "\u27E9"

    /** A line is blank if it holds only whitespace and/or flagged code points. */
    private fun isBlank(line: String): Boolean {
        var i = 0
        var prev = -1
        while (i < line.length) {
            val cp = line.codePointAt(i)
            i += Character.charCount(cp)
            if (!(Character.isWhitespace(cp) || flagged(cp, prev))) return false
            prev = cp
        }
        return true
    }

    /** Returns the line with flagged code points replaced, and whether any were. */
    private fun revealLine(line: String): Pair<String, Boolean> {
        var found = false
        val out = StringBuilder(line.length)
        var i = 0
        var prev = -1
        while (i < line.length) {
            val cp = line.codePointAt(i)
            i += Character.charCount(cp)
            if (flagged(cp, prev)) {
                found = true
                out.append(marker(cp))
            } else {
                out.appendCodePoint(cp)
            }
            prev = cp
        }
        return out.toString() to found
    }

    fun reveal(raw: String): Revealed {
        val lines = raw.split('\n')
        val blank = lines.map { isBlank(it) }
        val result = ArrayList<String>(lines.size)
        var suspicious = false
        var i = 0
        while (i < lines.size) {
            if (blank[i]) {
                var j = i
                while (j < lines.size && blank[j]) j++
                val run = j - i
                if (run > MaxPlainBlankLines) {
                    result.add("\u27E8$run blank lines\u27E9")
                    suspicious = true
                } else {
                    for (k in i until j) {
                        val (text, found) = revealLine(lines[k])
                        result.add(text)
                        suspicious = suspicious || found
                    }
                }
                i = j
            } else {
                val (text, found) = revealLine(lines[i])
                result.add(text)
                suspicious = suspicious || found
                i++
            }
        }
        return Revealed(result.joinToString("\n"), suspicious)
    }
}
