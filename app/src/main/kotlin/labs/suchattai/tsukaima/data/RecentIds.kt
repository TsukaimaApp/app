package labs.suchattai.tsukaima.data

/** Bounded set of recently seen ids: once [capacity] is reached, the oldest is forgotten. Thread-safe. */
class RecentIds(val capacity: Int = 64) {
    private val ids = object : LinkedHashMap<String, Unit>(capacity, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>?) = size > capacity
    }

    /** True if [id] was not present (and is now recorded). */
    @Synchronized
    fun add(id: String): Boolean = ids.put(id, Unit) == null

    @Synchronized
    fun contains(id: String): Boolean = ids.containsKey(id)

    @Synchronized
    fun clear() = ids.clear()

    @Synchronized
    fun remove(id: String) {
        ids.remove(id)
    }

    @get:Synchronized
    val size: Int get() = ids.size
}
