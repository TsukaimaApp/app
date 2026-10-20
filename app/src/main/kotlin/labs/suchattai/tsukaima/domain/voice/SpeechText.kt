package labs.suchattai.tsukaima.domain.voice

/** Turns Hermes' markdown replies into what text-to-speech should say. */
object SpeechText {
    private val fence = Regex("^\\s*(```|~~~)")
    private val media = Regex("^\\s*MEDIA:")
    private val heading = Regex("^\\s{0,3}#{1,6}\\s+")
    private val quote = Regex("^\\s*>+\\s?")
    private val listPrefix = Regex("^\\s*(?:[-*+]|\\d+[.)])\\s+")
    private val link = Regex("!?\\[([^\\]]*)]\\([^)]*\\)")
    private val url = Regex("https?://\\S+")
    private val inlineCode = Regex("`([^`]*)`")
    private val strong = Regex("(\\*\\*|__)(.+?)\\1")
    private val emphasis = Regex("(?<![\\w*])([*_])(?=\\S)(.+?)(?<=\\S)\\1(?![\\w*])")
    private val strike = Regex("~~(.+?)~~")

    /**
     * Speakable text for [markdown]: fenced code blocks (an unterminated fence runs to the end)
     * and `MEDIA:` lines are dropped, inline markup is reduced to its text, links keep their
     * label, bare URLs are dropped, and blank lines disappear. The result is trimmed.
     */
    fun plain(markdown: String): String {
        val out = ArrayList<String>()
        var inFence = false
        for (raw in markdown.lines()) {
            if (fence.containsMatchIn(raw)) {
                inFence = !inFence
                continue
            }
            if (inFence || media.containsMatchIn(raw)) continue
            var s = raw
            s = heading.replace(s, "")
            s = quote.replace(s, "")
            s = listPrefix.replace(s, "")
            s = link.replace(s) { it.groupValues[1] }
            s = url.replace(s, "")
            s = inlineCode.replace(s) { it.groupValues[1] }
            s = strong.replace(s) { it.groupValues[2] }
            s = emphasis.replace(s) { it.groupValues[2] }
            s = strike.replace(s) { it.groupValues[1] }
            s = s.trim()
            if (s.isNotEmpty()) out += s
        }
        return out.joinToString("\n")
    }
}

/** A sentence ready to speak; [end] is the index in the original text the transcript may reveal up to. */
data class SpokenChunk(val text: String, val end: Int)

/**
 * Splits a streaming reply into sentences. Feed it the reply's full text so far; it remembers
 * how much it already emitted, so repeated calls on a growing prefix are idempotent.
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
        while (i < text.length) {
            val c = text[i]
            val end = when {
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
