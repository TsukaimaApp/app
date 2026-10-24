package app.tsukaima.share

/** What an incoming share intent offers, before any I/O: [text] and [streams] (content URI strings). */
data class ShareRequest(val text: String, val streams: List<String>)

/** Pure rules for turning a share intent's parts into a [ShareRequest] and a one-line summary. */
object ShareIntake {
    const val ACTION_SEND = "android.intent.action.SEND"
    const val ACTION_SEND_MULTIPLE = "android.intent.action.SEND_MULTIPLE"

    private const val SUMMARY_CHARS = 60

    /** null when nothing usable: no text and no stream, or an action/type outside the manifest filters. */
    fun parse(action: String?, type: String?, text: String?, streams: List<String>): ShareRequest? {
        val mime = type?.lowercase()?.trim().orEmpty()
        val typeOk = mime == "text/plain" || mime.startsWith("image/") || mime == "application/pdf" ||
            (action == ACTION_SEND_MULTIPLE && mime == "*/*")
        val ok = (action == ACTION_SEND || action == ACTION_SEND_MULTIPLE) && typeOk
        if (!ok) return null
        val body = text.orEmpty().trim()
        val uris = streams.filter { it.isNotBlank() }
        if (body.isEmpty() && uris.isEmpty()) return null
        return ShareRequest(body, uris)
    }

    /**
     * "Link" for a single http(s) URL, else the first line of text cut to 60 chars with "…", else "3 images" /
     * "2 files" / "report.pdf".
     */
    fun summary(text: String, files: List<SharedFile>): String {
        val t = text.trim()
        if (t.isNotEmpty()) {
            if ((t.startsWith("http://") || t.startsWith("https://")) && t.none { it.isWhitespace() }) return "Link"
            val line = t.lineSequence().first().trim()
            return if (line.length > SUMMARY_CHARS) line.take(SUMMARY_CHARS) + "…" else line
        }
        return when {
            files.size == 1 -> files[0].name
            files.size > 1 && files.all { it.mime?.startsWith("image/") == true } -> "${files.size} images"
            files.size > 1 -> "${files.size} files"
            else -> ""
        }
    }
}
