package labs.suchattai.tsukaima.domain.voice

import labs.suchattai.tsukaima.domain.markdown.MarkdownTables

/** Turns Hermes' markdown replies into what text-to-speech should say. */
object SpeechText {
    private val fence = Regex("^\\s*(```|~~~)")
    private val media = Regex("^\\s*MEDIA:")

    /**
     * A whole-line `http(s)` image (the chat shows it as a card): nothing to say, not even the alt. The URL may hold
     * `)` (`…/Foo_(bar))`) and a title may follow it, as in the parser.
     */
    private val imageLine = Regex("^\\s*!\\[[^\\]]*]\\(\\s*<?https?://\\S+?>?(?:\\s+\"[^\"]*\")?\\s*\\)\\s*$")
    private val heading = Regex("^\\s{0,3}#{1,6}\\s+")
    private val quote = Regex("^\\s*(?:>\\s?)+")
    private val listPrefix = Regex("^\\s*(?:[-*+]|\\d+[.)])\\s+(?:\\[[ xX]]\\s+)?")
    private val link = Regex("!?\\[([^\\]]*)]\\([^)]*\\)")
    private val url = Regex("https?://\\S+")
    private val inlineCode = Regex("`([^`]*)`")
    private val strong = Regex("(\\*\\*|__)(.+?)\\1")
    private val emphasis = Regex("(?<![\\w*])([*_])(?=\\S)(.+?)(?<=\\S)\\1(?![\\w*])")
    private val multiSpace = Regex(" {2,}")
    private val strike = Regex("~~(.+?)~~")

    /** `\(…\)` inline math (read before escapes are hidden: `\(` is also an escaped parenthesis). */
    private val parenMath = Regex("\\\\\\((.+?)\\\\\\)")
    private val displayDollars = Regex("\\$\\$[^\n]+?\\$\\$")

    /** `$…$` as the parser reads it: a non-space after the opener, before the closer, and no digit after it. */
    private val inlineDollar = Regex("\\$(?=[^\\s$])[^$\n]*?(?<=[^\\s\\\\])\\$(?!\\d)")
    private val escape = Regex("\\\\([!-/:-@\\[-`{-~])")

    /**
     * Speakable text for [markdown]: fenced code blocks and display math (`$$`/`\[` lines; either one unterminated
     * runs to the end), and `MEDIA:` and whole-line image lines are dropped, inline math is dropped, inline markup is
     * reduced to its text (task boxes and backslash escapes too), links keep their label, bare URLs are dropped, and
     * blank lines disappear. A table row reads as its cells joined with ", "
     * (separator rows are dropped), and so are horizontal rules and setext underlines. The result is trimmed.
     */
    fun plain(markdown: String): String {
        val out = ArrayList<String>()
        var inFence = false
        var mathCloser: String? = null
        for (raw in markdown.lines()) {
            if (mathCloser == null && fence.containsMatchIn(raw)) {
                inFence = !inFence
                continue
            }
            if (inFence || media.containsMatchIn(raw)) continue
            val closer = mathCloser
            if (closer != null) {
                if (raw.trim().endsWith(closer)) mathCloser = null
                continue
            }
            when (val math = displayMath(raw.trim())) {
                DisplayMath.Line -> continue
                is DisplayMath.Opens -> { mathCloser = math.closer; continue }
                null -> Unit
            }
            // The quote marker goes first, so a quoted table row or rule reads like an unquoted one.
            val line = quote.replace(raw, "")
            if (isRuleOrUnderline(line) || isTableSeparator(line) || imageLine.matches(line)) continue
            var s = tableRow(line)
            s = heading.replace(s, "")
            s = listPrefix.replace(s, "")
            s = parenMath.replace(s, "")
            s = hideEscapes(s)
            s = displayDollars.replace(s, "")
            s = inlineDollar.replace(s, "")
            s = link.replace(s) { it.groupValues[1] }
            s = url.replace(s, "")
            s = inlineCode.replace(s) { it.groupValues[1] }
            s = strong.replace(s) { it.groupValues[2] }
            s = emphasis.replace(s) { it.groupValues[2] }
            s = strike.replace(s) { it.groupValues[1] }
            s = multiSpace.replace(unhideEscapes(s), " ").trim()
            if (s.isNotEmpty()) out += s
        }
        return out.joinToString("\n")
    }

    /** A trimmed line's display math: a whole one-line formula, or the opener of a block that [Opens.closer] ends. */
    private sealed interface DisplayMath {
        data object Line : DisplayMath
        data class Opens(val closer: String) : DisplayMath
    }

    /** Mirrors the parser: `$$`/`\[` opens; `$$a$$ and $$b$$` is inline math in a sentence, not a block. */
    private fun displayMath(trimmed: String): DisplayMath? {
        val (open, close) = when {
            trimmed.startsWith("$$") -> "$$" to "$$"
            trimmed.startsWith("\\[") -> "\\[" to "\\]"
            else -> return null
        }
        val head = trimmed.substring(open.length)
        if (!head.endsWith(close)) return DisplayMath.Opens(close)
        val tex = head.dropLast(close.length)
        return if (open !in tex && close !in tex) DisplayMath.Line else null
    }

    /**
     * A backslash-escaped punctuation character becomes a private-use stand-in, so no markup pattern (or `$`)
     * sees it; [unhideEscapes] turns the stand-ins back into the bare characters.
     */
    private fun hideEscapes(s: String): String = escape.replace(s) { (HIDDEN + it.groupValues[1][0].code).toChar().toString() }

