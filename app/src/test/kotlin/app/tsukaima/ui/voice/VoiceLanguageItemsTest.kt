package app.tsukaima.ui.voice

import app.tsukaima.domain.voice.VoiceLanguage
import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceLanguageItemsTest {
    private val options = listOf(VoiceLanguage(null, "System default"), VoiceLanguage("pl-PL", "Polish (Poland)"))

    @Test fun checksTheCurrentTagIgnoringCase() {
        assertEquals(listOf(false, true), voiceLanguageItems(options, "pl-pl") {}.map { it.checked })
        assertEquals(listOf(true, false), voiceLanguageItems(options, null) {}.map { it.checked })
        assertEquals(listOf("System default", "Polish (Poland)"), voiceLanguageItems(options, null) {}.map { it.label })
    }

    @Test fun pickingHandsBackTheTag() {
        val picked = mutableListOf<String?>()
        voiceLanguageItems(options, null) { picked += it }.forEach { it.onClick() }
        assertEquals(listOf(null, "pl-PL"), picked)
    }
}
