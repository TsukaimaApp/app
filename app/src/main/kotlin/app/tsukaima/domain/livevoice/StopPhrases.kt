package app.tsukaima.domain.livevoice

import java.text.Normalizer

/** Spoken stop commands; port of the desktop's `voice-stop-word.ts` (English built-ins only). */
object StopPhrases {
    private val punctuation = Regex("\\p{P}+")
    private val spaces = Regex("\\s+")

    private fun normalize(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase()
            .replace(punctuation, " ").replace(spaces, " ").trim()

    private val phrases: Set<String> = listOf(
        "stop", "stop listening", "stop it", "stop please", "please stop", "stop stop", "that is all", "that's all",
        "never mind", "nevermind", "end conversation", "end the conversation", "goodbye", "good bye", "bye", "cancel",
    ).map(::normalize).toSet()

    private val addressPrefixes = listOf("hey hermes", "hermes", "ok", "okay", "hey")

    /** True when the whole [utterance] is a stop command, optionally addressed to Hermes ("hey hermes, stop"). */
    fun isStopCommand(utterance: String): Boolean {
        val n = normalize(utterance)
        if (n.isEmpty()) return false
        if (n in phrases) return true
        val prefix = addressPrefixes.firstOrNull { n.startsWith("$it ") } ?: return false
        return n.substring(prefix.length + 1).trim() in phrases
    }
}
