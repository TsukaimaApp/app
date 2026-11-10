package app.tsukaima.ui.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import app.tsukaima.data.gateway.GatewaySession
import app.tsukaima.domain.chat.ChatItem
import app.tsukaima.domain.chat.SubagentStatus

/**
 * The subagent watch page for [ChatViewModel]: what one delegated child is doing (`subagent.tail`, read every
 * [POLL_MS] while the page is open and the child runs), and two actions on it alone, which leave the turn and the
 * other children running: Stop (`subagent.interrupt`) and a message into it (`subagent.steer`).
 */
internal class ChatSubagents(
    private val session: GatewaySession,
    private val backend: ChatBackend,
    private val scope: CoroutineScope,
    private val state: MutableStateFlow<ChatUiState>,
) {
    private var poll: Job? = null

    private fun row(id: String?) = state.value.chat.items.firstOrNull { it.id == id } as? ChatItem.Subagent

    /** Opens the page for subagent row [id] and starts reading its transcript. */
    fun open(id: String) {
        val item = row(id) ?: return
        poll?.cancel()
        state.update { it.copy(subagent = SubagentWatch(id)) }
        val subagentId = item.subagentId ?: return
        poll = scope.launch {
            while (true) {
                val live = state.value.chat.liveSessionId
                val running = row(id)?.status == SubagentStatus.Running
                if (live != null && !state.value.offline) {
                    try {
                        val tail = session.subagentTail(live, subagentId)
                        state.update { s ->
                            val watch = s.subagent?.takeIf { it.id == id } ?: return@update s
                            // The server drops the transcript when the child ends: what was read last stays.
                            if (!tail.available && watch.tail != null) s
                            else s.copy(subagent = watch.copy(tail = tailBody(tail.text), available = tail.available))
                        }
                    } catch (c: CancellationException) {
                        throw c
                    } catch (_: Exception) {
                        state.update { s -> s.subagent?.takeIf { it.id == id && it.tail == null }?.let { s.copy(subagent = it.copy(tail = "")) } ?: s }
                    }
                }
                // One read after the end is the last: nothing more is written.
                if (!running) break
                delay(POLL_MS)
            }
        }
    }

    fun close() {
        poll?.cancel()
        poll = null
        state.update { it.copy(subagent = null) }
    }

    /** Stop on the open page: ends this child only. */
    fun stop() {
        val (live, subagentId) = target() ?: return
        scope.launch {
            try {
                backend.toast(if (session.subagentInterrupt(live, subagentId)) "Stopping the subagent" else GONE)
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                backend.toast("Couldn’t stop the subagent: ${e.message ?: "gateway error"}")
            }
        }
    }

    /** Sends [text] into the open page's child; [done] gets whether it was taken (the page then clears its field). */
    fun steer(text: String, done: (Boolean) -> Unit) {
        val message = text.trim()
        val (live, subagentId) = target() ?: return done(false)
        if (message.isEmpty()) return done(false)
        scope.launch {
            val taken = try {
                (session.subagentSteer(live, subagentId, message) == QUEUED).also { backend.toast(if (it) "Sent to the subagent" else GONE) }
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                backend.toast("Couldn’t reach the subagent: ${e.message ?: "gateway error"}")
                false
            }
            done(taken)
        }
    }

    /** The live session and `subagent_id` of the open page's child, when it can be acted on. */
    private fun target(): Pair<String, String>? {
        val s = state.value
        val subagentId = row(s.subagent?.id)?.subagentId ?: return null
        if (s.offline) return null.also { backend.toast("Not connected to Hermes") }
        val live = s.chat.liveSessionId ?: return null
        return live to subagentId
    }

    companion object {
        const val POLL_MS = 2_000L
        private const val QUEUED = "queued"
        private const val GONE = "The subagent is no longer running"

        /** The line that ends the header Hermes writes at the top of a child's live transcript. */
        private val HEADER_END = Regex("""^={8,}\s*$""", RegexOption.MULTILINE)

        /**
         * A child's live transcript without its file header (delegation id, goal, start time, "tail -f me"): the
         * step lines only. A tail cut inside the file has no header and is returned as it is.
         */
        fun tailBody(text: String): String {
            val end = HEADER_END.find(text) ?: return text.trim()
            return if (text.startsWith("=== Hermes subagent live transcript")) text.substring(end.range.last + 1).trim() else text.trim()
        }
    }
}
