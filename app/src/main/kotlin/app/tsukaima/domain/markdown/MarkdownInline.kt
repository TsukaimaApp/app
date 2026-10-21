package app.tsukaima.domain.markdown

/**
 * Inline markdown for [Markdown]: code spans, `**bold**`, `*italic*` / `_italic_`, `~~strike~~`, `[text](url)`
 * links (`![alt](url)` images read as links), bare `http(s)://` autolinks, TeX math (`$…$`, `$$…$$`, `\(…\)`; see
 * [MdSpan.Math]; `\[…\]` is display math only, a block of its own) and CommonMark backslash escapes: a backslash
 * before ASCII punctuation emits that character literally and never opens or closes a construct (inside code spans
 * and math it stays as typed). An unbalanced marker stays literal, so a half-streamed construct reads as text until
 * it closes. Every emphasis, link and math closer search is memoised (positions only grow), so a long line of
 * unmatched markers parses in linear time. Never throws.
 */
internal object MarkdownInline {
    /** A link destination longer than this is not one (bounds the scan for its closing parenthesis). */
    private const val MAX_URL = 2048

    /** Characters an autolink does not end with (GFM extended autolinks); `)` only when unbalanced. */
    private const val AUTOLINK_TRAILING = ".,;:!?*_~'\""

    /** What a backslash escapes (CommonMark: ASCII punctuation); before anything else it is a literal backslash. */
    private const val ESCAPABLE = "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~"

    fun parse(s: String): List<MdSpan> = parse(s, emphasis = true)

