package labs.suchattai.tsukaima.ui.chat.components

import java.util.Locale

/** Makes invisible, direction-changing or padding characters in an approval command visible. */
object CommandText {
    data class Revealed(val text: String, val hadHiddenChars: Boolean)

    private const val MaxPlainBlankLines = 2

    private fun flagged(cp: Int): Boolean {
        if (cp == '\n'.code || cp == '\t'.code) return false
        if (cp in 0xFE00..0xFE0F || cp in 0xE0100..0xE01EF) return true
        if (cp in 0xE0000..0xE007F) return true
        if (cp == 0x115F || cp == 0x1160 || cp == 0x3164 || cp == 0xFFA0) return true
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
        "⟨U+" + Integer.toHexString(cp).uppercase(Locale.ROOT).padStart(4, '0') + "⟩"

    fun reveal(raw: String): Revealed {
        var found = false
        val out = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val cp = raw.codePointAt(i)
            i += Character.charCount(cp)
            if (flagged(cp)) {
                found = true
                out.append(marker(cp))
            } else {
                out.appendCodePoint(cp)
            }
        }
        val (collapsed, blanks) = collapseBlankRuns(out.toString())
        return Revealed(collapsed, found || blanks)
    }

    /** Replaces runs of more than two blank lines with one visible marker line. */
    private fun collapseBlankRuns(text: String): Pair<String, Boolean> {
        val lines = text.split('\n')
        val result = ArrayList<String>(lines.size)
        var changed = false
        var i = 0
        while (i < lines.size) {
            if (lines[i].isBlank()) {
                var j = i
                while (j < lines.size && lines[j].isBlank()) j++
                val run = j - i
                // A run touching the start or end has no neighbour on one side but is still padding.
                if (run > MaxPlainBlankLines) {
                    result.add("⟨$run blank lines⟩")
                    changed = true
                } else {
                    repeat(run) { result.add(lines[i + it]) }
                }
                i = j
            } else {
                result.add(lines[i])
                i++
            }
        }
        return result.joinToString("\n") to changed
    }
}
