package labs.suchattai.tsukaima.domain.markdown

sealed interface MdSpan {
    data class Text(val s: String) : MdSpan
    data class Bold(val s: String) : MdSpan
    data class Italic(val s: String) : MdSpan
    data class Code(val s: String) : MdSpan
}

sealed interface MdBlock {
    data class Paragraph(val spans: List<MdSpan>) : MdBlock
    data class Code(val lang: String?, val code: String) : MdBlock
    data class Bullets(val items: List<List<MdSpan>>) : MdBlock

    /** A file the agent delivered with a whole-line `MEDIA:<path>` directive (`/…` or `~/…`). */
    data class Media(val path: String) : MdBlock
}

/**
 * Minimal markdown: fenced code, bullets, paragraphs, bold, italic, inline code, and Hermes `MEDIA:` file
 * deliveries. Never throws.
 */
object Markdown {
    /**
     * With [streaming], the last line is never read as a `MEDIA:` directive: a half-streamed path would flash
     * a card for a file that does not exist.
     */
    fun parse(text: String, streaming: Boolean = false): List<MdBlock> {
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val blocks = mutableListOf<MdBlock>()
        val para = mutableListOf<String>()
        var bullets: MutableList<List<MdSpan>>? = null

        fun flushPara() {
            if (para.isNotEmpty()) {
                blocks += MdBlock.Paragraph(parseInline(para.joinToString("\n")))
                para.clear()
            }
        }

        fun flushBullets() {
            bullets?.let { blocks += MdBlock.Bullets(it.toList()) }
            bullets = null
        }

        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()
            val media = if (streaming && i == lines.lastIndex) null else mediaPath(trimmed)
            when {
                trimmed.startsWith("```") -> {
                    flushPara(); flushBullets()
                    val lang = trimmed.removePrefix("```").trimStart('`').trim()
                        .split(Regex("\\s+")).firstOrNull()?.takeIf { it.isNotEmpty() }
                    val body = mutableListOf<String>()
                    i++
                    while (i < lines.size && lines[i].trim() != "```") {
                        body += lines[i]; i++
                    }
                    blocks += MdBlock.Code(lang, body.joinToString("\n"))
                    // i is on the closing fence (or past the end); the increment below skips it.
                }
                trimmed.isEmpty() -> { flushPara(); flushBullets() }
                media != null -> { flushPara(); flushBullets(); blocks += MdBlock.Media(media) }
                line.startsWith("- ") || line.startsWith("* ") -> {
                    flushPara()
                    val list = bullets ?: mutableListOf<List<MdSpan>>().also { bullets = it }
                    list += parseInline(line.substring(2).trim())
                }
                else -> { flushBullets(); para += line }
            }
            i++
        }
        flushPara(); flushBullets()
        return blocks
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
            val tail = rest.substring(close + 1)
            if (tail.length > 1 || (tail.length == 1 && tail[0] !in QUOTES)) return null
            rest.substring(1, close)
        } else {
            // A trailing backtick or double quote is the wrapper's residue (`MEDIA:/a.txt`), not the path.
            val bare = if (rest.last() == '`' || rest.last() == '"') rest.dropLast(1) else rest
            if (bare.any(Char::isWhitespace) && bare.substringAfterLast('.').lowercase() !in DELIVERY_EXTS) return null
            bare
        }
        val anchored = path.startsWith("/") || path.startsWith("~/")
        return path.takeIf { anchored && it.substringAfterLast('/').isNotBlank() }
    }

    private val MEDIA_LINE = Regex("[`\"']?MEDIA:\\s*(\\S.*)")
    private const val QUOTES = "`\"'"

    /** Hermes `MEDIA_DELIVERY_EXTS` (gateway/platforms/base.py). */
    private val DELIVERY_EXTS = setOf(
        "png", "jpg", "jpeg", "gif", "webp", "bmp", "tiff", "svg", "mp4", "mov", "avi", "mkv", "webm", "3gp",
        "mp3", "m2a", "wav", "ogg", "opus", "m4a", "flac", "pdf", "docx", "doc", "odt", "rtf", "txt", "md", "epub",
        "xlsx", "xls", "ods", "csv", "tsv", "json", "xml", "yaml", "yml", "kmz", "kml", "geojson", "gpx",
        "pptx", "ppt", "odp", "key", "zip", "tar", "gz", "tgz", "bz2", "xz", "7z", "rar", "apk", "ipa", "html", "htm",
    )

    private fun parseInline(s: String): List<MdSpan> {
        val spans = mutableListOf<MdSpan>()
        val lit = StringBuilder()
        fun emit(span: MdSpan) {
            if (lit.isNotEmpty()) { spans += MdSpan.Text(lit.toString()); lit.setLength(0) }
            spans += span
        }

        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '`' -> {
                    val j = s.indexOf('`', i + 1)
                    if (j > i + 1) { emit(MdSpan.Code(s.substring(i + 1, j))); i = j + 1 }
                    else { lit.append(c); i++ }
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
}
