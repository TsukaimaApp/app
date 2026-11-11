package app.tsukaima.domain.chat

/** Search inside the open chat: which rows hold [query], in transcript order. */
object ChatSearch {
    /** A query shorter than this finds nothing: one letter would match every row. */
    const val MIN_QUERY = 2

    /**
     * The ids of the rows whose text holds [query] (any case): a message's text, a reply's reasoning, a tool row's
     * name, command line and output. Cards and notices are not searched.
     */
    fun matches(items: List<ChatItem>, query: String): List<String> {
        val q = query.trim()
        if (q.length < MIN_QUERY) return emptyList()
        return items.filter { item -> texts(item).any { it.contains(q, ignoreCase = true) } }.map { it.id }
    }

    /** Whether [item]'s match for [query] is only in its reasoning: the row then has to show all of it. */
    fun onlyInReasoning(item: ChatItem, query: String): Boolean {
        val q = query.trim()
        return item is ChatItem.AssistantMessage && q.length >= MIN_QUERY &&
            !item.text.contains(q, ignoreCase = true) && item.reasoning?.contains(q, ignoreCase = true) == true
    }

    private fun texts(item: ChatItem): List<String> = when (item) {
        is ChatItem.UserMessage -> listOf(item.text)
        is ChatItem.AssistantMessage -> listOfNotNull(item.text, item.reasoning)
        is ChatItem.ToolCall -> listOfNotNull(item.name, item.context, item.output)
        else -> emptyList()
    }

    /** Where [query] occurs in [text] (any case), as start..end-exclusive ranges that do not overlap. */
    fun ranges(text: String, query: String): List<IntRange> {
        val q = query.trim()
        if (q.length < MIN_QUERY) return emptyList()
        val found = ArrayList<IntRange>()
        var from = 0
        while (true) {
            val at = text.indexOf(q, from, ignoreCase = true)
            if (at < 0) return found
            found += at until at + q.length
            from = at + q.length
        }
    }
}
