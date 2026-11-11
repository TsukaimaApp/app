package app.tsukaima.domain.chat

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** One row of the transcript as drawn: a [ChatItem], or something the list adds around the items. */
sealed interface TranscriptRow {
    val key: String

    data class Item(val item: ChatItem) : TranscriptRow {
        override val key: String get() = item.id
    }

    /** When the message below it was sent ([at], epoch millis): shown at a new day and after a long gap. */
    data class TimeMark(override val key: String, val at: Long) : TranscriptRow

    /**
     * The work of an earlier turn (its tool calls and the reasoning between them) as one line: [count] steps,
     * [tools] of them tool calls, [failed] failed. [open]: the user unfolded it and the steps follow as rows.
     */
    data class Steps(
        override val key: String,
        val count: Int,
        val tools: Int,
        val failed: Int,
        val open: Boolean,
    ) : TranscriptRow {
        /** The group id [ChatReducer.toggleSteps] takes. */
        val id: String get() = key
    }
}

object TranscriptRows {
    /** A time mark also separates messages sent this long apart on the same day. */
    const val GAP_MS = 60 * 60 * 1000L

    /** A run of fewer steps than this stays as it is: folding it would save no room. */
    const val MIN_STEPS = 3

    /**
     * [items] as rows. Earlier turns fold each run of [MIN_STEPS] or more steps into a [TranscriptRow.Steps] line
     * (unfolded when its id is in [openSteps]); the current turn, everything from the last message the user sent as
     * a turn of its own, is never folded, so nothing moves while the agent works. A [TranscriptRow.TimeMark] goes
     * above a user message that starts a new day in [zone] or follows the previous one by [GAP_MS].
     */
    fun build(items: List<ChatItem>, openSteps: Set<String>, zone: ZoneId): List<TranscriptRow> {
        val current = items.indexOfLast { it is ChatItem.UserMessage && !it.steer && !it.queued }.let { if (it < 0) 0 else it }
        val rows = ArrayList<TranscriptRow>(items.size + 4)
        var lastAt: Long? = null
        var i = 0
        while (i < items.size) {
            val item = items[i]
            if (i < current && item.isStep()) {
                var end = i
                while (end < current && items[end].isStep()) end++
                val run = items.subList(i, end)
                if (run.size >= MIN_STEPS && run.none { it.isLive() }) {
                    val key = "steps-${run.first().id}"
                    val open = key in openSteps
                    rows += TranscriptRow.Steps(
                        key, run.size, run.count { it is ChatItem.ToolCall },
                        run.count { it is ChatItem.ToolCall && it.status == ToolStatus.Failed }, open,
                    )
                    if (open) run.mapTo(rows) { TranscriptRow.Item(it) }
                } else {
                    run.mapTo(rows) { TranscriptRow.Item(it) }
                }
                i = end
                continue
            }
            if (item is ChatItem.UserMessage && !item.steer && item.at != null) {
                val before = lastAt
                if (before == null || item.at - before >= GAP_MS || day(item.at, zone) != day(before, zone)) {
                    rows += TranscriptRow.TimeMark("time-${item.id}", item.at)
                }
                lastAt = item.at
            }
            rows += TranscriptRow.Item(item)
            i++
        }
        return rows
    }

    /** A tool call, or reasoning with no reply text (the thinking before a tool call). */
    private fun ChatItem.isStep(): Boolean =
        this is ChatItem.ToolCall || this is ChatItem.AssistantMessage && text.isBlank()

    private fun ChatItem.isLive(): Boolean =
        this is ChatItem.ToolCall && status == ToolStatus.Running || this is ChatItem.AssistantMessage && streaming

    private fun day(at: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()

    /**
     * A time mark's text for [at] read at [now]: "Today 09:02", "Yesterday 21:14", "Mon 3 Nov 18:05" within the
     * year, "3 Nov 2025 18:05" before it. [hour24] follows the phone's clock setting.
     */
    fun label(at: Long, now: Long, zone: ZoneId, hour24: Boolean, locale: Locale = Locale.getDefault()): String {
        val then = Instant.ofEpochMilli(at).atZone(zone)
        val today = day(now, zone)
        val time = then.format(DateTimeFormatter.ofPattern(if (hour24) "HH:mm" else "h:mm a", locale))
        val date = then.toLocalDate()
        val month = date.month.getDisplayName(TextStyle.SHORT, locale)
        return when {
            date == today -> "Today $time"
            date == today.minusDays(1) -> "Yesterday $time"
            date.year == today.year -> "${date.dayOfWeek.getDisplayName(TextStyle.SHORT, locale)} ${date.dayOfMonth} $month $time"
            else -> "${date.dayOfMonth} $month ${date.year} $time"
        }
    }

    /** "Thought for 12s", "Thought for 2m 5s"; "Thought" when it was not timed (history). */
    fun thoughtLabel(ms: Long?): String {
        val s = ms?.let { (it + 500) / 1000 } ?: return "Thought"
        return when {
            s < 1 -> "Thought briefly"
            s < 60 -> "Thought for ${s}s"
            else -> "Thought for ${s / 60}m ${s % 60}s"
        }
    }

    /** A finished tool's duration: "0.4s", "3.2s", "42s", "2m 5s"; null for none (a history row). */
    fun durationLabel(seconds: Double?): String? {
        val s = seconds?.takeIf { it >= 0 } ?: return null
        return when {
            s < 9.95 -> String.format(Locale.US, "%.1fs", s)
            s < 59.5 -> "${Math.round(s)}s"
            else -> Math.round(s).let { "${it / 60}m ${it % 60}s" }
        }
    }

    /** The folded tail of reasoning that is still streaming: its last [chars] characters from a word boundary. */
    fun tail(text: String, chars: Int = 220): String {
        val flat = text.trim().replace(WHITESPACE, " ")
        if (flat.length <= chars) return flat
        val cut = flat.substring(flat.length - chars)
        return "…" + cut.substring(cut.indexOf(' ') + 1)
    }

    private val WHITESPACE = Regex("\\s+")
}
