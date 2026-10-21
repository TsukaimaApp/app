package app.tsukaima.ui.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import app.tsukaima.data.gateway.GatewaySession
import app.tsukaima.domain.chat.ChatReducer
import app.tsukaima.domain.chat.SideKind

/**
 * Side questions (`prompt.btw`) and background tasks (`prompt.background`) for [ChatViewModel]. The composer's text
 * goes to a detached side agent instead of the turn, so it works mid-turn and leaves the chat's status alone; the
 * answer arrives as a `btw.complete` / `background.complete` event ([ChatReducer]). Attachments are not sent with
 * it: ready ones stay in the composer for the next turn.
 */
internal class ChatSides(
    private val session: GatewaySession,
    private val backend: ChatBackend,
    private val scope: CoroutineScope,
    private val state: MutableStateFlow<ChatUiState>,
) {
    /**
     * Sends the composer's text (trimmed, non-blank) as [kind]. The composer clears at once; a refused send puts the
     * text back (unless something new was typed meanwhile) and toasts.
     */
    fun send(kind: SideKind) {
        val s = state.value
        val text = s.input.trim()
        if (text.isEmpty() || !s.writable || s.compacting || s.pendingEdit != null) return
        val live = s.chat.liveSessionId ?: return backend.toast("Still connecting to Hermes")
        if (s.attachments.any { it.status == AttachmentStatus.Ready }) backend.toast("Attachments aren’t sent with side questions")
        state.update { it.copy(input = "") }
        scope.launch {
            try {
                val taskId = when (kind) {
                    SideKind.Question -> session.btw(live, text)
                    SideKind.Background -> session.background(live, text)
                }
                state.update { it.copy(chat = ChatReducer.onSideStarted(it.chat, taskId, kind, text)) }
            } catch (c: CancellationException) {
                throw c
            } catch (_: Exception) {
                state.update { it.copy(input = it.input.ifEmpty { text }) }
                backend.toast(if (kind == SideKind.Question) "Couldn’t send the side question" else "Couldn’t send the background task")
            }
        }
    }

    companion object {
        /**
         * The overflow offers the side entries with text typed in a live, writable chat that is not compacting, and
         * not while a message is being edited (the composer holds the edit).
         */
        fun available(s: ChatUiState): Boolean =
            s.input.isNotBlank() && s.chat.liveSessionId != null && s.writable && !s.compacting && s.pendingEdit == null
    }
}
