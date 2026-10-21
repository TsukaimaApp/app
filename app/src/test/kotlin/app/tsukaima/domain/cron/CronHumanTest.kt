package app.tsukaima.domain.cron

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class CronHumanTest {
    @Test fun dailyAtNine() = assertEquals("Every day at 09:00", CronHuman.describe("0 9 * * *"))
    @Test fun weekdays() = assertEquals("Weekdays at 18:00", CronHuman.describe("0 18 * * 1-5"))
    @Test fun specificWeekday() = assertEquals("Every Monday at 08:30", CronHuman.describe("30 8 * * 1"))
    @Test fun sundayAsZeroOrSeven() {
        assertEquals("Every Sunday at 07:00", CronHuman.describe("0 7 * * 0"))
        assertEquals("Every Sunday at 07:00", CronHuman.describe("0 7 * * 7"))
    }
    @Test fun everyFifteenMinutes() = assertEquals("Every 15 minutes", CronHuman.describe("*/15 * * * *"))
    @Test fun everyHour() = assertEquals("Every hour", CronHuman.describe("0 * * * *"))
    @Test fun unknownFallsBack() {
        assertEquals("*/30 8-20 * * 1-5", CronHuman.describe("*/30 8-20 * * 1-5"))
        assertEquals("nonsense", CronHuman.describe("nonsense"))
        assertEquals("0 25 * * *", CronHuman.describe("0 25 * * *"))
    }

    @Test
    fun cronOnlySubtitleWhenDescribeIsRaw() {
        assertEquals("every 60m", CronHuman.subtitle("every 60m"))
        assertEquals("Every day at 09:00 · 0 9 * * *", CronHuman.subtitle("0 9 * * *"))
    }

    // now = 2026-10-20 07:00 at +02:00
    private val now = Instant.parse("2026-10-20T05:00:00Z")

    @Test fun nextRunInHours() = assertEquals("in 2h", CronHuman.nextRunLabel("2026-10-20T09:00:00+02:00", now))
    @Test fun nextRunInMinutes() = assertEquals("in 12m", CronHuman.nextRunLabel("2026-10-20T07:12:30+02:00", now))
    @Test fun nextRunTomorrow() = assertEquals("tomorrow 09:00", CronHuman.nextRunLabel("2026-10-21T09:00:00+02:00", now))
    @Test fun nextRunInDays() = assertEquals("in 3d", CronHuman.nextRunLabel("2026-10-23T09:00:00+02:00", now))
    @Test fun nextRunPastIsDue() = assertEquals("due", CronHuman.nextRunLabel("2026-10-20T06:00:00+02:00", now))
    @Test fun nextRunUnparseable() {
        assertEquals("", CronHuman.nextRunLabel(null, now))
        assertEquals("", CronHuman.nextRunLabel("soon", now))
    }
}
