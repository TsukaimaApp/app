package labs.suchattai.tsukaima.domain.markdown

/**
 * Inline markdown for [Markdown]: code spans, `**bold**`, `*italic*` / `_italic_`, `~~strike~~`, `[text](url)`
 * links (`![alt](url)` images read as links) and bare `http(s)://` autolinks. An unbalanced marker stays literal,
 * so a half-streamed construct reads as text until it closes. Every lookahead is either memoised or bounded, so a
 * long line of unmatched markers stays linear. Never throws.
 */
internal object MarkdownInline {
    /** A link destination longer than this is not one (bounds the scan for its closing parenthesis). */
    private const val MAX_URL = 2048

    /** Characters an autolink does not end with (GFM extended autolinks); `)` only when unbalanced. */
    private const val AUTOLINK_TRAILING = ".,;:!?*_~'\""

    fun parse(s: String): List<MdSpan> {
        val spans = mutableListOf<MdSpan>()
        val lit = StringBuilder()
        fun emit(span: MdSpan) {
            if (lit.isNotEmpty()) { spans += MdSpan.Text(lit.toString()); lit.setLength(0) }
            spans += span
        }

        // Next `]` at or after a position; positions only grow, so one cached answer serves until passed.
        var closeBracket = -2
        fun nextCloseBracket(from: Int): Int {
            if (closeBracket == -1 || closeBracket >= from) return closeBracket
            closeBracket = s.indexOf(']', from)
            return closeBracket
        }

        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '`' -> {
                    // A code span opens and closes with backtick runs of the same length (`a`, ``a`b``, ```a```).
                    val run = backtickRun(s, i)
                    val j = closingRun(s, i + run, run)
                    if (j > i + run) { emit(MdSpan.Code(s.substring(i + run, j))); i = j + run }
                    else { lit.append(s, i, i + run); i += run }
                }
                c == '[' || (c == '!' && i + 1 < s.length && s[i + 1] == '[') -> {
                    val open = if (c == '!') i + 1 else i
                    val close = nextCloseBracket(open + 1)
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
                c == '~' && s.startsWith("~~", i) -> {
                    val j = s.indexOf("~~", i + 2)
                    if (j > i + 2) { emit(MdSpan.Strike(s.substring(i + 2, j))); i = j + 2 }
                    else { lit.append("~~"); i += 2 }
                }
                c == '*' && s.startsWith("**", i) -> {
                    val j = s.indexOf("**", i + 2)
                    if (j > i + 2) { emit(MdSpan.Bold(s.substring(i + 2, j))); i = j + 2 }
                    else { lit.append("**"); i += 2 }
                }
                c == '*' -> {
                    val j = s.indexOf('*', i + 1)
                    if (j > i + 1 && !s[i + 1].isWhitespace()) { emit(MdSpan.Italic(s.substring(i + 1, j))); i = j + 1 }
                    else { lit.append(c); i++ }
                }
                c == '_' && (i == 0 || !s[i - 1].isLetterOrDigit()) -> {
                    var j = s.indexOf('_', i + 1)
                    while (j != -1 && j + 1 < s.length && s[j + 1].isLetterOrDigit()) j = s.indexOf('_', j + 1)
                    if (j > i + 1 && !s[i + 1].isWhitespace()) { emit(MdSpan.Italic(s.substring(i + 1, j))); i = j + 1 }
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
            },
        )
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
