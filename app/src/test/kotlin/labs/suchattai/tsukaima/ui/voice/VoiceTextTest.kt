package labs.suchattai.tsukaima.ui.voice

import labs.suchattai.tsukaima.domain.chat.ChatItem
import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceTextTest {
    private fun reply(streaming: Boolean) = ChatItem.AssistantMessage("a1", "Hello there. How are you?", streaming, reasoning = null)

    @Test
    fun showsWhatWasSpoken() = assertEquals("Hello there.", voiceText(reply(streaming = true), mapOf("a1" to 12)))

    @Test
    fun streamingReplyWithoutCursorShowsNothing() = assertEquals("", voiceText(reply(streaming = true), emptyMap()))

    @Test
    fun completeReplyWithoutCursorShowsAll() = assertEquals("Hello there. How are you?", voiceText(reply(streaming = false), emptyMap()))

    @Test
    fun cursorBeyondTextIsClamped() = assertEquals("Hello there. How are you?", voiceText(reply(streaming = false), mapOf("a1" to 99)))
}
