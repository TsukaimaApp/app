package app.tsukaima.ui.chat

import app.tsukaima.data.gateway.TranscriptMessage
import app.tsukaima.domain.chat.ChatItem

/**
 * Where a branch cuts ([ChatViewModel.branch]). `session.branch {count}` keeps the first `count` rows of the
 * server's list of user and assistant rows with visible text (tool rows and reasoning-only rows are not in it;
 * verified on Hermes v0.23.1), so the cut is counted in that list, from the stored history.
 */
internal object ChatBranch {
    /** [count] rows are kept, the last of them reading [last]; [draft] is the user message left out, for the composer. */
    data class Cut(val count: Int, val last: String, val draft: String?)

    /** The persisted row a branch from [item] is addressed by; null for a row that cannot start one. */
    fun row(item: ChatItem): Long? = when (item) {
        is ChatItem.AssistantMessage -> item.rowId.takeIf { !item.streaming && item.text.isNotBlank() }
        is ChatItem.UserMessage -> item.rowId.takeIf { !item.failed && !item.queued && !item.steer }
        else -> null
    }

    /**
     * Whether [item]'s menu offers a branch: a persisted reply with text, or a persisted message of the user's that
     * is not the chat's first row with text (a branch must keep at least one row).
     */
    fun offered(items: List<ChatItem>, item: ChatItem): Boolean {
        row(item) ?: return false
        if (item !is ChatItem.UserMessage) return true
        return items.takeWhile { it !== item }.any { it is ChatItem.UserMessage && !it.failed && !it.queued || it is ChatItem.AssistantMessage && it.text.isNotBlank() }
    }

    /**
     * The cut for a branch from persisted row [row] of [history]: through it for a reply; before it for a user
     * message, whose text becomes the draft. Null when the row is not in the history, has no text, or nothing would
     * be kept.
     */
    fun count(history: List<TranscriptMessage>, row: Long): Cut? {
        val visible = history.filter { (it.role == "user" || it.role == "assistant") && it.text.isNotBlank() }
        val at = visible.indexOfFirst { it.rowId == row }
        if (at < 0) return null
        val target = visible[at]
        return if (target.role == "assistant") Cut(at + 1, target.text, draft = null)
        else if (at == 0) null
        else Cut(at, visible[at - 1].text, draft = target.text)
    }
}
