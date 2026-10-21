package app.tsukaima.domain.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.random.Random

class ThinkingFlairTest {
    private fun designFile(): File {
        // Gradle runs unit tests with working directory `app/`.
        val candidates = listOf(
            File(System.getProperty("user.dir"), "../design/components/chat/StatusLine.jsx"),
            File(System.getProperty("user.dir"), "design/components/chat/StatusLine.jsx"),
        )
        return candidates.firstOrNull { it.isFile } ?: error("StatusLine.jsx not found from ${System.getProperty("user.dir")}")
    }

    private fun parseList(source: String, name: String): List<String> {
        val block = Regex("""export const $name = \[(.*?)];""", RegexOption.DOT_MATCHES_ALL)
            .find(source)?.groupValues?.get(1) ?: error("$name not found")
        return Regex("""'((?:[^'\\]|\\.)*)'""").findAll(block).map { unescapeJs(it.groupValues[1]) }.toList()
    }

    private fun unescapeJs(s: String): String =
        Regex("""\\u([0-9a-fA-F]{4})|\\(.)""").replace(s) { m ->
            m.groups[1]?.value?.toInt(16)?.toChar()?.toString() ?: m.groupValues[2]
        }

    @Test
    fun listsMatchDesignFile() {
        val source = designFile().readText()
        val base = parseList(source, "THINKING_FLAIR")
        val watch = parseList(source, "THINKING_FLAIR_WATCHLIST")
        assertTrue(base.isNotEmpty() && watch.isNotEmpty())
        assertEquals(base.size, ThinkingFlair.BASE.size)
        base.forEachIndexed { i, line -> assertEquals("BASE[$i]", line, ThinkingFlair.BASE[i]) }
        assertEquals(watch.size, ThinkingFlair.WATCHLIST.size)
        watch.forEachIndexed { i, line -> assertEquals("WATCHLIST[$i]", line, ThinkingFlair.WATCHLIST[i]) }
    }

    @Test
    fun nextCyclesWithoutRepeatingConsecutively() {
        val all = (ThinkingFlair.BASE + ThinkingFlair.WATCHLIST).toSet()
        ThinkingFlair.resetForTest()
        val random = Random(42)
        val seen = mutableSetOf<String>()
        var now = 0L
        var prev = ThinkingFlair.next(random, now)
        repeat(5_000) {
            now += ThinkingFlair.HOLD_MS
            val line = ThinkingFlair.next(random, now)
            assertNotEquals(prev, line)
            assertTrue(line in all)
            seen += line
            prev = line
        }
        // Over many draws every line shows up.
        assertEquals(all, seen)
    }

    @Test
    fun lineIsHeldForTheRotationInterval() {
        ThinkingFlair.resetForTest()
        val random = Random(7)
        val t0 = 1_000_000L
        val first = ThinkingFlair.next(random, t0)
        // Streaming deltas arrive many times a second: the line must not flicker between them.
        repeat(50) { i -> assertEquals(first, ThinkingFlair.next(random, t0 + i * 50L)) }
        assertEquals(first, ThinkingFlair.next(random, t0 + ThinkingFlair.HOLD_MS - 1))
        val second = ThinkingFlair.next(random, t0 + ThinkingFlair.HOLD_MS)
        assertNotEquals(first, second)
        assertEquals(second, ThinkingFlair.next(random, t0 + ThinkingFlair.HOLD_MS + 100))
    }
}
