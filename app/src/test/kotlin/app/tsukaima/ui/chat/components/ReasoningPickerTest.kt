package app.tsukaima.ui.chat.components

import app.tsukaima.domain.chat.AgentStatus
import app.tsukaima.domain.chat.ChatState
import app.tsukaima.domain.chat.SessionUsage
import app.tsukaima.ui.chat.ChatUiState
import app.tsukaima.ui.chat.REASONING_LEVELS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReasoningPickerTest {
    @Test
    fun thePickerOffersExactlyTheEffortsTheServerAccepts() =
        assertEquals(REASONING_LEVELS.map { it.first }, REASONING_PICKER.map { it.value })

    @Test
    fun eachEffortHasItsPhraseAndTheDefaultReadsAsUsual() {
        assertEquals("ready to go all out", reasoningPhrase("high"))
        assertEquals("taking it easy", reasoningPhrase("none"))
        assertEquals("going beyond its limits", reasoningPhrase("ultra"))
        assertEquals("at its usual pace", reasoningPhrase(""))
        assertEquals("at its usual pace", reasoningPhrase(null))
        assertEquals("at its usual pace", reasoningPhrase("something-new"))
        assertEquals(REASONING_PICKER.size, REASONING_PICKER.map { it.phrase }.toSet().size)
    }

    @Test
    fun onlyMaxAndUltraAreTopLevels() {
        assertEquals(listOf("max", "ultra"), REASONING_PICKER.filterIndexed { i, _ -> i >= TOP_LEVEL }.map { it.value })
        assertEquals(0, reasoningStrength("none"))
        assertEquals(-1, reasoningStrength(""))
    }

    @Test
    fun thePickerShowsOnlyOnTheUsersTurnInALiveWritableChat() {
        val live = ChatState(liveSessionId = "d4fb0dea")
        assertTrue(ChatUiState(chat = live).reasoningPickable)
        assertFalse(ChatUiState(chat = ChatState()).reasoningPickable)
        assertFalse(ChatUiState(chat = live.copy(status = AgentStatus.Replying)).reasoningPickable)
        assertFalse(ChatUiState(chat = live.copy(status = AgentStatus.Approval)).reasoningPickable)
        assertFalse(ChatUiState(chat = live.copy(compacting = true)).reasoningPickable)
        assertFalse(ChatUiState(chat = live, compacting = true).reasoningPickable)
        assertFalse(ChatUiState(chat = live, archived = true).reasoningPickable)
        assertFalse(ChatUiState(chat = live, gatewayStatus = AgentStatus.Stopped).reasoningPickable)
        assertFalse(ChatUiState(chat = live.copy(status = AgentStatus.Offline)).reasoningPickable)
    }

    @Test
    fun theMeterBesideThePickerIsThePercentageAlone() {
        assertEquals("9% ctx", SessionUsage(contextPercent = 9, contextUsed = 94_300, contextMax = 1_000_000).shortMeter)
        assertEquals("2% ctx", SessionUsage(contextUsed = 20_000, contextMax = 1_000_000).shortMeter)
        assertEquals("16k ctx", SessionUsage(contextUsed = 16_000).shortMeter)
    }
}
