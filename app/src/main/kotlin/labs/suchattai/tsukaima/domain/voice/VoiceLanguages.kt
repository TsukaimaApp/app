package labs.suchattai.tsukaima.domain.voice

/** One choice in the voice language picker: [tag] is a BCP-47 tag (`pl-PL`), null = follow the system default. */
data class VoiceLanguage(val tag: String?, val label: String)

/**
 * Options for the voice language setting (Settings → Voice → Language, and the voice screen's translate button). One
 * setting drives both speech recognition and text-to-speech. Pure: the Android side supplies the device locales, the
 * TTS engine's languages and how a tag is named.
 */
object VoiceLanguages {
    const val SYSTEM_DEFAULT = "System default"

    /** Offered even when neither the device nor the TTS engine lists them (the engine may not be ready yet). */
    val FALLBACK: List<String> = listOf(
        "en-US", "en-GB", "pl-PL", "de-DE", "fr-FR", "es-ES", "it-IT", "pt-BR",
        "nl-NL", "sv-SE", "uk-UA", "cs-CZ", "ja-JP", "ko-KR", "zh-CN",
    )

    /**
     * "System default" first, then the [device] locales in order (the first is the current one), then the [engine]
     * languages not listed yet, then [FALLBACK]; tags are deduplicated case-insensitively (first spelling wins) and
     * named by [labelOf].
     */
    fun options(device: List<String>, engine: List<String>, labelOf: (String) -> String): List<VoiceLanguage> {
        val seen = HashSet<String>()
        val tags = (device + engine + FALLBACK).filter { it.isNotBlank() && seen.add(it.lowercase()) }
        return listOf(VoiceLanguage(null, SYSTEM_DEFAULT)) + tags.map { VoiceLanguage(it, labelOf(it)) }
    }

    /** The label of [tag] among [options]; the raw tag when it is not listed; "System default" for null. */
    fun label(tag: String?, options: List<VoiceLanguage>): String {
        if (tag == null) return SYSTEM_DEFAULT
        return options.firstOrNull { it.tag.equals(tag, ignoreCase = true) }?.label ?: tag
    }
}
