package app.tsukaima.domain.livevoice

import org.junit.Assert.assertEquals
import org.junit.Test

class LiveTranscriptTest {
    private fun frag(i: Int, end: Long = i.toLong()) = LiveFragment(LiveSpeaker.User, "f$i", 0, end)

    @Test fun transcriptTrimsTo1500After2000() {
        val t = LiveTranscript()
        for (i in 1..2_000) t.append(frag(i))
        assertEquals(2_000, t.size)
        t.append(frag(2_001))
        assertEquals(1_500, t.size)
        assertEquals("f2001", t.contextWindow(Long.MAX_VALUE, 10_000).last().text)
        assertEquals("f502", t.contextWindow(Long.MAX_VALUE, 10_000).first().text)
    }

    @Test fun contextWindowKeepsFiveMinutesAndAtMost80() {
        val t = LiveTranscript()
        assertEquals(emptyList<LiveFragment>(), t.contextWindow())
        t.append(frag(0, 0))
        t.append(frag(1, 100_001))
        t.append(frag(2, 400_000))
        t.append(frag(3, 400_001))
        assertEquals(listOf("f1", "f2", "f3"), t.contextWindow().map { it.text })
        val many = LiveTranscript()
        for (i in 1..200) many.append(frag(i, 1_000L + i))
        val w = many.contextWindow()
        assertEquals(80, w.size)
        assertEquals("f121", w.first().text)
        assertEquals("f200", w.last().text)
    }
}
