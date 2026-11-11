package app.tsukaima.domain.chat

import kotlinx.serialization.Serializable

/**
 * A chat's transcript as last seen, kept on the phone so the chat opens without Hermes ([at]: when it was saved,
 * epoch millis). Only what reads the same later is kept: messages, reasoning and finished tool rows. Cards that
 * need the server (approvals, questions, connections), notices and the todo list are not.
 */
@Serializable
data class SavedTranscript(val at: Long, val items: List<SavedItem> = emptyList())

/** One saved row; [kind] is `user`, `assistant` or `tool`. A field a kind does not use stays null. */
@Serializable
data class SavedItem(
    val kind: String,
    val id: String,
    val text: String = "",
    val reasoning: String? = null,
    val rowId: Long? = null,
    val at: Long? = null,
    val thoughtMs: Long? = null,
    val steer: Boolean = false,
    val name: String? = null,
    val context: String? = null,
    val status: String? = null,
    val output: String? = null,
    val durationS: Double? = null,
)

/** A message written without a connection, waiting to be sent ([at]: when it was written, epoch millis). */
@Serializable
data class OutboxMessage(val id: String, val text: String, val at: Long)

object SavedChat {
    /** The newest rows kept; a longer chat loses its start in the saved copy only. */
    const val MAX_ITEMS = 400

    /** A tool's output is cut to this many characters in the saved copy. */
    const val MAX_OUTPUT = 4_000

    /**
     * [items] as a saved copy made at [now]. A message still streaming, a send that failed or still waits, and a
     * tool still running are left out: the copy is what Hermes has stored.
     */
    fun save(items: List<ChatItem>, now: Long): SavedTranscript = SavedTranscript(
        now,
        items.mapNotNull { item ->
            when (item) {
                is ChatItem.UserMessage ->
                    if (item.failed || item.queued || item.steerWaiting) null
                    else SavedItem("user", item.id, item.text, rowId = item.rowId, at = item.at, steer = item.steer)
                is ChatItem.AssistantMessage ->
                    if (item.streaming) null
                    else SavedItem("assistant", item.id, item.text, reasoning = item.reasoning, rowId = item.rowId, thoughtMs = item.thoughtMs)
                is ChatItem.ToolCall ->
                    if (item.status == ToolStatus.Running) null
                    else SavedItem(
                        "tool", item.id, name = item.name, context = item.context, status = item.status.name,
                        output = item.output?.take(MAX_OUTPUT), durationS = item.durationS,
                    )
                else -> null
            }
        }.takeLast(MAX_ITEMS),
    )

    /** The rows of [saved], with the ids they had, so the history that replaces them keeps the list where it is. */
    fun items(saved: SavedTranscript): List<ChatItem> = saved.items.mapNotNull { s ->
        when (s.kind) {
            "user" -> ChatItem.UserMessage(s.id, s.text, rowId = s.rowId, steer = s.steer, at = s.at)
            "assistant" -> ChatItem.AssistantMessage(s.id, s.text, streaming = false, reasoning = s.reasoning, rowId = s.rowId, thoughtMs = s.thoughtMs)
            "tool" -> ChatItem.ToolCall(
                s.id, s.name ?: "tool", s.context, args = null,
                status = ToolStatus.entries.firstOrNull { it.name == s.status } ?: ToolStatus.Done,
                // A cut or missing output is asked for again once Hermes is back ([ChatItem.ToolCall.outputPending]).
                output = s.output, exitCode = null, durationS = s.durationS, outputPending = s.output == null,
            )
            else -> null
        }
    }

    /**
     * What an offline message does when its wait runs out: [minutes] after it was written it stops waiting and
     * goes back to the composer as a draft. 0 = it never sends by itself.
     */
    fun expiresAt(message: OutboxMessage, minutes: Int): Long = message.at + minutes * 60_000L
}
