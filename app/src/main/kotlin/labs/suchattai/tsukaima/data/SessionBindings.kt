package labs.suchattai.tsukaima.data

/**
 * Live session id → stored session id, as chats bind them, bounded to the latest [capacity]. Notifications
 * know only the live id a server request names; this maps it to the stored id a chat route opens. Thread-safe.
 */
class SessionBindings(private val capacity: Int = 64) {
    private val stored = object : LinkedHashMap<String, String>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > capacity
    }

    @Synchronized
    fun bind(live: String, storedId: String) {
        stored[live] = storedId
    }

    @Synchronized
    fun storedFor(live: String): String? = stored[live]
}
