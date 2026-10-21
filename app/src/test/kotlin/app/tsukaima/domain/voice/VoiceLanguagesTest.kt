package app.tsukaima.domain.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceLanguagesTest {
    private val names = mapOf("pl-PL" to "Polish (Poland)", "en-US" to "English (United States)")
    private val labelOf: (String) -> String = { names[it] ?: "name of $it" }

    @Test fun systemDefaultThenDeviceThenEngineThenFallback() {
        val tags = VoiceLanguages.options(listOf("pl-PL", "en-US"), listOf("fi-FI", "de-DE"), labelOf).map { it.tag }
        assertNull(tags[0])
        assertEquals(listOf("pl-PL", "en-US", "fi-FI", "de-DE"), tags.subList(1, 5))
        val rest = VoiceLanguages.FALLBACK.filterNot { it in listOf("pl-PL", "en-US", "de-DE") }
        assertEquals(rest, tags.drop(5))
    }

    @Test fun dedupesTagsIgnoringCase() {
        val tags = VoiceLanguages.options(listOf("en-us"), listOf("EN-US", "en-US", "fi-FI", "fi-fi"), labelOf).map { it.tag }
        assertEquals(1, tags.count { it.equals("en-US", ignoreCase = true) })
        assertEquals("en-us", tags[1]) // the first spelling wins
        assertEquals(1, tags.count { it.equals("fi-FI", ignoreCase = true) })
        assertEquals(tags.size, tags.map { it?.lowercase() }.toSet().size)
    }

    @Test fun labelsComeFromLabelOf() {
        val options = VoiceLanguages.options(listOf("pl-PL"), emptyList(), labelOf)
        assertEquals(VoiceLanguage(null, "System default"), options[0])
        assertEquals(VoiceLanguage("pl-PL", "Polish (Poland)"), options[1])
        assertEquals(VoiceLanguage("ja-JP", "name of ja-JP"), options.first { it.tag == "ja-JP" })
    }

    @Test fun labelFallsBackToTheTagOrSystemDefault() {
        val options = VoiceLanguages.options(listOf("pl-PL"), emptyList(), labelOf)
        assertEquals("Polish (Poland)", VoiceLanguages.label("pl-PL", options))
        assertEquals("Polish (Poland)", VoiceLanguages.label("pl-pl", options))
        assertEquals("xx-YY", VoiceLanguages.label("xx-YY", options))
        assertEquals("System default", VoiceLanguages.label(null, options))
        assertEquals("System default", VoiceLanguages.label(null, emptyList()))
    }
}
