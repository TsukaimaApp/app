package app.tsukaima.domain.cron

import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.format.DateTimeParseException
import java.util.Locale

/** Plain-English renderings of cron expressions and next-run timestamps. Pure: no Android, no clock reads. */
object CronHuman {
    private val hhmm = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)

    /**
     * "Every day at 09:00", "Weekdays at 18:00", "Every Monday at 08:30", "Every 15 minutes", "Every hour".
     * Anything else (ranges, lists, steps on other fields, malformed input) returns [expr] unchanged.
     */
    fun describe(expr: String): String {
        val f = expr.trim().split(Regex("\\s+"))
        if (f.size != 5) return expr
        val (minute, hour, dom, month, dow) = f
        if (dom != "*" || month != "*") return expr

        if (hour == "*" && dow == "*") {
            if (minute == "0") return "Every hour"
            val step = Regex("""\*/(\d{1,2})""").matchEntire(minute)?.groupValues?.get(1)?.toIntOrNull()
            if (step != null && step in 1..59) return if (step == 1) "Every minute" else "Every $step minutes"
            return expr
        }

        val m = minute.toIntOrNull()?.takeIf { it in 0..59 } ?: return expr
        val h = hour.toIntOrNull()?.takeIf { it in 0..23 } ?: return expr
        val time = "%02d:%02d".format(Locale.ROOT, h, m)
        return when {
            dow == "*" -> "Every day at $time"
            dow == "1-5" -> "Weekdays at $time"
            else -> {
                val d = dow.toIntOrNull()?.takeIf { it in 0..7 } ?: return expr
                val day = DayOfWeek.of(if (d == 0) 7 else d).getDisplayName(TextStyle.FULL, Locale.ENGLISH)
                "Every $day at $time"
            }
        }
    }

    /** List subtitle: "<human> · <expr>", or just [expr] when it has no plain-English form (no duplicate). */
    fun subtitle(expr: String): String {
        val human = describe(expr)
        return if (human == expr) expr else "$human · $expr"
    }

    /**
     * Relative label for an ISO-8601 timestamp with offset: "in 12m", "in 2h", "tomorrow 09:00", "in 3d",
     * "due" for the past, "" when [iso] is null or unparseable. Paused jobs are labelled "paused" by the caller.
     */
    fun nextRunLabel(iso: String?, now: Instant): String {
        val next = try {
            OffsetDateTime.parse(iso ?: return "")
        } catch (_: DateTimeParseException) {
            return ""
        }
        val diff = Duration.between(now, next.toInstant())
        if (diff.isNegative) return "due"
        return when {
            diff.toMinutes() < 60 -> "in ${diff.toMinutes().coerceAtLeast(1)}m"
            diff.toHours() < 24 -> "in ${diff.toHours()}h"
            next.toLocalDate() == now.atOffset(next.offset).toLocalDate().plusDays(1) -> "tomorrow ${next.format(hhmm)}"
            else -> "in ${diff.toDays()}d"
        }
    }
}
