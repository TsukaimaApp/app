package app.tsukaima.domain.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class LiveClockTest {
    @Test fun liveClockFormatsMinutesAndPaddedSeconds() {
        assertEquals("0:00", liveClock(0))
        assertEquals("1:04", liveClock(64))
        assertEquals("12:30", liveClock(750))
    }
}
