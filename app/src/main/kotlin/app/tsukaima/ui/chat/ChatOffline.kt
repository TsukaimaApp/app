package app.tsukaima.ui.chat

import app.tsukaima.domain.chat.ChatItem
import app.tsukaima.domain.chat.OutboxMessage
import app.tsukaima.domain.chat.SavedChat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The chat without Hermes: the transcript saved on the phone, the composer's draft, and the messages written offline
 * (the outbox). [key] names the chat in the store: its stored session id, or [app.tsukaima.data.ChatStore.NEW_CHAT].
 *
 * An offline message waits [app.tsukaima.data.prefs.Settings.offlineSendMinutes] for the connection. Back in time,
 * it is sent like a typed one ([deliver]); too late, it returns to the composer as a draft, because a message that
 * starts an agent turn long after it was written may no longer be meant. The wait counts from when it was written
 * and survives the app being closed; only an open chat sends, so a message whose chat stays closed becomes a draft.
 */
internal class ChatOffline(
    private val backend: ChatBackend,
    private val scope: CoroutineScope,
    private val state: MutableStateFlow<ChatUiState>,
    private val key: String,
    private val deliver: (String) -> Boolean,
    private val nextId: () -> String,
) {
    private val store get() = backend.store
    private val timers = mutableMapOf<String, Job>()
    private val minutes get() = state.value.offlineSendMinutes

    /**
     * Loads what the phone kept for this chat: the draft (unless the composer already has text), the waiting
     * messages (those past their wait go to the composer), and with [transcript] the saved copy, shown until
     * Hermes' history replaces it. Nothing is shown over a transcript that is already there.
     */
    fun start(transcript: Boolean) {
        scope.launch {
            store.draft(key)?.takeIf { it.isNotBlank() }?.let { draft ->
                state.update { if (it.input.isEmpty()) it.copy(input = draft) else it }
            }
            val kept = store.outbox(key)
            if (kept.isNotEmpty()) {
                state.update { it.copy(outbox = kept) }
                // One whose wait ran out while the app was closed is a draft again, without a word; the rest wait on.
                val now = WallClock.now()
                kept.forEach { m -> if (minutes <= 0 || now >= SavedChat.expiresAt(m, minutes)) toComposer(m.id) else arm(m) }
            }
            if (!transcript) return@launch
            val saved = store.transcript(key) ?: return@launch
            state.update {
                if (it.chat.items.isNotEmpty() || it.chat.liveSessionId != null) it
                else it.copy(chat = it.chat.copy(items = SavedChat.items(saved)), savedAt = saved.at)
            }
        }
    }

    /** Send while offline: [text] leaves the composer and waits under the transcript. */
    fun enqueue(text: String) {
        val message = OutboxMessage(nextId(), text, WallClock.now())
        state.update { it.copy(input = "", outbox = it.outbox + message) }
        persist()
        arm(message)
    }

    /** Cancel on a waiting message: it goes back to the composer. */
    fun cancel(id: String) = toComposer(id)

    /**
     * Hermes is reachable and the chat has its session: the waiting messages go out in the order written. One whose
     * wait ran out meanwhile, or that cannot be sent now (the chat is archived, the context is compacting), returns
     * to the composer.
     */
    fun flush() {
        val now = WallClock.now()
        for (message in state.value.outbox) {
            if (minutes <= 0 || now >= SavedChat.expiresAt(message, minutes)) {
                toComposer(message.id)
                continue
            }
            take(message.id) ?: continue
            if (!deliver(message.text)) restore(message.text)
        }
    }

    /** The composer's text changed: it is this chat's draft from now on (blank forgets it). */
    fun saveDraft(text: String) {
        scope.launch { store.saveDraft(key, text) }
    }

    /** The chat is idle with Hermes' transcript on screen: that is the copy to open without Hermes next time. */
    fun saveTranscript(storedId: String, items: List<ChatItem>) {
        scope.launch { store.saveTranscript(storedId, SavedChat.save(items, WallClock.now())) }
    }

    /** The session was deleted: nothing of it stays on the phone. */
    fun forget(storedId: String) {
        timers.values.forEach(Job::cancel)
        timers.clear()
        scope.launch {
            store.forget(storedId)
            store.forget(key)
        }
    }

    private fun arm(message: OutboxMessage) {
        timers.remove(message.id)?.cancel()
        timers[message.id] = scope.launch {
            val wait = if (minutes <= 0) 0 else SavedChat.expiresAt(message, minutes) - WallClock.now()
            if (wait > 0) delay(wait)
            if (state.value.outbox.any { it.id == message.id }) {
                toComposer(message.id)
                backend.toast("Still offline: your message is back in the composer")
            }
        }
    }

    /** Takes message [id] out of the outbox; null when it is no longer there. */
    private fun take(id: String): OutboxMessage? {
        val message = state.value.outbox.firstOrNull { it.id == id } ?: return null
        timers.remove(id)?.cancel()
        state.update { it.copy(outbox = it.outbox.filterNot { m -> m.id == id }) }
        persist()
        return message
    }

    private fun toComposer(id: String) {
        take(id)?.let { restore(it.text) }
    }

    /** [text] back into the composer, under what is typed there already. */
    private fun restore(text: String) {
        state.update { it.copy(input = if (it.input.isBlank()) text else it.input.trimEnd() + "\n\n" + text) }
    }

    private fun persist() {
        val messages = state.value.outbox
        scope.launch { store.saveOutbox(key, messages) }
    }
}
