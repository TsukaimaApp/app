package app.tsukaima.domain.livevoice

import app.tsukaima.domain.chat.ChatItem
import kotlinx.serialization.Serializable

/** What a delegation submits: [prompt] is the user's last spoken turn, [context] the recent exchange. */
data class DelegationPrompt(val prompt: String, val context: String)

/** One text turn that seeds a new live session (`POST …/voice-live/session` `history`). */
@Serializable
data class LiveHistoryMessage(val type: String = "message", val role: String, val content: List<Part>) {
    /** A text part: `input_text` for the user, `output_text` for the assistant. */
    @Serializable
    data class Part(val type: String, val text: String)
}

/** Text helpers for the live voice engine; ports of the desktop's `voice-live.ts` functions. */
object LiveText {
    private val whitespace = Regex("\\s+")
    private val sentenceEnd = Regex("(?<=[.!?])\\s+")

    private fun String.collapsed() = replace(whitespace, " ").trim()

    /** Splits a reply into chunks of at most [limit] chars on sentence boundaries (a longer sentence is cut hard). */
    fun chunkForCommentary(text: String, limit: Int = 1_400): List<String> {
        val clean = text.collapsed()
        if (clean.isEmpty()) return emptyList()
        if (clean.length <= limit) return listOf(clean)
        val chunks = ArrayList<String>()
        var current = ""
        for (sentence in clean.split(sentenceEnd)) {
            if (sentence.length > limit) {
                if (current.isNotEmpty()) {
                    chunks.add(current)
                    current = ""
                }
                chunks.addAll(sentence.chunked(limit))
                continue
            }
            val candidate = if (current.isEmpty()) sentence else "$current $sentence"
            if (candidate.length > limit) {
                chunks.add(current)
                current = sentence
            } else {
                current = candidate
            }
        }
        if (current.isNotEmpty()) chunks.add(current)
        return chunks
    }

    /** Merges same-speaker runs of [fragments] into turns; the prompt is the last user turn, else the context tail. */
    fun delegationPrompt(fragments: List<LiveFragment>): DelegationPrompt {
        val turns = ArrayList<Pair<LiveSpeaker, StringBuilder>>()
        for (f in fragments) {
            val last = turns.lastOrNull()
            if (last != null && last.first == f.speaker) last.second.append(f.text)
            else turns.add(f.speaker to StringBuilder(f.text))
        }
        val prompt = turns.lastOrNull { it.first == LiveSpeaker.User }?.second?.toString().orEmpty().collapsed()
        val context = turns
            .map { (s, t) ->
                val label = if (s == LiveSpeaker.User) "User" else "Voice assistant"
                "$label: ${t.toString().collapsed()}"
            }
            .filter { !it.endsWith(": ") }
            .joinToString("\n")
        return DelegationPrompt(prompt.ifEmpty { context.takeLast(400) }, context)
    }

    /** Seed history from the chat: newest text turns within [maxMessages] and [maxChars], oldest first. */
    fun liveHistory(items: List<ChatItem>, maxMessages: Int = 24, maxChars: Int = 6_000): List<LiveHistoryMessage> {
        val out = ArrayList<LiveHistoryMessage>()
        var budget = maxChars
        for (item in items.asReversed()) {
            val (role, raw, partType) = when (item) {
                is ChatItem.UserMessage -> Triple("user", item.text, "input_text")
                is ChatItem.AssistantMessage -> Triple("assistant", item.text, "output_text")
                else -> continue
            }
            val text = raw.collapsed().take(1_200)
            if (text.isEmpty()) continue
            if (out.size >= maxMessages || budget - text.length < 0) break
            budget -= text.length
            out.add(LiveHistoryMessage(role = role, content = listOf(LiveHistoryMessage.Part(partType, text))))
        }
        out.reverse()
        return out
    }
}