    /**
     * With [emphasis] false (the inside of a bold, italic or strike span) only code spans and links are read, so the
     * second pass over that text is the last one and parsing stays linear.
     */
    private fun parse(s: String, emphasis: Boolean): List<MdSpan> {
        val spans = mutableListOf<MdSpan>()
        val lit = StringBuilder()
        fun emit(span: MdSpan) {
            if (lit.isNotEmpty()) { spans += MdSpan.Text(lit.toString()); lit.setLength(0) }
            spans += span
        }

        /** An emphasis span; a link or code span inside it is split out (a link keeps the emphasis via its flags). */
        fun emphasised(inner: String, wrap: (String) -> MdSpan, link: (MdSpan.Link) -> MdSpan.Link) {
            if ('[' !in inner && '`' !in inner && "http" !in inner && '\\' !in inner && '$' !in inner) { emit(wrap(inner)); return }
            for (span in parse(inner, emphasis = false)) emit(
                when (span) {
                    is MdSpan.Text -> wrap(span.s)
                    is MdSpan.Link -> link(span)
                    else -> span
                },
            )
        }

        // Closers: the next of each at or after a position, memoised (see [NextIndex]). Each test depends on the
        // closer's position alone, never on the opener, so a cached answer is right for any later opener.
        val closeBracket = NextIndex { unescapedIndexOf(s, "]", it) } // `[a\]b](u)`: an escaped `]` is link text
        val doubleTilde = NextIndex { unescapedIndexOf(s, "~~", it) }
        val doubleStar = NextIndex { unescapedIndexOf(s, "**", it) }
        val star = NextIndex { unescapedIndexOf(s, "*", it) }
        // `_` closes only at a word boundary (`snake_case_name` has no closer).
        val underscore = NextIndex { from ->
            var k = unescapedIndexOf(s, "_", from)
            while (k != -1 && k + 1 < s.length && s[k + 1].isLetterOrDigit()) k = unescapedIndexOf(s, "_", k + 1)
            k
        }
        val newline = NextIndex { s.indexOf('\n', it) }
        val dollarCloser = NextIndex { from ->
            var k = s.indexOf('$', from)
            while (k != -1 && !closesDollarMath(s, k)) k = s.indexOf('$', k + 1)
            k
        }
        val doubleDollar = NextIndex { s.indexOf("$$", it) }
        val parenCloser = NextIndex { s.indexOf("\\)", it) }

        /** Math from [from] to the closer at [close] (no newline between, not blank), emitted; false when it is none. */
        fun math(from: Int, close: Int): Boolean {
            if (close < from) return false
            val nl = newline.next(from)
            if (nl in from until close) return false
            val tex = s.substring(from, close).trim()
            if (tex.isEmpty()) return false
            emit(MdSpan.Math(tex))
            return true
        }

        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '\\' && i + 1 < s.length && s[i + 1] == '(' -> {
                    val j = parenCloser.next(i + 2)
                    if (math(i + 2, j)) i = j + 2 else { lit.append('('); i += 2 }
                }
                c == '\\' && i + 1 < s.length && s[i + 1] in ESCAPABLE -> { lit.append(s[i + 1]); i += 2 }
                c == '$' && s.startsWith("$$", i) -> {
                    val j = doubleDollar.next(i + 2)
                    if (math(i + 2, j)) i = j + 2 else { lit.append("$$"); i += 2 }
                }
                c == '$' -> {
                    // `$5 and $10`, `$HOME/$USER`, `a$b$c`: see [opensDollarMath] and [closesDollarMath].
                    val j = if (opensDollarMath(s, i)) dollarCloser.next(i + 1) else -1
                    if (j > i + 1 && math(i + 1, j)) i = j + 1 else { lit.append(c); i++ }
                }
                c == '`' -> {
                    // A code span opens and closes with backtick runs of the same length (`a`, ``a`b``, ```a```).
                    val run = backtickRun(s, i)
                    val j = closingRun(s, i + run, run)
                    if (j > i + run) { emit(MdSpan.Code(s.substring(i + run, j))); i = j + run }
                    else { lit.append(s, i, i + run); i += run }
                }
                c == '[' || (c == '!' && i + 1 < s.length && s[i + 1] == '[') -> {
                    val open = if (c == '!') i + 1 else i
                    val close = closeBracket.next(open + 1)
                    val end = if (close > open && close + 1 < s.length && s[close + 1] == '(') destinationEnd(s, close + 2) else -1
                    if (end < 0) { lit.append(c); i++; continue }
                    val text = plainText(s.substring(open + 1, close))
                    val url = s.substring(close + 2, end).trim().split(' ', '\t').first().removeSurrounding("<", ">")
                    when {
                        isLinkable(url) -> emit(MdSpan.Link(text.ifEmpty { url }, url))
                        else -> lit.append(text) // a relative or unknown link reads as its text
                    }
                    i = end + 1
                }
                c == 'h' && (s.startsWith("http://", i) || s.startsWith("https://", i)) && autolinkMayStart(s, i) -> {
                    val url = autolink(s, i)
                    if (url == null) { lit.append(c); i++; continue }
                    // `<https://…>`: the angle brackets are markup, not text.
                    val angled = lit.endsWith("<") && s.getOrNull(i + url.length) == '>'
                    if (angled) lit.setLength(lit.length - 1)
                    emit(MdSpan.Link(url, url))
                    i += url.length + if (angled) 1 else 0
                }
                !emphasis && (c == '~' || c == '*' || c == '_') -> { lit.append(c); i++ }
                c == '~' && s.startsWith("~~", i) -> {
                    val j = doubleTilde.next(i + 2)
                    if (j > i + 2) { emphasised(s.substring(i + 2, j), MdSpan::Strike) { it.copy(strike = true) }; i = j + 2 }
                    else { lit.append("~~"); i += 2 }
                }
                c == '*' && s.startsWith("**", i) -> {
                    val j = doubleStar.next(i + 2)
                    if (j > i + 2) { emphasised(s.substring(i + 2, j), MdSpan::Bold) { it.copy(bold = true) }; i = j + 2 }
                    else { lit.append("**"); i += 2 }
                }
                c == '*' -> {
                    val j = if (i + 1 < s.length && !s[i + 1].isWhitespace()) star.next(i + 1) else -1
                    if (j > i + 1) { emphasised(s.substring(i + 1, j), MdSpan::Italic) { it.copy(italic = true) }; i = j + 1 }
                    else { lit.append(c); i++ }
                }
                c == '_' && (i == 0 || !s[i - 1].isLetterOrDigit()) -> {
                    val j = underscore.next(i + 1)
                    if (j > i + 1 && !s[i + 1].isWhitespace()) { emphasised(s.substring(i + 1, j), MdSpan::Italic) { it.copy(italic = true) }; i = j + 1 }
                    else { lit.append(c); i++ }
                }
                else -> { lit.append(c); i++ }
            }
        }
        if (lit.isNotEmpty()) spans += MdSpan.Text(lit.toString())
        return spans
    }

    /**
     * Index of the `)` closing a link destination that starts at [from], or -1. Parentheses inside the URL nest
     * (`…/Foo_(bar))`); a title after whitespace is skipped; a newline or more than [MAX_URL] characters is no link.
     */
    private fun destinationEnd(s: String, from: Int): Int {
        var depth = 0
        var k = from
        val limit = minOf(s.length, from + MAX_URL)
        while (k < limit) {
            when (s[k]) {
                '\n' -> return -1
                '(' -> depth++
                ')' -> if (depth == 0) return k else depth--
                ' ', '\t' -> {
                    // `[a](url "title")`: the destination ended; the link ends at the next `)` on the line.
                    while (k < limit && s[k] != ')' && s[k] != '\n') k++
                    return if (k < limit && s[k] == ')') k else -1
                }
            }
            k++
        }
        return -1
    }

    private fun isLinkable(url: String): Boolean =
        (url.startsWith("https://") && url.length > 8) || (url.startsWith("http://") && url.length > 7) ||
            (url.startsWith("mailto:") && url.length > 7)

    /** Not glued to a preceding word (`xhttp://`), and not the destination of a link still being typed (`](http…`). */
    private fun autolinkMayStart(s: String, i: Int): Boolean =
        i == 0 || (!s[i - 1].isLetterOrDigit() && !(s[i - 1] == '(' && i >= 2 && s[i - 2] == ']'))

    /** The bare URL starting at [i], up to whitespace or `<`/`>`, without trailing punctuation; null when only a scheme. */
    private fun autolink(s: String, i: Int): String? {
        var end = i
        while (end < s.length && !s[end].isWhitespace() && s[end] != '<' && s[end] != '>') end++
        var opens = 0
        var closes = 0
        for (k in i until end) when (s[k]) { '(' -> opens++; ')' -> closes++ }
        while (end > i) {
            val last = s[end - 1]
            when {
                last in AUTOLINK_TRAILING -> end--
                last == ')' && closes > opens -> { end--; closes-- }
                else -> break
            }
        }
        val url = s.substring(i, end)
        return url.takeIf { isLinkable(it) }
    }

    /** Link text without its inline markers (`[**a** `b`](…)` reads as "a b"). */
    private fun plainText(s: String): String = buildString {
        for (span in parse(s)) append(
            when (span) {
                is MdSpan.Text -> span.s
                is MdSpan.Bold -> span.s
                is MdSpan.Italic -> span.s
                is MdSpan.Code -> span.s
                is MdSpan.Strike -> span.s
                is MdSpan.Link -> span.text
                is MdSpan.Math -> span.tex
            },
        )
    }

    /** The next match at or after a position; positions only grow, so one cached answer serves until passed. */
    private class NextIndex(private val find: (Int) -> Int) {
        private var cached = -2

        fun next(from: Int): Int {
            if (cached == -1 || cached >= from) return cached
            cached = find(from)
            return cached
        }
    }

    /**
     * Whether the `$` at [i] may open inline math: a non-space after it (`$ 5`), and no ASCII letter or digit before
     * it (`a$b$c`, `$HOME/$USER`). Shared with `SpeechText`, which must drop exactly what the chat shows as math.
     */
    internal fun opensDollarMath(s: String, i: Int): Boolean =
        i + 1 < s.length && !s[i + 1].isWhitespace() && (i == 0 || !s[i - 1].isAsciiLetterOrDigit())

    /**
     * Whether the `$` at [k] may close inline math: a non-space before it, not backslash-escaped (`\$` is a dollar
     * inside the TeX; `\\$` closes), and no digit (`$5 and $10`) or ASCII letter (`$HOME/$USER`) after it.
     */
    internal fun closesDollarMath(s: String, k: Int): Boolean =
        k > 0 && !s[k - 1].isWhitespace() && !isEscaped(s, k) &&
            (k + 1 >= s.length || (!s[k + 1].isDigit() && !s[k + 1].isAsciiLetterOrDigit()))

    private fun Char.isAsciiLetterOrDigit(): Boolean = this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'

    /** Index of [target] at or after [from] whose first character is not backslash-escaped, or -1. */
    private fun unescapedIndexOf(s: String, target: String, from: Int): Int {
        var j = s.indexOf(target, from)
        while (j != -1 && isEscaped(s, j)) j = s.indexOf(target, j + 1)
        return j
    }

    /** An odd run of backslashes right before [k] escapes it. */
    private fun isEscaped(s: String, k: Int): Boolean {
        var n = 0
        var j = k - 1
        while (j >= 0 && s[j] == '\\') { n++; j-- }
        return n % 2 == 1
    }

    private fun backtickRun(s: String, from: Int): Int {
        var j = from
        while (j < s.length && s[j] == '`') j++
        return j - from
    }

    /** Start of the next run of exactly [length] backticks at or after [from], or -1. */
    private fun closingRun(s: String, from: Int, length: Int): Int {
        var j = s.indexOf('`', from)
        while (j != -1) {
            val run = backtickRun(s, j)
            if (run == length) return j
            j = s.indexOf('`', j + run)
        }
        return -1
    }
}
