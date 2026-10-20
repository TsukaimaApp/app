package labs.suchattai.tsukaima.domain.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionUsageTest {
    @Test
    fun meterShowsTheContextWindowNotTheBillingTotal() {
        // A long session: Hermes' `total` sums every call's input+output (millions); the window is what matters.
        assertEquals("15.8k / 1M ctx · 2%", SessionUsage(contextPercent = 2, contextUsed = 15_754, contextMax = 1_000_000, totalTokens = 3_123_456).meter)
        assertEquals("128k / 200k ctx · 64%", SessionUsage(contextUsed = 128_000, contextMax = 200_000, totalTokens = 9_999_999).meter)
        assertEquals("64% ctx", SessionUsage(contextPercent = 64, totalTokens = 3_000_000).meter)
        assertEquals("12.3k ctx", SessionUsage(contextUsed = 12_345).meter)
        assertNull(SessionUsage(totalTokens = 3_000_000).meter)
        assertNull(SessionUsage().meter)
    }

    @Test
    fun compactTokensDropsTrailingZeros() {
        assertEquals("950", compactTokens(950))
        assertEquals("1k", compactTokens(1_000))
        assertEquals("12.3k", compactTokens(12_345))
        assertEquals("200k", compactTokens(200_000))
        assertEquals("1M", compactTokens(1_000_000))
        assertEquals("1.2M", compactTokens(1_234_567))
        assertEquals("3.1M", compactTokens(3_123_456))
    }
}
