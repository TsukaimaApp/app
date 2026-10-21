package labs.suchattai.tsukaima.domain.markdown

sealed interface MdSpan {
    data class Text(val s: String) : MdSpan
    data class Bold(val s: String) : MdSpan
    data class Italic(val s: String) : MdSpan
    data class Code(val s: String) : MdSpan
    data class Strike(val s: String) : MdSpan

    /**
     * `[text](url)` or a bare `http(s)://` autolink (then [text] is the URL). Only web and `mailto:` URLs. The flags
     * carry the emphasis around it (`**[docs](…)**`): emphasis spans stay flat strings, so a link inside one is split
     * out and keeps the emphasis here.
     */
    data class Link(
        val text: String,
        val url: String,
        val bold: Boolean = false,
        val italic: Boolean = false,
        val strike: Boolean = false,
    ) : MdSpan

    /** Inline TeX from `$…$`, `$$…$$` or `\(…\)` inside a line, verbatim (trimmed) without its delimiters. */
    data class Math(val tex: String) : MdSpan
}

/** Column alignment from a table's separator row (`:--`, `:-:`, `--:`). */
enum class MdAlign { Start, Center, End }

/**
 * One list entry. [depth] is its nesting (0 = top, at most 3); [number] is the shown number of an ordered
 * entry and null for a bullet, so an ordered list can hold nested bullets and the other way round. [checked] is set
 * for a GFM task item (`- [ ] a`, `- [x] b`), whose box is not part of [spans]; null for an ordinary item.
 */
data class MdListItem(val spans: List<MdSpan>, val depth: Int, val number: Int? = null, val checked: Boolean? = null)

sealed interface MdBlock {
    data class Paragraph(val spans: List<MdSpan>) : MdBlock
    data class Code(val lang: String?, val code: String) : MdBlock

    /** A list whose top-level entries are bullets. */
    data class Bullets(val items: List<MdListItem>) : MdBlock

    /** A list whose top-level entries are numbered. */
    data class Numbered(val items: List<MdListItem>) : MdBlock

    /** `#`–`######` or a setext (`===` / `---` underlined) heading; [level] is 1–6. */
    data class Heading(val level: Int, val spans: List<MdSpan>) : MdBlock

    /** A GFM pipe table; every row has exactly as many cells as [header], and [align] one entry per column. */
    data class Table(
        val header: List<List<MdSpan>>,
        val align: List<MdAlign>,
        val rows: List<List<List<MdSpan>>>,
    ) : MdBlock

    /** Consecutive `>` lines, their content parsed as markdown of its own. */
    data class Quote(val blocks: List<MdBlock>) : MdBlock

    /** A horizontal rule (`---`, `***`, `___`). */
    data object Rule : MdBlock

    /** A file the agent delivered with a whole-line `MEDIA:<path>` directive (`/…` or `~/…`). */
    data class Media(val path: String) : MdBlock

    /** A line that is only `![alt](url)` with an `http(s)` [url]; [alt] may be empty. Inline images stay links. */
    data class Image(val alt: String, val url: String) : MdBlock

    /**
     * Display TeX: `$$…$$` or `\[…\]` starting a line and closing at the end of the same or a later line, with no
     * blank line between. [tex] is verbatim (no markdown inside), trimmed; an unclosed opener stays text.
     */
    data class Math(val tex: String) : MdBlock
}

/**
 * The markdown agents emit: fenced code, GFM pipe tables, ATX and setext headings, bullet and numbered lists
 * (nested, GFM task items), block quotes, rules, paragraphs, whole-line `http(s)` images, display math; inline
 * bold, italic, code, strikethrough, links, math and backslash escapes (see [MarkdownInline]); plus Hermes `MEDIA:`
 * file deliveries. Line-based and linear, since a streaming reply is re-parsed on every delta; a half-typed construct
 * reads as text until it is complete enough. Never throws.
 */
