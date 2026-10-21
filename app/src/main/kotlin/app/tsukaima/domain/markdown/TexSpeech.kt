package app.tsukaima.domain.markdown

/**
 * A best-effort spoken reading of a TeX formula, for accessibility labels: `\frac{a}{b}` is "a over b", `^{n}` "to
 * the power n", `_{i}` "sub i", `\sqrt{x}` "square root of x", Greek letters their name, `\cdot` and `\times`
 * "times", any other command its name without the backslash; braces are dropped and whitespace collapsed, and the
 * result is at most [MAX_LENGTH] characters. Pure; never throws, whatever the input (unbalanced groups read as far as
 * they go). `SpeechText` still drops math from spoken replies; this is what it would use if it spoke it.
 */
object TexSpeech {
    const val MAX_LENGTH = 300

    /** Input read past this much output is not needed (the result is capped), so reading stops there. */
    private const val READ_LIMIT = MAX_LENGTH * 2

    /** Groups nested deeper than this read their contents flat rather than recursing further. */
    private const val MAX_DEPTH = 32

    /** Variant spellings read as the base letter; every other Greek command already reads as its own name. */
    private val greekVariants = mapOf(
        "varepsilon" to "epsilon", "vartheta" to "theta", "varpi" to "pi", "varrho" to "rho", "varsigma" to "sigma",
        "varphi" to "phi", "varkappa" to "kappa",
    )

    /** Control symbols (`\,` and kin) that are only spacing. */
    private const val SPACING = ",;:! >"

    fun readable(tex: String): String {
        val reader = Reader(tex)
        reader.sequence(depth = 0, untilClose = false)
        return reader.out.toString().trim().replace(Regex("\\s+"), " ").take(MAX_LENGTH).trimEnd()
    }

    private class Reader(val s: String) {
        var i = 0
        val out = StringBuilder()

        private val done get() = i >= s.length || out.length > READ_LIMIT

        private fun word(w: String) {
            out.append(' ').append(w).append(' ')
        }

        /** Reads atoms until the end, or (when [untilClose]) the `}` closing the current group, which it consumes. */
        fun sequence(depth: Int, untilClose: Boolean) {
            while (!done) {
                if (s[i] == '}') {
                    i++
                    if (untilClose) return
                    continue // a stray closing brace
                }
                atom(depth)
            }
        }

        /** Reads one atom: a group, a command with its arguments, a script, or a single character. */
        fun atom(depth: Int) {
            if (done) return
            val c = s[i]
            when {
                c == '{' -> {
                    i++
                    if (depth < MAX_DEPTH) sequence(depth + 1, untilClose = true)
                }
                c == '}' -> i++
                c == '^' -> {
                    i++
                    word("to the power")
                    argument(depth)
                }
                c == '_' -> {
                    i++
                    word("sub")
                    argument(depth)
                }
                c == '\\' -> command(depth)
                else -> {
                    out.append(c)
                    i++
                }
            }
        }

        /** One argument: the next atom after any whitespace (a group reads whole, otherwise one character or command). */
        private fun argument(depth: Int) {
            while (i < s.length && s[i].isWhitespace()) i++
            if (i < s.length && s[i] == '}') return // `x^}`: nothing to read, leave the brace to its group
            atom(depth + 1)
        }

        private fun command(depth: Int) {
            i++ // the backslash
            if (i >= s.length) return
            if (!s[i].isLetter()) {
                val symbol = s[i++]
                if (symbol in SPACING || symbol.isWhitespace()) out.append(' ') else out.append(symbol)
                return
            }
            val start = i
            while (i < s.length && s[i].isLetter()) i++
            when (val name = s.substring(start, i)) {
                "frac", "dfrac", "tfrac" -> {
                    argument(depth)
                    word("over")
                    argument(depth)
                }
                "sqrt" -> {
                    word("square root of")
                    argument(depth)
                }
                "cdot", "times" -> word("times")
                else -> word(greekVariants[name] ?: name)
            }
        }
    }
}
