package labs.suchattai.tsukaima.ui.voice

import labs.suchattai.tsukaima.domain.voice.VoiceLanguage
import labs.suchattai.tsukaima.ui.components.MenuItem

/**
 * The voice language sheet (Settings → Voice → Language and the voice screen's translate button): every option,
 * check on [current] (tags compared ignoring case, null = "System default"); [onPick] gets the option's tag.
 */
internal fun voiceLanguageItems(
    options: List<VoiceLanguage>,
    current: String?,
    onPick: (String?) -> Unit,
): List<MenuItem> = options.map { option ->
    MenuItem(option.label, null, checked = option.tag.equals(current, ignoreCase = true)) { onPick(option.tag) }
}
