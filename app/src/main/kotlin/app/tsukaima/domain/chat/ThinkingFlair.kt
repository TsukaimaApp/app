package app.tsukaima.domain.chat

import kotlin.random.Random

/**
 * Rotating "thinking" lines for the status line. Both lists are copied verbatim from
 * `design/components/chat/StatusLine.jsx` (`THINKING_FLAIR`, `THINKING_FLAIR_WATCHLIST`);
 * `ThinkingFlairTest.listsMatchDesignFile` keeps them in sync.
 */
object ThinkingFlair {
    val BASE: List<String> = listOf(
        "Thinking…", "Summoning…", "Charging ki…", "Powering up…",
        "Channeling…", "Reading the scroll…", "Consulting the shrine…", "Steeping matcha…",
        "Folding cranes…", "Sharpening the blade…", "Drawing the seal…", "Chanting…",
        "Gathering spirit energy…", "Training arc…", "Unlocking the next form…", "Meditating under the waterfall…",
        "Rolling the gacha…", "Inking the next panel…", "Grinding XP…", "Binding the contract…",
    )

    // one line per title rated 9+ on the maintainer's MAL — allusions, never quotes
    val WATCHLIST: List<String> = listOf(
        "Weighing the oddity…", "Checking if it\u2019s a fake…", "Letting the cat out…", "Taking the long way home…",
        "Checking the mirror…", "Solving the classroom riddle…", "Finishing the story…", "Bleeding a little iron…",
        "Reclaiming what was taken…", "Collecting twelve blades…", "Reading the fine print on the wish…", "Tuning the phone microwave…",
        "Lingering in déjà vu…", "Making a move on the board…", "Playing the final move…", "Walking the tiger home…",
        "Suspecting a conspiracy…", "Holding the line, Sergeant…", "Guarding the classroom…", "Granting a five-yen wish…",
        "Settling an old debt…", "Swapping places for a second…", "Running the cargo…", "Reloading both pistols…",
        "Following the blood trail…", "Polishing the gems…", "Betting it all on a game…", "Practicing the broom flight…",
        "Serving tea, dragon-style…", "Casting Explosion, once a day…", "Paying off the party\u2019s debt…", "Filing a complaint with Being X…",
        "Crossing through the gate…", "Starting over from the save point…", "Keeping a secret hobby…", "Finishing the life consultation…",
        "Transforming, reluctantly…", "Rebooting the undead…", "Tracing the blade…", "Diving into the net…",
    )

    private val ALL: List<String> = BASE + WATCHLIST

    /** How long a line stays before the next call may swap it: the design rotates every 2.6 s (`StatusLine.jsx`). */
    const val HOLD_MS = 2_600L

    @Volatile
    private var last: Int = -1

    @Volatile
    private var lastAt: Long = Long.MIN_VALUE

    /** Tests share this singleton; each starts from no previous line. */
    internal fun resetForTest() {
        last = -1
        lastAt = Long.MIN_VALUE
    }

    /**
     * A random line from both lists, never the same as the previous call's line. Callers ask on every streamed
     * delta (~30 per second), so the previous line is kept until [HOLD_MS] have passed since it was chosen.
     */
    fun next(random: Random = Random.Default, now: Long = System.currentTimeMillis()): String {
        val prev = last
        if (prev in ALL.indices && now - lastAt < HOLD_MS) return ALL[prev]
        lastAt = now
        val idx = if (prev !in ALL.indices) {
            random.nextInt(ALL.size)
        } else {
            random.nextInt(ALL.size - 1).let { if (it >= prev) it + 1 else it }
        }
        last = idx
        return ALL[idx]
    }
}
