package app.tsukaima.domain.chat

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** A chat as a Markdown document, for the share sheet. */
object ChatExport {
    private val STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    /**
     * [items] under [title]: each message under "## You" or "## Hermes" (a user message with its time when known),
     * a reply's text as it is (it is Markdown already), and each tool call as one list line with its command.
     * Reasoning, tool output, cards and notices are left out: the export is the conversation, not its log.
     * A message still streaming, and a send that failed or still waits, are left out too.
     */
    fun markdown(title: String?, items: List<ChatItem>, zone: ZoneId): String = buildString {
        appendLine("# ${title?.trim().takeUnless { it.isNullOrEmpty() } ?: "Chat with Hermes"}")
        var speaker: String? = null
        fun heading(who: String, at: Long? = null) {
            if (speaker == who && at == null) return
            speaker = who
            appendLine()
            appendLine(if (at == null) "## $who" else "## $who · ${STAMP.format(Instant.ofEpochMilli(at).atZone(zone))}")
            appendLine()
        }
        for (item in items) {
            when (item) {
                is ChatItem.UserMessage -> {
                    if (item.failed || item.queued || item.steerWaiting) continue
                    heading("You", item.at)
                    appendLine(item.text.trim())
                    item.attachments.forEach { appendLine("- attached: $it") }
                }
                is ChatItem.AssistantMessage -> {
                    if (item.streaming || item.text.isBlank()) continue
                    heading("Hermes")
                    appendLine(item.text.trim())
                }
                is ChatItem.ToolCall -> {
                    heading("Hermes")
                    val what = item.context?.trim()?.takeIf { it.isNotEmpty() }?.let { " · `${it.replace("`", "'")}`" }.orEmpty()
                    val how = when (item.status) {
                        ToolStatus.Failed -> " (failed)"
                        ToolStatus.Interrupted -> " (interrupted)"
                        else -> ""
                    }
                    appendLine("- tool `${item.name}`$what$how")
                }
                else -> Unit
            }
        }
    }.trimEnd() + "\n"

    /** A file name for the export of a chat titled [title]: letters, digits and dashes, at most 60 characters. */
    fun fileName(title: String?): String {
        val slug = title.orEmpty().lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(60).trim('-')
        return (slug.ifEmpty { "chat" }) + ".md"
    }
}
