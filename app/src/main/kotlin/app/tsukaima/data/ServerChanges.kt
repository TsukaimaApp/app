package app.tsukaima.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import app.tsukaima.data.gateway.Frame

/** Global gateway events (`session_id: ""`, empty payload) that say a server-side list changed. */
object ServerChanges {
    /** A session was created, titled, archived, deleted, or finished a turn: the drawer list is stale. */
    const val SESSIONS = "sessions.changed"

    /** A messaging platform connected or dropped: the Status rows are stale. */
    const val PLATFORMS = "platforms.changed"
}

/** One tick per event of [type]. */
fun Flow<Frame.Event>.ticks(type: String): Flow<Unit> = filter { it.type == type }.map { }
