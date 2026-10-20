package labs.suchattai.tsukaima.domain.markdown

/**
 * GFM pipe tables for [Markdown]: a header line holding `|`, then a separator row (`|:--|:-:|--:|`) with as many
 * cells, then rows until a blank line or a line without `|`. Cells split on unescaped `|` (`\|` is a literal
 * pipe), the outer pipes are optional, short rows are padded with empty cells and extra cells dropped. While a
 * reply streams, the header alone stays a paragraph; once the separator is in, the table shows the rows so far,
 * including one still being typed. Linear in the input; never throws.
 */
internal object MarkdownTables {
    /** The table starting at `lines[start]` and the index of the first line after it, or null when none starts there. */
    fun parse(lines: List<String>, start: Int): Pair<MdBlock.Table, Int>? {
        if (start + 1 >= lines.size) return null
        val headerLine = lines[start]
        if ('|' !in headerLine) return null
        val align = separator(lines[start + 1]) ?: return null
        val header = splitCells(headerLine)
        if (header.size != align.size) return null

        val rows = mutableListOf<List<List<MdSpan>>>()
        var i = start + 2
        while (i < lines.size && lines[i].isNotBlank() && '|' in lines[i]) {
            val cells = splitCells(lines[i])
            rows += List(header.size) { c -> cells.getOrNull(c)?.let(MarkdownInline::parse) ?: emptyList() }
            i++
        }
        return MdBlock.Table(header.map(MarkdownInline::parse), align, rows) to i
    }

    /** The column alignments of a separator row, or null when [line] is not one (it must hold a `|`). */
    private fun separator(line: String): List<MdAlign>? {
        if ('|' !in line) return null
        val cells = splitCells(line)
        if (cells.isEmpty()) return null
        return cells.map { cell ->
            val left = cell.startsWith(':')
            val right = cell.length > 1 && cell.endsWith(':')
            val dashes = cell.substring(if (left) 1 else 0, cell.length - if (right) 1 else 0)
            if (dashes.isEmpty() || dashes.any { it != '-' }) return null
            when {
                left && right -> MdAlign.Center
                right -> MdAlign.End
                else -> MdAlign.Start
            }
        }
    }

    /** [row]'s trimmed cells: split on unescaped `|`, with a leading and a trailing pipe optional. */
    fun splitCells(row: String): List<String> {
        val s = row.trim()
        val cells = mutableListOf<String>()
        val cell = StringBuilder()
        var lastPipe = -1
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '\\' && i + 1 < s.length && s[i + 1] == '|' -> { cell.append('|'); i++ }
                c == '|' -> { cells += cell.toString().trim(); cell.setLength(0); lastPipe = i }
                else -> cell.append(c)
            }
            i++
        }
        cells += cell.toString().trim()
        if (s.startsWith('|')) cells.removeAt(0)
        // The same pipe cannot be both the leading and the trailing one (`|` alone is one empty cell).
        if (lastPipe > 0 && lastPipe == s.lastIndex && cells.size > 1) cells.removeAt(cells.lastIndex)
        return cells
    }
}
