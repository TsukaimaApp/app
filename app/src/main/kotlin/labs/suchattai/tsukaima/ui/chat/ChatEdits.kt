package labs.suchattai.tsukaima.ui.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import labs.suchattai.tsukaima.data.gateway.GatewaySession
import labs.suchattai.tsukaima.data.gateway.RpcException
import labs.suchattai.tsukaima.domain.chat.AgentStatus
import labs.suchattai.tsukaima.domain.chat.ChatItem
import labs.suchattai.tsukaima.domain.chat.ChatReducer

/**
 * Edit and Regenerate for [ChatViewModel]. Both rewind the transcript to a persisted user row and send a turn in
 * its place (`prompt.submit` with `truncate_before_row_id`): the later turns are dropped and the new bubble sits
 * where the old one was. The rewind is applied at once (the turn's events may arrive before the RPC returns) and
 * undone if the server refuses it ([failed]).
 *
 * Edit restores only the message's text: attachments it was sent with are not put back in the composer (the
 * caption says so). Regenerate replays what was submitted ([ChatItem.UserMessage.promptText]), so `@file:` refs
 * ride along; a send that queued images is not offered, since the server consumed them.
 *
 * [forgetFailed] drops the Retry state of failed bubbles a successful rewind removed; [reload] replaces the
 * transcript with the stored history (after a cut the server applied but could not follow with the new turn).
 */