object Markdown {
    /**
     * With [streaming], the last line is never read as a `MEDIA:` directive: a half-streamed path would flash
     * a card for a file that does not exist.
     */
    fun parse(text: String, streaming: Boolean = false): List<MdBlock> =
        parseLines(text.replace("\r\n", "\n").replace('\r', '\n').split('\n'), streaming, quoteDepth = 0)

    /** Quotes nest this deep at most; a deeper `>` is literal text (and the recursion stays shallow). */
    private const val MAX_QUOTE_DEPTH = 5

    private fun parseLines(lines: List<String>, streaming: Boolean, quoteDepth: Int): List<MdBlock> {
        val blocks = mutableListOf<MdBlock>()
        val para = mutableListOf<String>()
        var list: ListBuilder? = null
        val unclosedMath = HashMap<String, IntRange>(2)

        fun flushPara() {
            if (para.isNotEmpty()) {
                blocks += MdBlock.Paragraph(MarkdownInline.parse(para.joinToString("\n")))
                para.clear()
            }
        }

        fun flushList() {
            list?.let { blocks += it.build() }
            list = null
        }

        fun flush() { flushPara(); flushList() }

        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()
            val fence = fenceLength(trimmed)
            if (fence > 0) {
                flush()
                val lang = trimmed.substring(fence).trim()
                    .split(WHITESPACE).firstOrNull()?.takeIf { it.isNotEmpty() }
                val indent = line.indexOfFirst { it != ' ' }
                val body = mutableListOf<String>()
                i++
                while (i < lines.size && !closesFence(lines[i].trim(), fence)) {
                    body += dedent(lines[i], indent); i++
                }
                blocks += MdBlock.Code(lang, body.joinToString("\n"))
                i++ // past the closing fence (or the end)
                continue
            }
            if (trimmed.isEmpty()) { flush(); i++; continue }

            val math = displayMath(lines, i, unclosedMath)
            if (math != null) { flush(); blocks += math.first; i = math.second; continue }

            val media = if (streaming && i == lines.lastIndex) null else mediaPath(trimmed)
            if (media != null) { flush(); blocks += MdBlock.Media(media); i++; continue }

            // An indented image line under a list item continues the item, as any other line would.
            val image = if (list != null && indentOf(line) >= 2) null else imageLine(trimmed)
            if (image != null) { flush(); blocks += image; i++; continue }

            val table = MarkdownTables.parse(lines, i)
            if (table != null) {
                flush()
                blocks += table.first
                i = table.second
                continue
            }

            val heading = atxHeading(line)
            if (heading != null) { flush(); blocks += heading; i++; continue }

            val setext = if (para.isNotEmpty()) setextLevel(trimmed) else 0
            if (setext > 0) {
                val title = para.removeAt(para.lastIndex)
                flushPara()
                blocks += MdBlock.Heading(setext, MarkdownInline.parse(title.trim()))
                i++
                continue
            }

            if (isRule(line)) { flush(); blocks += MdBlock.Rule; i++; continue }

            if (quoteDepth < MAX_QUOTE_DEPTH && indentOf(line) <= 3 && trimmed.startsWith('>')) {
                flush()
                val inner = mutableListOf<String>()
                while (i < lines.size) {
                    val q = lines[i].trimStart()
                    if (!q.startsWith('>') || indentOf(lines[i]) > 3) break
                    inner += q.substring(1).let { if (it.startsWith(' ')) it.substring(1) else it }
                    i++
                }
                blocks += MdBlock.Quote(parseLines(inner, streaming && i == lines.size, quoteDepth + 1))
                continue
            }

            val marker = listMarker(line)
            val open = list
            when {
                marker != null -> {
                    flushPara()
                    val target = if (open != null && open.accepts(marker)) open else {
                        flushList()
                        ListBuilder(ordered = marker.number != null).also { list = it }
                    }
                    target.add(marker, line.substring(marker.textStart).trim())
                }
                open != null && indentOf(line) >= 2 -> open.continueWith(trimmed)
                else -> { flushList(); para += line.trimStart() }
            }
            i++
        }
        flush()
        return blocks
    }

    /**
     * Length of the backtick run opening a code fence on [trimmed] (3 or more), else 0. As in CommonMark, an info
     * string holding a backtick is no fence: ```` ```ls``` ```` on one line is inline code in a paragraph.
     */
    private fun fenceLength(trimmed: String): Int {
        val run = trimmed.takeWhile { it == '`' }.length
        return if (run >= 3 && '`' !in trimmed.substring(run)) run else 0
    }

    /** A closing fence is a line of only backticks, at least as many as opened it (```` ```` ```` closes ```` ```` ````). */
    private fun closesFence(trimmed: String, open: Int): Boolean =
        trimmed.length >= open && trimmed.all { it == '`' }

    /** [line] without up to [n] leading spaces: the body of an indented fence (inside a list item) loses the fence's indent. */
    private fun dedent(line: String, n: Int): String {
        var k = 0
        while (k < n && k < line.length && line[k] == ' ') k++
        return line.substring(k)
    }

    /** Width of [line]'s leading whitespace, a tab counting as 4. */
    private fun indentOf(line: String): Int {
        var w = 0
        for (c in line) {
            when (c) {
                ' ' -> w++
                '\t' -> w += 4
                else -> return w
            }
        }
        return w
    }

    /**
     * The display math starting at `lines[start]` and the index of the line after it, or null. The opener (`$$` or
     * `\[`) begins the trimmed line; the closer (`$$` or `\]`) ends the trimmed line, on this line or a later one. A
     * one-line `$$a$$ and $$b$$` is no block (inline math in a paragraph). As in pandoc, a blank line ends the search:
     * a typo'd `$$a = b` cannot swallow the blocks after it. Without a closer, nothing is one. A failed scan is
     * remembered in [unclosed] (per closer: its start and where it stopped), so a later opener inside the same stretch
     * fails at once and many unclosed openers stay linear.
     */
    private fun displayMath(lines: List<String>, start: Int, unclosed: MutableMap<String, IntRange>): Pair<MdBlock.Math, Int>? {
        val first = lines[start].trim()
        val (open, close) = when {
            first.startsWith("$$") -> "$$" to "$$"
            first.startsWith("\\[") -> "\\[" to "\\]"
            else -> return null
        }
        val head = first.substring(open.length)
        if (head.endsWith(close)) {
            val tex = head.dropLast(close.length)
            // `$$a$$ b $$c$$` and a bare `$$` are not one-line blocks; a bare `$$` opens a multi-line one below.
            return if (tex.isNotBlank() && open !in tex && close !in tex) MdBlock.Math(tex.trim()) to start + 1 else null
        }
        // An earlier opener of this kind already scanned past this line without meeting a closer: so would this one.
        if (unclosed[close]?.let { start in it } == true) return null
        val body = StringBuilder(head)
        var i = start + 1
        while (i < lines.size) {
            val t = lines[i].trim()
            if (t.isEmpty()) break
            if (t.endsWith(close)) {
                if (body.isNotEmpty()) body.append('\n')
                body.append(t.dropLast(close.length))
                val tex = body.toString().trim()
                return if (tex.isEmpty()) null else MdBlock.Math(tex) to i + 1
            }
            if (body.isNotEmpty()) body.append('\n')
            body.append(t)
            i++
        }
        unclosed[close] = start..i
        return null
    }

    /** `#`–`######`, a space, then the text; a closing run of `#`s after a space is dropped (`## C#` keeps its `#`). */
    private fun atxHeading(line: String): MdBlock.Heading? {
        if (indentOf(line) > 3) return null
        val s = line.trimStart()
        val level = s.takeWhile { it == '#' }.length
        if (level !in 1..6 || level >= s.length || (s[level] != ' ' && s[level] != '\t')) return null
        val content = s.substring(level).trim()
        val unclosed = content.trimEnd('#')
        val text = when {
            unclosed.isEmpty() -> ""
            unclosed.last() == ' ' || unclosed.last() == '\t' -> unclosed.trimEnd()
            else -> content
        }
        return MdBlock.Heading(level, MarkdownInline.parse(text))
    }

    /** 1 for a `===` underline, 2 for `---` (three or more, nothing else on the line), else 0. */
    private fun setextLevel(trimmed: String): Int = when {
        trimmed.length < 3 -> 0
        trimmed.all { it == '=' } -> 1
        trimmed.all { it == '-' } -> 2
        else -> 0
    }

    /** Three or more of one of `-`, `*`, `_`, spaces allowed between (`- - -`), alone on the line. */
    private fun isRule(line: String): Boolean {
        if (indentOf(line) > 3) return false
        val s = line.trim()
        val mark = s.firstOrNull()?.takeIf { it == '-' || it == '*' || it == '_' } ?: return false
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

    /** A list item's marker: [number] is null for `-`/`*`/`+`; [textStart] is where its text begins in the line. */
    private class ListMarker(val indent: Int, val number: Int?, val textStart: Int)

    private fun listMarker(line: String): ListMarker? {
        var j = 0
        while (j < line.length && (line[j] == ' ' || line[j] == '\t')) j++
        if (j >= line.length) return null
        val c = line[j]
        if (c == '-' || c == '*' || c == '+') {
            return if (j + 1 < line.length && line[j + 1].isWhitespace()) ListMarker(indentOf(line), null, j + 2) else null
        }
        var k = j
        while (k < line.length && k - j < 9 && line[k].isDigit()) k++
        if (k == j || k + 1 >= line.length) return null
        if ((line[k] != '.' && line[k] != ')') || !line[k + 1].isWhitespace()) return null
        return ListMarker(indentOf(line), line.substring(j, k).toInt(), k + 2)
    }

    /**
     * Collects one list. Depth follows the indentation relative to the open items (so two- and four-space nesting
     * both read as one level deeper); numbers continue from the first marker at each depth (`1.` `1.` `1.` shows
     * 1, 2, 3, the way agents rely on).
     */
    private class ListBuilder(val ordered: Boolean) {
        private val texts = mutableListOf<StringBuilder>()
        private val depths = mutableListOf<Int>()
        private val numbers = mutableListOf<Int?>()
        private val checks = mutableListOf<Boolean?>()
        private val indents = ArrayList<Int>()
        private val counters = arrayOfNulls<Int>(MAX_DEPTH + 1)

        /** A top-level marker of the other kind starts a new list; nested markers of either kind stay. */
        fun accepts(marker: ListMarker): Boolean =
            indents.any { it < marker.indent } || (marker.number != null) == ordered

        fun add(marker: ListMarker, text: String) {
            while (indents.isNotEmpty() && indents.last() >= marker.indent) indents.removeAt(indents.lastIndex)
            val depth = minOf(indents.size, MAX_DEPTH)
            indents += marker.indent
            for (d in depth + 1..MAX_DEPTH) counters[d] = null
            val number = marker.number?.let { start -> (counters[depth]?.plus(1) ?: start) }
            counters[depth] = number
            val check = taskBox(text)
            texts += StringBuilder(if (check == null) text else text.substring(TASK_BOX).trimStart())
            depths += depth
            numbers += number
            checks += check
        }

        fun continueWith(text: String) {
            texts.lastOrNull()?.let { if (it.isEmpty()) it.append(text) else it.append(' ').append(text) }
        }

        fun build(): MdBlock {
            val items = texts.indices.map { MdListItem(MarkdownInline.parse(texts[it].toString()), depths[it], numbers[it], checks[it]) }
            return if (ordered) MdBlock.Numbered(items) else MdBlock.Bullets(items)
        }

        /** A GFM task box opening an item's text: `[ ]` unchecked, `[x]`/`[X]` checked; whitespace and text must follow. */
        private fun taskBox(text: String): Boolean? {
            if (text.length <= TASK_BOX || text[0] != '[' || text[2] != ']' || !text[TASK_BOX].isWhitespace()) return null
            return when (text[1]) {
                ' ' -> false
                'x', 'X' -> true
                else -> null
            }
        }

        private companion object {
            const val MAX_DEPTH = 3
            const val TASK_BOX = 3
        }
    }

    /**
     * The path of a whole-line `MEDIA:` directive (Hermes TUI and desktop `MEDIA_LINE_RE`): the directive may
     * be wrapped in a backtick or quote, the path may be backticked or quoted (then it may hold spaces), and an
     * unquoted path holds spaces only when it ends in a deliverable extension (desktop `_MEDIA_PATH_ANCHORED`).
     * Only absolute and `~/` paths count; anything else stays prose.
     */
    fun mediaPath(line: String): String? {
        val rest = MEDIA_LINE.matchEntire(line.trim())?.groupValues?.get(1) ?: return null
        val q = rest.first()
        val close = if (q in QUOTES) rest.indexOf(q, 1) else -1
        val path = if (close > 1) {
            // Only a wrapper's closing quote and sentence punctuation may follow the quoted path.
            val tail = rest.substring(close + 1)
            if (tail.any { it !in QUOTES && it !in TRAILING_PUNCT }) return null
            rest.substring(1, close)
        } else {
            // A trailing quote is the wrapper's residue (`MEDIA:/a.txt`), and trailing sentence punctuation is
            // prose (`MEDIA:/a.pdf.`), not the path; the desktop strips both.
            val bare = rest.trimEnd { it in QUOTES }.trimEnd { it in TRAILING_PUNCT }
            if (bare.any(Char::isWhitespace) && bare.substringAfterLast('.').lowercase() !in DELIVERY_EXTS) return null
            bare
        }
        val anchored = path.startsWith("/") || path.startsWith("~/")
        return path.takeIf { anchored && it.substringAfterLast('/').isNotBlank() }
    }

    /**
     * [trimmed] as an [MdBlock.Image] when it is exactly one `![alt](url)` (title allowed) with an `http(s)` url.
     * Read with [MarkdownInline], so the destination rules (nested parentheses, `<…>`, titles) match an inline image.
     */
    private fun imageLine(trimmed: String): MdBlock.Image? {
        if (!trimmed.startsWith("![") || !trimmed.endsWith(')')) return null
        val link = MarkdownInline.parse(trimmed).singleOrNull() as? MdSpan.Link ?: return null
        if (!link.url.startsWith("http://") && !link.url.startsWith("https://")) return null
        // An empty alt reads as the URL in a link; an image keeps it empty.
        val alt = if (trimmed.startsWith("![]")) "" else link.text
        return MdBlock.Image(alt, link.url)
    }

    private val WHITESPACE = Regex("\\s+")
    private val MEDIA_LINE = Regex("[`\"']?MEDIA:\\s*(\\S.*)")
    private const val QUOTES = "`\"'"
    private const val TRAILING_PUNCT = ".,;:!?"

    /** Hermes `MEDIA_DELIVERY_EXTS` (gateway/platforms/base.py). */
    private val DELIVERY_EXTS = setOf(
        "png", "jpg", "jpeg", "gif", "webp", "bmp", "tiff", "svg", "mp4", "mov", "avi", "mkv", "webm", "3gp",
        "mp3", "m2a", "wav", "ogg", "opus", "m4a", "flac", "pdf", "docx", "doc", "odt", "rtf", "txt", "md", "epub",
        "xlsx", "xls", "ods", "csv", "tsv", "json", "xml", "yaml", "yml", "kmz", "kml", "geojson", "gpx",
        "pptx", "ppt", "odp", "key", "zip", "tar", "gz", "tgz", "bz2", "xz", "7z", "rar", "apk", "ipa", "html", "htm",
    )
}
