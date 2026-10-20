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
}

/** Minimal markdown: fenced code, bullets, paragraphs, bold, italic, inline code. Never throws. */
object Markdown {
    fun parse(text: String): List<MdBlock> {
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
