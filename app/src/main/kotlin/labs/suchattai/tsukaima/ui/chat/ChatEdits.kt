package labs.suchattai.tsukaima.ui.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import labs.suchattai.tsukaima.data.gateway.GatewaySession
import labs.suchattai.tsukaima.domain.chat.AgentStatus
import labs.suchattai.tsukaima.domain.chat.ChatItem
import labs.suchattai.tsukaima.domain.chat.ChatReducer

/**
 * Edit and Regenerate for [ChatViewModel]. Both rewind the transcript to a persisted user row and send a turn in
 * its place (`prompt.submit` with `truncate_before_row_id`): the later turns are dropped and the new bubble sits
 * where the old one was. The rewind is applied at once (the turn's events may arrive before the RPC returns) and
 * undone if the server refuses it, which toasts the server's reason.
 *
 * Edit restores only the message's text: attachments it was sent with are not put back in the composer.
 */
internal class ChatEdits(
    private val session: GatewaySession,
    private val backend: ChatBackend,
    private val scope: CoroutineScope,
    private val state: MutableStateFlow<ChatUiState>,
    private val attachments: ChatAttachments,
    private val flair: () -> String,
    private val nextLocalId: () -> String,
) {
    /** "Edit" on user message [id]: its text goes into the composer and the next send replaces it. */
    fun edit(id: String) {
        state.update { it.copy(messageMenu = null) }
        val item = state.value.chat.items.firstOrNull { it.id == id } as? ChatItem.UserMessage ?: return
        val row = item.rowId?.takeIf { !item.failed } ?: return
        state.update { it.copy(input = item.text, pendingEdit = row) }
    }

    /** A tap on the "Editing" caption: leaves edit mode and clears the composer. */
    fun cancel() = state.update { if (it.pendingEdit == null) it else it.copy(input = "", pendingEdit = null) }

    /** Send while editing row [row]: [text] and the composer's ready attachments replace that message. */
    fun send(live: String, text: String, row: Long) {
        if (!rewindable(state.value)) return backend.toast("Wait for the reply to finish")
        rewind(live, text, attachments.takeReady(), row, fromComposer = true)
    }

    /** "Regenerate" on reply [assistantId]: sends the user message it answered again, in its place. */
    fun regenerate(assistantId: String) {
        state.update { it.copy(messageMenu = null) }
        val s = state.value
        val user = regenerateSource(s.chat.items, assistantId) ?: return
        if (!rewindable(s)) return
        val live = s.chat.liveSessionId ?: return backend.toast("Still connecting to Hermes")
        rewind(live, user.text, emptyList(), user.rowId ?: return, fromComposer = false)
    }

    private fun rewind(live: String, text: String, sent: List<PendingAttachment>, row: Long, fromComposer: Boolean) {
        val localId = nextLocalId()
        val before = state.value.chat.items
        state.update {
            val chat = ChatReducer.onSubmitted(ChatReducer.onTruncated(it.chat, row), text, localId, flair, sent.map(PendingAttachment::name))
            if (fromComposer) it.copy(chat = chat, input = "", pendingEdit = null) else it.copy(chat = chat)
        }
        scope.launch {
            try {
                val r = session.submit(live, Attachments.promptText(text, sent), truncateBeforeRowId = row)
                attachments.sent(sent)
                state.update { it.copy(chat = ChatReducer.onSubmitAccepted(it.chat, localId, r.userRowId, r.survivorUserRowIds)) }
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                state.update { s ->
                    // A rebuild from history meanwhile (reconnect) already shows what the server kept.
                    val items = if (s.chat.items.any { it.id == localId }) before else s.chat.items
                    val status = if (s.chat.status == AgentStatus.Offline) AgentStatus.Offline else AgentStatus.Idle
                    val restored = s.copy(chat = s.chat.copy(items = items, status = status))
                    if (fromComposer) restored.copy(input = s.input.ifEmpty { text }, pendingEdit = row) else restored
                }
                if (fromComposer) {
                    attachments.restore(sent)
                    attachments.onLiveSession(live)
                }
                val what = if (fromComposer) "Couldn’t edit the message" else "Couldn’t regenerate the reply"
                backend.toast("$what: ${e.message ?: "gateway error"}")
            }
        }
    }

    companion object {
        /**
         * The user message reply [assistantId] answers: the nearest user message above it, if it has a persisted row
         * (else null: rewinding to an older row would drop that message too).
         */
        fun regenerateSource(items: List<ChatItem>, assistantId: String): ChatItem.UserMessage? {
            val index = items.indexOfFirst { it.id == assistantId && it is ChatItem.AssistantMessage }
            if (index < 0) return null
            val user = items.subList(0, index).lastOrNull { it is ChatItem.UserMessage } as? ChatItem.UserMessage
            return user?.takeIf { it.rowId != null && !it.failed }
        }

        /** A rewind needs Hermes reachable, a writable chat, and no turn running or waiting on the user. */
        fun rewindable(s: ChatUiState): Boolean =
            s.writable && !s.compacting && (s.chat.status == AgentStatus.Idle || s.chat.status == AgentStatus.Stopped)
    }
}