internal class ChatEdits(
    private val session: GatewaySession,
    private val backend: ChatBackend,
    private val scope: CoroutineScope,
    private val state: MutableStateFlow<ChatUiState>,
    private val attachments: ChatAttachments,
    private val flair: () -> String,
    private val forgetFailed: (Set<String>) -> Unit,
    private val reload: suspend (live: String) -> Unit,
    private val nextLocalId: () -> String,
) {
    /** "Edit" on user message [id]: its text goes into the composer and the next send replaces it. */
    fun edit(id: String) {
        state.update { it.copy(messageMenu = null) }
        val item = state.value.chat.items.firstOrNull { it.id == id } as? ChatItem.UserMessage ?: return
        val row = item.rowId?.takeIf { !item.failed } ?: return
        state.update { it.copy(input = item.text, pendingEdit = row, editDropsAttachments = item.attachments.isNotEmpty()) }
    }

    /** A tap on the "Editing" caption: leaves edit mode and clears the composer. */
    fun cancel() = state.update { if (it.pendingEdit == null) it else it.copy(input = "", pendingEdit = null, editDropsAttachments = false) }

    /** Send while editing row [row]: [text] and the composer's ready attachments replace that message. */
    fun send(live: String, text: String, row: Long) {
        if (!rewindable(state.value)) return backend.toast(WAIT)
        rewind(live, text, attachments.takeReady(), row, fromComposer = true)
    }

    /** "Regenerate" on reply [assistantId]: sends what its user message submitted again, in its place. */
    fun regenerate(assistantId: String) {
        state.update { it.copy(messageMenu = null) }
        val s = state.value
        val user = regenerateSource(s.chat.items, assistantId) ?: return
        if (!rewindable(s)) return
        val live = s.chat.liveSessionId ?: return backend.toast("Still connecting to Hermes")
        rewind(
            live, user.text, emptyList(), user.rowId ?: return, fromComposer = false,
            prompt = user.promptText, shownAttachments = user.attachments,
        )
    }

    private fun rewind(
        live: String, text: String, sent: List<PendingAttachment>, row: Long, fromComposer: Boolean, prompt: String? = null,
        shownAttachments: List<String> = sent.map(PendingAttachment::name),
    ) {
        val localId = nextLocalId()
        val before = state.value
        val promptText = prompt ?: Attachments.promptText(text, sent)
        state.update {
            val cut = ChatReducer.onTruncated(it.chat, row)
            val chat = ChatReducer.onSubmitted(
                cut, text, localId, flair, shownAttachments,
                promptText = promptText, imagesSent = sent.any { a -> a.kind == AttachmentKind.Image },
            )
            if (fromComposer) it.copy(chat = chat, input = "", pendingEdit = null, editDropsAttachments = false) else it.copy(chat = chat)
        }
        val droppedFailed = before.chat.items
            .filter { it is ChatItem.UserMessage && it.failed && state.value.chat.items.none { kept -> kept.id == it.id } }
            .map { it.id }.toSet()
        scope.launch {
            try {
                val r = session.submit(live, promptText, truncateBeforeRowId = row)
                attachments.sent(sent)
                if (droppedFailed.isNotEmpty()) forgetFailed(droppedFailed)
                state.update { it.copy(chat = ChatReducer.onSubmitAccepted(it.chat, localId, r.userRowId, r.survivorUserRowIds)) }
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                failed(e, live, localId, before, text, sent, row, fromComposer)
            }
        }
    }

    /**
     * A refused rewind. Storage failures (5070–5072) come after the server archived the cut rows, so the history is
     * reloaded; anything else changed nothing there, so the transcript snapshot comes back (unless a rebuild from
     * history replaced it meanwhile). An edit keeps its text and attachments in the composer, and its row unless the
     * row is gone (4018).
     */
    private suspend fun failed(
        e: Exception, live: String, localId: String, before: ChatUiState, text: String, sent: List<PendingAttachment>,
        row: Long, fromComposer: Boolean,
    ) {
        val code = (e as? RpcException)?.code
        val archived = code in STORAGE_FAILURES
        val gone = code == TARGET_GONE
        state.update { s ->
            val items = if (!archived && s.chat.items.any { it.id == localId }) before.chat.items else s.chat.items
            val status = if (s.chat.status == AgentStatus.Offline) AgentStatus.Offline else AgentStatus.Idle
            val restored = s.copy(chat = s.chat.copy(items = items, status = status))
            if (!fromComposer) return@update restored
            val keepRow = !archived && !gone
            restored.copy(
                input = s.input.ifEmpty { text },
                pendingEdit = row.takeIf { keepRow },
                editDropsAttachments = before.editDropsAttachments && keepRow,
            )
        }
        if (fromComposer) {
            attachments.restore(sent)
            attachments.onLiveSession(live)
        }
        if (archived) reload(live)
        val what = if (fromComposer) "Couldn’t edit the message" else "Couldn’t regenerate the reply"
        backend.toast(
            when {
                code == SESSION_BUSY -> WAIT
                gone && fromComposer -> "That message is gone — send it as a new message or cancel"
                archived -> e.message ?: "$what: storage error"
                else -> "$what: ${e.message ?: "gateway error"}"
            },
        )
    }

    companion object {
        private const val WAIT = "Wait for the reply to finish"

        /** `prompt.submit`: a turn (or a subagent) is still running. */
        private const val SESSION_BUSY = 4009

        /** `prompt.submit`: the truncation target is no longer in the session history. */
        private const val TARGET_GONE = 4018

        /** `prompt.submit`: session storage failed after the cut was written (disk full, write error, unavailable). */
        private val STORAGE_FAILURES = 5070..5072

        /**
         * The user message reply [assistantId] answers: the nearest user message above it, if it has a persisted row
         * and did not queue images (else null: rewinding to an older row would drop that message too).
         */
        fun regenerateSource(items: List<ChatItem>, assistantId: String): ChatItem.UserMessage? {
            val index = items.indexOfFirst { it.id == assistantId && it is ChatItem.AssistantMessage }
            if (index < 0) return null
            val user = items.subList(0, index).lastOrNull { it is ChatItem.UserMessage } as? ChatItem.UserMessage
            return user?.takeIf { it.rowId != null && !it.failed && !it.imagesSent }
        }

        /** A rewind needs Hermes reachable, a writable chat, and no turn running or waiting on the user. */
        fun rewindable(s: ChatUiState): Boolean =
            s.writable && !s.compacting && (s.chat.status == AgentStatus.Idle || s.chat.status == AgentStatus.Stopped)
    }
}
