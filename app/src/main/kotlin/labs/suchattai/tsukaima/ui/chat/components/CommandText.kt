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

    private const val Keycap = 0x20E3

    /** Keycap bases only form an emoji as `base VS16 U+20E3`. */
    private fun isKeycapBase(cp: Int): Boolean = cp == 0x23 || cp == 0x2A || cp in 0x30..0x39

    private fun isEmojiBase(cp: Int): Boolean =
        cp in 0x1F000..0x1FAFF || cp in 0x2600..0x27BF || cp in 0x2300..0x23FF ||
            cp in 0x2B00..0x2BFF || cp in 0x2190..0x21FF ||
            cp == 0x00A9 || cp == 0x00AE || cp == 0x203C || cp == 0x2049 || cp == 0x2122 ||
            cp == 0x2139 || cp == 0x3030 || cp == 0x303D || cp == 0x3297 || cp == 0x3299

    private fun isSkinTone(cp: Int): Boolean = cp in 0x1F3FB..0x1F3FF

    /**
     * Whether [cp] is a ZWJ or VS16 that is part of an emoji sequence. [prev] and [next] are the
     * neighbouring code points on the line (or -1); [prevIsEmojiVs16] tells if [prev] is a VS16
     * that was itself exempt.
     */
    private fun isEmojiJoiner(cp: Int, prev: Int, prevIsEmojiVs16: Boolean, next: Int): Boolean =
        when (cp) {
            Vs16 -> isEmojiBase(prev) || (isKeycapBase(prev) && next == Keycap)
            Zwj -> (isEmojiBase(prev) || isSkinTone(prev) || prevIsEmojiVs16) && isEmojiBase(next)
            else -> false
        }

    /** Whether [cp] is hidden or deceptive; the other arguments are as in [isEmojiJoiner]. */
    private fun flagged(cp: Int, prev: Int = -1, prevIsEmojiVs16: Boolean = false, next: Int = -1): Boolean {
        if (cp == '\n'.code || cp == '\t'.code) return false
        if (isEmojiJoiner(cp, prev, prevIsEmojiVs16, next)) return false
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

    /**
     * A line is blank only if every code point is whitespace that is not itself flagged (in
     * practice space and tab). A line holding any flagged code point is never collapsed, so each
     * hidden character in it gets its own marker.
     */
    private fun isBlank(line: String): Boolean {
        var i = 0
        while (i < line.length) {
            val cp = line.codePointAt(i)
            i += Character.charCount(cp)
            if (!Character.isWhitespace(cp) || flagged(cp)) return false
        }
        return true
    }

    /** Returns the line with flagged code points replaced, and whether any were. */
    private fun revealLine(line: String): Pair<String, Boolean> {
        var found = false
        val out = StringBuilder(line.length)
        var i = 0
        var prev = -1
        var prevIsEmojiVs16 = false
        while (i < line.length) {
            val cp = line.codePointAt(i)
            i += Character.charCount(cp)
            val next = if (i < line.length) line.codePointAt(i) else -1
            val hidden = flagged(cp, prev, prevIsEmojiVs16, next)
            if (hidden) {
                found = true
                out.append(marker(cp))
            } else {
                out.appendCodePoint(cp)
            }
            prevIsEmojiVs16 = cp == Vs16 && !hidden && isEmojiBase(prev)
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