    private fun unhideEscapes(s: String): String =
        if (s.none { it.code in HIDDEN until HIDDEN + 128 }) s
        else buildString { for (c in s) append(if (c.code in HIDDEN until HIDDEN + 128) (c.code - HIDDEN).toChar() else c) }

    private const val HIDDEN = 0xE000

    /** `---`, `***`, `___` (spaces allowed between) or a setext `===` underline: nothing to say. */
    private fun isRuleOrUnderline(line: String): Boolean {
        val s = line.trim()
        val mark = s.firstOrNull()?.takeIf { it in "-*_=" } ?: return false
        var count = 0
        for (c in s) {
            when (c) {
                mark -> count++
                ' ', '\t' -> Unit
                else -> return false
            }
        }
        return count >= 3
    }

    /** A table's `|:--|--:|` row. */
    private fun isTableSeparator(line: String): Boolean =
        '|' in line && '-' in line && line.all { it in "|:- \t" }

    /** A table row (a line that opens or closes with `|`) as its non-empty cells joined with ", "; any other line as is. */
    private fun tableRow(line: String): String {
        val s = line.trim()
        if (!s.startsWith('|') && !s.endsWith('|')) return line
        return MarkdownTables.splitCells(s).filter { it.isNotEmpty() }.joinToString(", ")
    }
}

/**
 * A sentence ready to speak. [end] is the index in the original text the transcript may reveal
 * up to: just past the sentence terminator, or just past the `\n` when the terminator is
 * followed by one. The boundary space is not consumed, so the next chunk starts at [end].
 * A fenced code block arrives as one chunk (fence to fence); `SpeechText.plain` of it is empty.
 */
data class SpokenChunk(val text: String, val end: Int)

/**
 * Splits a streaming reply into sentences, never inside a ``` or ~~~ fence. Feed it the reply's full text so far; it
 * remembers how much it already emitted, so repeated calls on a growing prefix are idempotent.
 */
class SentenceChunker {
    private var cursor = 0
    private var pending = ""

    /** Newly complete sentences in [text] (the full reply so far). */
    fun feed(text: String): List<String> = feedPositions(text).map { it.text }

    /** Like [feed], with each sentence's end index in [text] (just past its terminator or newline). */
    fun feedPositions(text: String): List<SpokenChunk> {
        val out = ArrayList<SpokenChunk>()
        if (text.length < cursor) return out
        var i = cursor
        var inFence = false
        var mathEnd = -1 // no boundary before this index: inside a display-math block
        while (i < text.length) {
            val c = text[i]
            val lineStart = i == 0 || text[i - 1] == '\n'
            if (lineStart && i >= mathEnd && togglesFence(text, i)) inFence = !inFence
            if (lineStart && !inFence && i >= mathEnd) mathEnd = displayMathEnd(text, i)
            val end = when {
                inFence || i < mathEnd -> -1
                c == '\n' && i + 1 < text.length -> i + 1
                c in TERMINATORS && i + 1 < text.length && text[i + 1].isWhitespace() ->
                    if (text[i + 1] == '\n') i + 2 else i + 1
                else -> -1
            }
            if (end < 0) { i++; continue }
            val sentence = text.substring(cursor, end).trim()
            if (sentence.isNotEmpty()) out += SpokenChunk(sentence, end)
            cursor = end
            i = end
        }
        pending = text.substring(cursor)
        return out
    }

    /**
     * Whether the line starting at [start] opens or closes a fence: after indentation, a run of 3+ backticks or
     * tildes not followed by another such run on the same line (`` ```x``` `` is inline, not a fence).
     */
    private fun togglesFence(text: String, start: Int): Boolean {
        var j = start
        while (j < text.length && text[j] != '\n' && text[j].isWhitespace()) j++
        val mark = text.getOrNull(j)?.takeIf { it == '`' || it == '~' } ?: return false
        var k = j
        while (k < text.length && text[k] == mark) k++
        if (k - j < 3) return false
        val lineEnd = text.indexOf('\n', k).let { if (it < 0) text.length else it }
        return text.substring(k, lineEnd).indexOf(mark.toString().repeat(3)) < 0
    }

    /**
     * When the line at [start] opens a multi-line display-math block (`$$` or `\[`, as in the parser), the index of
     * the newline ending its closing line (the text's length when that line is the last); [Int.MAX_VALUE] while the
     * closer has not arrived; -1 for any other line. The block then reaches `SpeechText.plain` whole, which drops it.
     */
    private fun displayMathEnd(text: String, start: Int): Int {
        var lineEnd = text.indexOf('\n', start).let { if (it < 0) text.length else it }
        val first = text.substring(start, lineEnd).trim()
        val close = when {
            first.startsWith("$$") -> "$$"
            first.startsWith("\\[") -> "\\]"
            else -> return -1
        }
        if (first.length > 2 && first.endsWith(close)) return -1 // a one-line formula (or inline math): one line anyway
        while (lineEnd < text.length) {
            val next = lineEnd + 1
            lineEnd = text.indexOf('\n', next).let { if (it < 0) text.length else it }
            if (text.substring(next, lineEnd).trim().endsWith(close)) return lineEnd
        }
        return Int.MAX_VALUE
    }

    /** Whatever is left, trimmed (null when blank); resets the chunker. */
    fun flush(): String? {
        val rest = pending.trim()
        pending = ""
        cursor = 0
        return rest.ifEmpty { null }
    }

    private companion object {
        const val TERMINATORS = ".!?…:"
    }
}
