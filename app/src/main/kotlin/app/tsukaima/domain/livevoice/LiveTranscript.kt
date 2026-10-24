package app.tsukaima.domain.livevoice

/** Bounded store of a live session's transcript: once more than [keep] fragments are held, the last [trimTo] stay. */
class LiveTranscript(private val keep: Int = 2_000, private val trimTo: Int = 1_500) {
    private val fragments = ArrayList<LiveFragment>()

    /** Number of fragments held. */
    val size: Int get() = fragments.size

    /** Adds [f], trimming the oldest when the store outgrows its bound. */
    fun append(f: LiveFragment) {
        fragments.add(f)
        if (fragments.size > keep) {
            val drop = fragments.size - trimTo
            if (drop > 0) fragments.subList(0, drop).clear()
        }
    }

    /** Recent fragments, oldest first: those ending within [windowMs] of the last one, at most [maxFragments]. */
    fun contextWindow(windowMs: Long = 300_000, maxFragments: Int = 80): List<LiveFragment> {
        val last = fragments.lastOrNull() ?: return emptyList()
        val from = last.endMs - windowMs
        return fragments.filter { it.endMs >= from }.takeLast(maxFragments)
    }
}
